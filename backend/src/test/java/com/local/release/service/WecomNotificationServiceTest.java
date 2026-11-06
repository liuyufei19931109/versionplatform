package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.domain.WecomNotification.Status;
import com.local.release.repo.*;
import com.local.release.security.CredentialSecretCrypto;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class WecomNotificationServiceTest {
  static final String WEBHOOK="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=00000000-0000-0000-0000-000000000001";
  @Autowired WecomNotificationSettingsRepository settings;
  @Autowired WecomNotificationRepository notifications;
  @Autowired ReleaseTaskRepository tasks;
  @Autowired AppServiceRepository services;
  @Autowired PlatformTransactionManager manager;
  @TempDir Path directory;
  WecomNotificationService service;WecomMessageTransport transport;CredentialSecretCrypto crypto;
  @BeforeEach void setup(){
    notifications.deleteAll();settings.deleteAll();tasks.deleteAll();services.deleteAll();
    crypto=new CredentialSecretCrypto(directory.resolve("test.key").toString());ReflectionTestUtils.invokeMethod(crypto,"initialize");
    transport=mock(WecomMessageTransport.class);
    service=new WecomNotificationService(settings,notifications,tasks,services,crypto,new ObjectMapper(),manager,transport);
    service.clock=Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"),ZoneId.of("Asia/Shanghai"));
    when(transport.send(anyString(),anyString())).thenReturn(new WecomMessageTransport.Delivery(true,false,null));
  }
  WecomNotificationService.SettingsRequest config(boolean enabled,String hook){return new WecomNotificationService.SettingsRequest(enabled,hook,"测试群","http://127.0.0.1",List.of("test","prod"),true,true);}
  void enable(){service.saveSettings(config(true,WEBHOOK));}
  ReleaseTask task(String env,ModelEnums.TaskKind kind,ModelEnums.TaskStatus status){
    AppService app=new AppService();app.setName("示例应用");app.setServiceKey(UUID.randomUUID().toString());services.saveAndFlush(app);
    ReleaseTask task=new ReleaseTask(app.getId(),kind,null,env,"master",null,false);task.setStatus(status);task.setCreatedBy("yuan");
    task.setCommitId("1234567890123456789012345678901234567890");task.setStartedAt(LocalDateTime.of(2026,10,14,15,58));task.setFinishedAt(LocalDateTime.of(2026,10,14,16,0));
    return tasks.saveAndFlush(task);
  }
  void finish(ReleaseTask task){service.completed(new WecomNotificationService.ReleaseFinished(task));}
  void forward(long seconds){service.clock=Clock.offset(service.clock,Duration.ofSeconds(seconds));}
  @Test void defaultsDoNotWriteOrBackfillHistoryAndWebhookIsEncrypted(){
    task("prod",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.SUCCESS);
    assertThat(service.getSettings().webhookConfigured()).isFalse();assertThat(settings.count()).isZero();enable();
    assertThat(notifications.count()).isZero();String stored=settings.findById(1L).orElseThrow().getEncryptedWebhook();
    assertThat(stored).doesNotContain("weixin").doesNotContain("00000000");assertThat(crypto.decrypt(stored)).isEqualTo(WEBHOOK);
    service.saveSettings(config(true,""));assertThat(settings.findById(1L).orElseThrow().getEncryptedWebhook()).isEqualTo(stored);
  }
  @Test void onlyEnabledDeploymentResultsForSelectedEnvironmentsEnqueue(){
    ReleaseTask production=task("prod",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.SUCCESS);finish(production);assertThat(notifications.count()).isZero();
    enable();finish(task("dev",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.SUCCESS));finish(task("test",ModelEnums.TaskKind.BUILD,ModelEnums.TaskStatus.SUCCESS));finish(task("test",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.RUNNING));
    assertThat(notifications.count()).isZero();finish(production);finish(task("test",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.FAILED));assertThat(notifications.count()).isEqualTo(2);
  }
  @Test void executionDeduplicatesButRetrySameTaskGetsNewImmutableSnapshot()throws Exception{
    enable();ReleaseTask task=task("test",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.FAILED);task.setErrorMessage("password=TOP-SECRET https://host/?token=PRIVATE");tasks.save(task);
    finish(task);finish(task);assertThat(notifications.count()).isEqualTo(1);
    String original=notifications.findAll().getFirst().getPayload();assertThat(original).doesNotContain("TOP-SECRET").doesNotContain("PRIVATE");
    task.setStatus(ModelEnums.TaskStatus.SUCCESS);task.setLastRetryRequestKey("retry-2");task.setStartedAt(task.getStartedAt().plusMinutes(3));task.setFinishedAt(task.getFinishedAt().plusMinutes(3));tasks.save(task);finish(task);
    assertThat(notifications.count()).isEqualTo(2);assertThat(notifications.findAll().getFirst().getPayload()).isEqualTo(original);
    var card=new ObjectMapper().readTree(notifications.findAll().getLast().getPayload()).path("template_card");
    assertThat(card.path("horizontal_content_list").size()).isEqualTo(6);assertThat(card.path("card_action").path("type").asInt()).isEqualTo(1);
    assertThat(card.toString()).contains("12345678").doesNotContain("123456789012");
  }
  @Test void successAndFailureCardsKeepServiceNameProminentAndColorTheEnvironmentIndependently()throws Exception{
    enable();
    for(var status:List.of(ModelEnums.TaskStatus.SUCCESS,ModelEnums.TaskStatus.FAILED)){
      boolean success=status==ModelEnums.TaskStatus.SUCCESS;
      ReleaseTask task=task(success?"test":"prod",ModelEnums.TaskKind.DEPLOY,status);
      String key=success?"example-console":"example-api";
      AppService app=services.findById(task.getServiceId()).orElseThrow();app.setServiceKey(key);services.saveAndFlush(app);
      finish(task);
      var card=new ObjectMapper().readTree(notifications.findByTaskIdOrderByIdDesc(task.getId()).getFirst().getPayload()).path("template_card");
      assertThat(card.path("main_title").path("title").asText()).isEqualTo("示例应用");
      assertThat(card.path("main_title").path("desc").asText()).isEqualTo(key);
      assertThat(card.path("source").path("desc").asText()).contains(success?"🟢 预生产":"🔴 生产");
      assertThat(card.path("source").path("desc_color").asInt()).isEqualTo(success?3:2);
      assertThat(card.path("sub_title_text").asText()).startsWith(success?"✅ 发布成功":"❌ 发布失败");
      assertThat(card.path("horizontal_content_list").size()).isEqualTo(6);
      assertThat(card.path("card_action").path("url").asText()).isEqualTo("http://127.0.0.1/releases/"+task.getId());
    }
    verifyNoInteractions(transport);
  }
  @Test void longUnicodeServiceNamesKeepResultVisibleWithoutBreakingCodePoints()throws Exception{
    enable();
    for(var status:List.of(ModelEnums.TaskStatus.SUCCESS,ModelEnums.TaskStatus.FAILED)){
      ReleaseTask task=task("test",ModelEnums.TaskKind.DEPLOY,status);
      AppService app=services.findById(task.getServiceId()).orElseThrow();app.setName("🚀服务面板".repeat(12));app.setServiceKey("🚀".repeat(35)+status);services.saveAndFlush(app);
      finish(task);
      var heading=new ObjectMapper().readTree(notifications.findByTaskIdOrderByIdDesc(task.getId()).getFirst().getPayload()).path("template_card").path("main_title");
      String title=heading.path("title").asText(),desc=heading.path("desc").asText();
      assertThat(title).startsWith("🚀服务面板").endsWith("…");
      assertThat(title.codePointCount(0,title.length())).isEqualTo(26);
      assertThat(desc).startsWith("🚀").endsWith("…");
      assertThat(desc.codePointCount(0,desc.length())).isEqualTo(30);
      assertThat(title.codePoints().anyMatch(c->c>=0xD800&&c<=0xDFFF)).isFalse();
      assertThat(desc.codePoints().anyMatch(c->c>=0xD800&&c<=0xDFFF)).isFalse();
    }
  }
  @Test void developmentNotificationsAreOptInAndEnvironmentColorNeverDependsOnResult()throws Exception {
    enable();finish(task("dev",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.SUCCESS));assertThat(notifications.count()).isZero();
    service.saveSettings(new WecomNotificationService.SettingsRequest(true,"","群","http://127.0.0.1",List.of("dev","test","prod"),true,true));
    for(String env:List.of("dev","test","prod"))for(var status:List.of(ModelEnums.TaskStatus.SUCCESS,ModelEnums.TaskStatus.FAILED)){
      ReleaseTask task=task(env,ModelEnums.TaskKind.DEPLOY,status);finish(task);
      var card=new ObjectMapper().readTree(notifications.findByTaskIdOrderByIdDesc(task.getId()).getFirst().getPayload()).path("template_card");
      assertThat(card.path("source").path("desc_color").asInt()).isEqualTo(env.equals("prod")?2:env.equals("test")?3:0);
      assertThat(card.path("source").path("desc").asText()).contains(WecomNotificationService.environmentLabel(env));
      assertThat(card.path("main_title").path("title").asText()).isEqualTo("示例应用");
    }
    assertThat(notifications.count()).isEqualTo(6);verifyNoInteractions(transport);
    assertThatThrownBy(()->service.saveSettings(new WecomNotificationService.SettingsRequest(true,"","群","http://127.0.0.1",List.of("staging"),true,true))).hasMessageContaining("通知环境");
  }
  @Test void successAndFailureFlagsAndDisabledDispatcherAreHonoured(){
    service.saveSettings(new WecomNotificationService.SettingsRequest(true,WEBHOOK,"群","http://127.0.0.1",List.of("prod"),false,true));
    finish(task("prod",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.SUCCESS));assertThat(notifications.count()).isZero();finish(task("prod",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.FAILED));
    service.saveSettings(config(false,""));service.dispatchOne();verifyNoInteractions(transport);assertThat(notifications.findAll().getFirst().getStatus()).isEqualTo(Status.PENDING);
  }
  @Test void backoffStopsAfterThreeFailuresAndRetryCannotResendPendingOrWrongTask(){
    enable();ReleaseTask task=task("test",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.SUCCESS);finish(task);Long id=notifications.findAll().getFirst().getId();
    when(transport.send(anyString(),anyString())).thenReturn(new WecomMessageTransport.Delivery(false,true,"请求超时"));
    service.dispatchOne();service.dispatchOne();verify(transport,times(1)).send(anyString(),anyString());
    forward(10);service.dispatchOne();forward(30);service.dispatchOne();forward(120);service.dispatchOne();
    assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(Status.FAILED);verify(transport,times(3)).send(anyString(),anyString());
    ReleaseTask other=task("test",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.SUCCESS);
    assertThatThrownBy(()->service.retry(other.getId(),id)).hasMessageContaining("不属于");
    service.retry(task.getId(),id);assertThatThrownBy(()->service.retry(task.getId(),id)).hasMessageContaining("失败");
    when(transport.send(anyString(),anyString())).thenReturn(new WecomMessageTransport.Delivery(true,false,null));service.dispatchOne();
    assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(Status.SENT);assertThat(notifications.findById(id).orElseThrow().getAttempts()).isEqualTo(4);
    assertThatThrownBy(()->service.retry(task.getId(),id)).hasMessageContaining("失败");
  }
  @Test void concurrentManualRetryEnqueuesOnlyOnce()throws Exception{
    enable();ReleaseTask task=task("prod",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.FAILED);finish(task);WecomNotification row=notifications.findAll().getFirst();row.setStatus(Status.FAILED);notifications.saveAndFlush(row);
    try(var pool=Executors.newFixedThreadPool(2)){
      Callable<Boolean> call=()->{try{service.retry(task.getId(),row.getId());return true;}catch(IllegalArgumentException expected){return false;}};
      var results=pool.invokeAll(List.of(call,call));assertThat(results.stream().filter(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).count()).isEqualTo(1);
    }
    assertThat(notifications.count()).isEqualTo(1);
  }
  @Test void rateLimitPersistsAcrossDispatcherInstances(){
    enable();service.enqueueTest();service.enqueueTest();service.dispatchOne();service.dispatchOne();verify(transport,times(1)).send(anyString(),anyString());
    WecomNotificationService replacement=new WecomNotificationService(settings,notifications,tasks,services,crypto,new ObjectMapper(),manager,transport);replacement.clock=service.clock;
    replacement.dispatchOne();verify(transport,times(1)).send(anyString(),anyString());replacement.clock=Clock.offset(service.clock,Duration.ofSeconds(4));replacement.dispatchOne();verify(transport,times(2)).send(anyString(),anyString());
  }
  @Test void interruptedSendingIsRecoveredWithoutScanningHistoricalTasks(){
    enable();task("test",ModelEnums.TaskKind.DEPLOY,ModelEnums.TaskStatus.SUCCESS);service.enqueueTest();WecomNotification row=notifications.findAll().getFirst();row.setStatus(Status.SENDING);row.setCycleAttempts(1);row.setAttempts(1);row.setLastAttemptAt(LocalDateTime.now(service.clock).minusMinutes(2));notifications.saveAndFlush(row);
    service.dispatchOne();assertThat(notifications.findById(row.getId()).orElseThrow().getStatus()).isEqualTo(Status.PENDING);verifyNoInteractions(transport);forward(11);service.dispatchOne();assertThat(notifications.findById(row.getId()).orElseThrow().getStatus()).isEqualTo(Status.SENT);assertThat(notifications.count()).isEqualTo(1);
  }
  @Test void bootstrapIsOneShotAndDoesNotReplaceExistingDestination(){
    assertThat(service.initializeIfAbsent(config(true,WEBHOOK),true)).isTrue();assertThat(notifications.count()).isEqualTo(1);
    assertThat(service.initializeIfAbsent(config(true,WEBHOOK.replace("0001","0002")),true)).isFalse();assertThat(notifications.count()).isEqualTo(1);assertThat(crypto.decrypt(settings.findById(1L).orElseThrow().getEncryptedWebhook())).isEqualTo(WEBHOOK);
  }
}
