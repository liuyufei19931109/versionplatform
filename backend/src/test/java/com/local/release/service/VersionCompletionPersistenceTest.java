package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.*;
import com.local.release.security.CredentialSecretCrypto;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import javax.crypto.spec.SecretKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class VersionCompletionPersistenceTest {
  @Autowired VersionCompletionEventRepository events;
  @Autowired VersionBatchRepository versions;
  @Autowired VersionMemberRepository members;
  @Autowired VersionRepositoryRepository repositories;
  @Autowired AppServiceRepository services;
  @Autowired AdminUserRepository users;
  @Autowired ReleaseTaskRepository tasks;
  @Autowired ArtifactRepository artifacts;
  @Autowired MobileDeliveryRepository deliveries;
  @Autowired ServiceEnvironmentRepository environments;
  @Autowired EntityManager entities;
  @Autowired PlatformTransactionManager manager;
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  final LocalDateTime started=LocalDateTime.of(2026,10,20,9,0);
  VersionCompletionService completion;VersionBatch version;AppService backend;
  long sequence;
  @BeforeEach void setup(){
    events.deleteAll();deliveries.deleteAll();artifacts.deleteAll();tasks.deleteAll();members.deleteAll();repositories.deleteAll();environments.deleteAll();versions.deleteAll();services.deleteAll();users.deleteAll();
    CredentialSecretCrypto crypto=new CredentialSecretCrypto("unused-test-key");ReflectionTestUtils.setField(crypto,"key",new SecretKeySpec(new byte[32],"AES"));
    completion=new VersionCompletionService(events,versions,members,repositories,services,users,tasks,artifacts,deliveries,environments,entities,manager,json,crypto);
    completion.clock=Clock.fixed(Instant.parse("2026-10-20T02:00:00Z"),ZoneOffset.UTC);
    user("admin",List.of());user("Admin",List.of());user("viewer",List.of());user("web",List.of("versions","builds"));user("mobile",List.of("versions","apps"));user("version-only",List.of("versions"));
    version=versions.saveAndFlush(new VersionBatch(LocalDate.of(2026,10,20),"release20261020","admin"));
    backend=member("private-backend",ServiceType.BACKEND);
  }

  @Test void completeFreezesExactDeploymentAndIdempotentReplayDoesNotPublishOrAlterExistingRows()throws Exception{
    ReleaseTask published=deploy(backend,"prod",TaskStatus.SUCCESS,true);
    String beforeTask=json.writeValueAsString(tasks.findAll()),beforeVersion=json.writeValueAsString(versions.findAll()),beforeArtifact=json.writeValueAsString(artifacts.findAll());
    var input=input();var preview=completion.preview(version.getId(),input,"admin");
    assertThat(preview.counts()).isEqualTo(new VersionCompletionService.Counts(1,1,0,0));
    assertThat(preview.items().getFirst().taskId()).isEqualTo(published.getId());
    var confirmed=completion.complete(version.getId(),confirm(input,preview),"admin");
    assertThat(confirmed.state()).isEqualTo("COMPLETED");assertThat(events.count()).isEqualTo(1);
    assertThat(completion.complete(version.getId(),confirm(input,preview),"admin").current().id()).isEqualTo(confirmed.current().id());
    assertThat(events.count()).isEqualTo(1);
    assertThat(json.writeValueAsString(tasks.findAll())).isEqualTo(beforeTask);assertThat(json.writeValueAsString(versions.findAll())).isEqualTo(beforeVersion);assertThat(json.writeValueAsString(artifacts.findAll())).isEqualTo(beforeArtifact);
    ReleaseTask later=deploy(backend,"prod",TaskStatus.FAILED,true);
    var frozen=completion.detail(version.getId(),"admin").current();assertThat(frozen.items().getFirst().taskId()).isEqualTo(published.getId()).isNotEqualTo(later.getId());
    assertThat(frozen.items().getFirst().outcome()).isEqualTo("PUBLISHED");
  }

  @Test void onlyRealDeploymentsOfSameServiceBranchAndEnvironmentCanCount(){
    deploy(backend,"test",TaskStatus.SUCCESS,true);
    ReleaseTask other=deploy(backend,"prod",TaskStatus.SUCCESS,true);ReflectionTestUtils.setField(other,"branch","release20261022");tasks.saveAndFlush(other);
    ReleaseTask simulated=deploy(backend,"prod",TaskStatus.SUCCESS,true);ReflectionTestUtils.setField(simulated,"simulate",true);tasks.saveAndFlush(simulated);
    var options=completion.options(version.getId(),"prod","admin");assertThat(options.counts().published()).isZero();assertThat(options.items().getFirst().taskId()).isNull();
    assertThatThrownBy(()->completion.complete(version.getId(),confirm(input(),completion.preview(version.getId(),input(),"admin")),"admin")).hasMessageContaining("遗留事项");
    assertThat(events.count()).isZero();
  }

  @Test void exclusionReasonsAppearInBusinessDraftWithoutAutomaticTechnicalIdentifiers()throws Exception{
    AppService second=member("secret-service-name",ServiceType.FRONTEND);
    var input=new VersionCompletionService.Input("prod","页面操作优化","",List.of(new VersionCompletionService.Exclusion(backend.getId(),"支付验收尚未完成"),new VersionCompletionService.Exclusion(second.getId(),"联调延期")));
    var preview=completion.preview(version.getId(),input,"admin");assertThat(preview.summary()).contains("本次不发布安排","支付验收尚未完成","联调延期").doesNotContain(backend.getName(),second.getName(),version.getBranch());
    var complete=completion.complete(version.getId(),confirm(input,preview),"admin");assertThat(complete.current().counts()).isEqualTo(new VersionCompletionService.Counts(2,0,2,0));
    String publicJson=json.writeValueAsString(completion.latest("viewer"));assertThat(publicJson).contains("支付验收尚未完成").doesNotContain("serviceId","taskId","serverIds","commitId","branch",backend.getName(),second.getName());
    assertThatThrownBy(()->completion.preview(version.getId(),new VersionCompletionService.Input("prod","变更","",List.of(new VersionCompletionService.Exclusion(backend.getId(),""))),"admin")).hasMessageContaining("原因");
  }

  @Test void newerFailedPublishIsNotReplacedWithAnOlderSuccess(){
    deploy(backend,"prod",TaskStatus.SUCCESS,true);ReleaseTask failed=deploy(backend,"prod",TaskStatus.FAILED,true);
    var preview=completion.preview(version.getId(),input(),"admin");assertThat(preview.counts().published()).isZero();assertThat(preview.items().getFirst().taskId()).isEqualTo(failed.getId());
    assertThat(preview.items().getFirst().message()).contains("失败");
  }

  @Test void runningOrPendingAttemptBlocksClosureEvenIfExcludedOrNotTheLatestCandidate(){
    ReleaseTask pending=deploy(backend,"prod",TaskStatus.RUNNING,true);deploy(backend,"prod",TaskStatus.FAILED,true);
    var input=new VersionCompletionService.Input("prod","变更","稍后发布",List.of(new VersionCompletionService.Exclusion(backend.getId(),"本次跳过")));
    assertThat(completion.options(version.getId(),"prod","admin").items()).hasSize(1);
    assertThatThrownBy(()->completion.preview(version.getId(),input,"admin")).hasMessageContaining("执行中");
    pending.setStatus(TaskStatus.CANCELLED);pending.setFinishedAt(started.plusHours(1));tasks.saveAndFlush(pending);
    var preview=completion.preview(version.getId(),input,"admin");deploy(backend,"prod",TaskStatus.PENDING,true);
    assertThatThrownBy(()->completion.complete(version.getId(),confirm(input,preview),"admin")).hasMessageContaining("执行中");assertThat(events.count()).isZero();
  }

  @Test void publishedModuleCannotBeMisreportedAsExcludedAndUnknownExclusionRejected(){
    deploy(backend,"prod",TaskStatus.SUCCESS,true);
    assertThatThrownBy(()->completion.preview(version.getId(),new VersionCompletionService.Input("prod","变更","",List.of(new VersionCompletionService.Exclusion(backend.getId(),"跳过"))),"admin")).hasMessageContaining("不能标为");
    assertThatThrownBy(()->completion.preview(version.getId(),new VersionCompletionService.Input("prod","变更","",List.of(new VersionCompletionService.Exclusion(999999L,"跳过"))),"admin")).hasMessageContaining("不属于");
  }

  @Test void taskChangeAndMembershipChangeAfterPreviewRequireFreshConfirmation(){
    ReleaseTask task=deploy(backend,"prod",TaskStatus.SUCCESS,true);var input=input();var old=completion.preview(version.getId(),input,"admin");
    task.setStatus(TaskStatus.FAILED);tasks.saveAndFlush(task);
    assertThatThrownBy(()->completion.complete(version.getId(),confirm(input,old),"admin")).hasMessageContaining("发布记录已变化");
    task.setStatus(TaskStatus.SUCCESS);tasks.saveAndFlush(task);var preview=completion.preview(version.getId(),input,"admin");
    member("new-member",ServiceType.FRONTEND);
    assertThatThrownBy(()->completion.complete(version.getId(),confirm(input,preview),"admin")).hasMessageContaining("范围或发布记录已变化");assertThat(events.count()).isZero();
  }

  @Test void authenticatedPreviewBindsActorInputsExpiryAndCannotBeForged(){
    deploy(backend,"prod",TaskStatus.SUCCESS,true);var input=input();var preview=completion.preview(version.getId(),input,"admin");
    assertThatThrownBy(()->completion.complete(version.getId(),confirm(input,preview),"Admin")).hasMessageContaining("操作者已变化");
    var edited=new VersionCompletionService.Input("prod","不同变更","",List.of());
    assertThatThrownBy(()->completion.complete(version.getId(),confirm(edited,preview),"admin")).hasMessageContaining("内容或操作者");
    var forged=new VersionCompletionService.Confirm("prod",input.changes(),"",List.of(),preview.summary(),"not-a-ticket");
    assertThatThrownBy(()->completion.complete(version.getId(),forged,"admin")).hasMessageContaining("凭证无效");
    completion.clock=Clock.offset(completion.clock,Duration.ofMinutes(11));
    assertThatThrownBy(()->completion.complete(version.getId(),confirm(input,preview),"admin")).hasMessageContaining("过期");assertThat(events.count()).isZero();
  }

  @Test void independentAdminConfirmationsSerializeToOneImmutableCompletion()throws Exception{
    deploy(backend,"prod",TaskStatus.SUCCESS,true);var input=input();var a=completion.preview(version.getId(),input,"admin");var b=completion.preview(version.getId(),input,"Admin");
    CountDownLatch start=new CountDownLatch(1);
    try(ExecutorService pool=Executors.newFixedThreadPool(2)){
      Future<String> first=pool.submit(()->{start.await();return attempt(input,a,"admin");});Future<String> second=pool.submit(()->{start.await();return attempt(input,b,"Admin");});start.countDown();
      assertThat(List.of(first.get(8,TimeUnit.SECONDS),second.get(8,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","STALE");
    }
    assertThat(events.count()).isEqualTo(1);
  }

  @Test void editsAndReopenAreAppendOnlyAndCannotReuseAnOldConfirmation(){
    ReleaseTask task=deploy(backend,"prod",TaskStatus.SUCCESS,true);var input=input();var preview=completion.preview(version.getId(),input,"admin");
    var initial=completion.complete(version.getId(),confirm(input,preview),"admin").current();String original=events.findById(initial.id()).orElseThrow().getContentJson();
    var edited=completion.edit(version.getId(),new VersionCompletionService.Edit(initial.id(),"补充变更","","补充小结"),"admin").current();
    assertThat(edited.items()).isEqualTo(initial.items());assertThat(edited.completedAt()).isEqualTo(initial.completedAt());assertThat(events.findById(initial.id()).orElseThrow().getContentJson()).isEqualTo(original);
    assertThatThrownBy(()->completion.edit(version.getId(),new VersionCompletionService.Edit(initial.id(),"过期变更","","旧小结"),"admin")).hasMessageContaining("其他操作更新");
    var reopened=completion.reopen(version.getId(),new VersionCompletionService.Reopen(edited.id(),"需要补发"),"admin");assertThat(reopened.state()).isEqualTo("OPEN");assertThat(reopened.history()).hasSize(3);assertThat(reopened.current().reason()).isEqualTo("需要补发");assertThat(completion.latest("viewer")).isNull();
    assertThatThrownBy(()->completion.complete(version.getId(),confirm(input,preview),"admin")).hasMessageContaining("状态已变化");
    var fresh=completion.preview(version.getId(),input,"admin");assertThat(completion.complete(version.getId(),confirm(input,fresh),"admin").history()).hasSize(4);
    assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.SUCCESS);
  }

  @Test void legacyNullScopeNeedsMatchingNonEmptyNodesAndExplicitPartialRemainsUnconfirmed(){
    ReleaseTask task=deploy(backend,"prod",TaskStatus.SUCCESS,null);
    assertThat(completion.options(version.getId(),"prod","admin").counts().published()).isEqualTo(1);
    task.setProductionScopeComplete(false);tasks.saveAndFlush(task);assertThat(completion.options(version.getId(),"prod","admin").counts().unconfirmed()).isEqualTo(1);
    task.setProductionScopeComplete(null);task.setTargetServerIds(List.of(72L));tasks.saveAndFlush(task);assertThat(completion.options(version.getId(),"prod","admin").counts().unconfirmed()).isEqualTo(1);
    task.setTargetServerIds(List.of());tasks.saveAndFlush(task);assertThat(completion.options(version.getId(),"prod","admin").counts().unconfirmed()).isEqualTo(1);
  }

  @Test void appDownloadOrDeliveryRecordAloneIsNotExternalPublishConfirmation(){
    AppService app=member("private-mobile",ServiceType.FLUTTER_ANDROID);ReleaseTask task=deploy(app,"prod",TaskStatus.WAITING_CONFIRMATION,true);
    MobileDelivery delivery=new MobileDelivery(task.getId(),task.getArtifactId(),"b".repeat(64));delivery.setDownloadedAt(started);delivery=deliveries.saveAndFlush(delivery);
    assertThat(itemFor(completion.options(version.getId(),"prod","admin"),app).outcome()).isEqualTo("UNCONFIRMED");
    task.setStatus(TaskStatus.SUCCESS);task.setFinishedAt(started.plusHours(1));tasks.saveAndFlush(task);
    assertThat(itemFor(completion.options(version.getId(),"prod","admin"),app).outcome()).isEqualTo("UNCONFIRMED");
    delivery.setConfirmationStatus("SUCCESS");delivery.setConfirmedAt(started.plusHours(1));deliveries.saveAndFlush(delivery);
    assertThat(itemFor(completion.options(version.getId(),"prod","admin"),app).outcome()).isEqualTo("PUBLISHED");
  }

  @Test void publicSummariesAreVisibleWithoutMenusButTechnicalItemsFollowOriginalPermissions()throws Exception{
    AppService app=member("private-mobile",ServiceType.FLUTTER_ANDROID);
    var input=new VersionCompletionService.Input("prod","可公开业务变更","等待验收",List.of());var preview=completion.preview(version.getId(),input,"admin");completion.complete(version.getId(),confirm(input,preview),"admin");
    assertThat(completion.latest("viewer").summary()).contains("可公开业务变更");
    assertThatThrownBy(()->completion.detail(version.getId(),"viewer")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(()->completion.detail(version.getId(),"version-only")).isInstanceOf(AccessDeniedException.class);
    assertThat(completion.detail(version.getId(),"web").current().items()).extracting(VersionCompletionService.Item::serviceId).containsExactly(backend.getId());
    assertThat(completion.detail(version.getId(),"mobile").current().items()).extracting(VersionCompletionService.Item::serviceId).containsExactly(app.getId());
    assertThat(completion.states("web")).hasSize(1);assertThat(completion.states("version-only")).isEmpty();assertThatThrownBy(()->completion.states("viewer")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(()->completion.options(version.getId(),"prod","web")).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->completion.preview(version.getId(),input,"web")).isInstanceOf(AccessDeniedException.class);
    String serialized=json.writeValueAsString(completion.detail(version.getId(),"web"));assertThat(serialized).doesNotContain(app.getName(),app.getServiceKey());
    AdminUser disabled=users.findByUsername("viewer").orElseThrow();disabled.setEnabled(false);users.saveAndFlush(disabled);assertThatThrownBy(()->completion.latest("viewer")).isInstanceOf(AccessDeniedException.class);
  }

  private String attempt(VersionCompletionService.Input input,VersionCompletionService.Preview preview,String actor){try{completion.complete(version.getId(),confirm(input,preview),actor);return "OK";}catch(IllegalArgumentException expected){return "STALE";}}
  private VersionCompletionService.Item itemFor(VersionCompletionService.Options options,AppService service){return options.items().stream().filter(item->item.serviceId().equals(service.getId())).findFirst().orElseThrow();}
  private VersionCompletionService.Input input(){return new VersionCompletionService.Input("prod","新增搜索筛选","",List.of());}
  private VersionCompletionService.Confirm confirm(VersionCompletionService.Input input,VersionCompletionService.Preview preview){return new VersionCompletionService.Confirm(input.environment(),input.changes(),input.remaining(),input.exclusions(),preview.summary(),preview.fingerprint());}
  private void user(String name,List<String> permissions){AdminUser user=new AdminUser(name,"unused");user.setMenuPermissions(permissions);users.saveAndFlush(user);}
  private AppService member(String name,ServiceType type){
    AppService service=new AppService();service.setName(name);service.setServiceKey(name);service.setServiceType(type);service.setGitRepo("https://github.com/example/"+name);service=services.saveAndFlush(service);
    VersionRepository repository=new VersionRepository(version.getId(),PolicyGitRepository.identity(service.getGitRepo()),"encrypted-fixture","admin");repository.prepare("encrypted-fixture","a".repeat(40),"baseline","admin");repository.result("SUCCESS",null,"created");repository=repositories.saveAndFlush(repository);
    members.saveAndFlush(new VersionMember(version.getId(),repository.getId(),service));
    for(String env:List.of("dev","test","prod")){ServiceEnvironment configuration=new ServiceEnvironment();configuration.setServiceId(service.getId());configuration.setEnvironmentName(env);configuration.setServerIds(List.of(71L));environments.saveAndFlush(configuration);}
    return service;
  }
  private ReleaseTask deploy(AppService service,String env,TaskStatus status,Boolean scope){
    String sha=String.format("%040x",++sequence);boolean mobile=service.getServiceType()==ServiceType.FLUTTER_ANDROID;
    ReleaseTask build=new ReleaseTask(service.getId(),TaskKind.BUILD,null,env,version.getBranch(),"build",false);build.setCommitId(sha);build.setStatus(TaskStatus.SUCCESS);build.setCreatedBy("admin");build.setStartedAt(started.plusSeconds(sequence));build.setFinishedAt(started.plusSeconds(sequence+1));if(mobile)build.setDeliveryType("MOBILE");build=tasks.saveAndFlush(build);
    Artifact artifact=artifacts.saveAndFlush(new Artifact(service.getId(),build.getId(),"package-"+sequence,version.getBranch(),sha,"registry.invalid/fixture:"+sequence));
    ReleaseTask task=new ReleaseTask(service.getId(),TaskKind.DEPLOY,artifact.getId(),env,version.getBranch(),"publish",false);task.setCommitId(sha);task.setStatus(status);task.setCreatedBy("admin");task.setTargetServerIds(List.of(71L));task.setProductionScopeComplete(scope);task.setStartedAt(started.plusMinutes(sequence));if(!Set.of(TaskStatus.PENDING,TaskStatus.RUNNING,TaskStatus.WAITING_CONFIRMATION).contains(status))task.setFinishedAt(started.plusMinutes(sequence).plusSeconds(30));if(mobile)task.setDeliveryType("MOBILE");return tasks.saveAndFlush(task);
  }
}
