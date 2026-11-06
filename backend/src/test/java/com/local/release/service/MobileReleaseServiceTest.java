package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.security.TotpService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class MobileReleaseServiceTest {
  @Autowired ReleaseTaskRepository tasks;@Autowired AppServiceRepository apps;@Autowired ArtifactRepository artifacts;
  @Autowired MobileDeliveryRepository deliveries;@Autowired PlatformTransactionManager manager;
  @TempDir Path directory;
  FlutterBuildService flutter;ProductionMergeService merge;TotpService totp;ApplicationEventPublisher events;MobileReleaseService service;
  AppService app;ReleaseTask build;Artifact artifact;MobileArtifact metadata;
  static final String SHA="a".repeat(40),DIGEST="b".repeat(64);
  @BeforeEach void setup()throws Exception{
    deliveries.deleteAll();artifacts.deleteAll();tasks.deleteAll();apps.deleteAll();
    flutter=mock(FlutterBuildService.class);merge=mock(ProductionMergeService.class);totp=mock(TotpService.class);events=mock(ApplicationEventPublisher.class);
    service=new MobileReleaseService(tasks,apps,artifacts,deliveries,flutter,totp,merge,events,manager);
    service.clock=Clock.fixed(Instant.parse("2026-10-16T01:00:00Z"),ZoneId.of("Asia/Shanghai"));
    app=new AppService();app.setServiceKey("android");app.setName("测试 App");app.setServiceType(ServiceType.FLUTTER_ANDROID);app=apps.saveAndFlush(app);
    build=new ReleaseTask(app.getId(),TaskKind.BUILD,null,"build","release/v1",null,false);build.setStatus(TaskStatus.SUCCESS);build.setCommitId(SHA);build.setDeliveryType("MOBILE");build=tasks.saveAndFlush(build);
    artifact=artifacts.saveAndFlush(new Artifact(app.getId(),build.getId(),"release-v1-b1",build.getBranch(),SHA,null));
    metadata=new MobileArtifact(artifact.getId(),build.getId(),app.getId(),"com.example.app","1.0",1,DIGEST,3,"c".repeat(64),SHA,"3.35.4","private");
    Path file=Files.write(directory.resolve("bundle.aab"),new byte[]{1,2,3});
    when(flutter.metadata(artifact.getId())).thenReturn(metadata);when(flutter.verifiedFile(artifact.getId())).thenReturn(file);
    bindBuild("prod","RELEASE");
  }
  void bindBuild(String env,String mode){
    String format="DEBUG".equals(mode)?"APK":"AAB";
    app.setConfigJson("{\"mobileBuild\":{\"flutterVersion\":\"3.35.4\",\"environments\":{\""+env+"\":{\"enabled\":true,\"applicationId\":\"com.example.app\",\"expectedCertificateSha256\":\""+"c".repeat(64)+"\",\"signingProfile\":\"test-profile\",\"outputFormat\":\""+format+"\",\"buildMode\":\""+mode+"\",\"allowDebugSigning\":"+"DEBUG".equals(mode)+"}}}}");
    ReflectionTestUtils.setField(build,"environmentName",env);ReflectionTestUtils.setField(build,"mobileBuildConfigSnapshot",null);
    build.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.snapshot(app,env));build=tasks.saveAndFlush(build);
    metadata.setBuildEnvironmentName(env);metadata.setBuildMode(mode);ReflectionTestUtils.setField(metadata,"format",format);
  }
  String request(){return UUID.randomUUID().toString();}
  MobileReleaseService.Create create(String env,String request){return new MobileReleaseService.Create(app.getId(),artifact.getId(),env,"发布",request,"123456");}
  MobileReleaseService.Confirm confirm(boolean success,String request){return new MobileReleaseService.Confirm(artifact.getId(),DIGEST,success,request,"外部渠道确认");}
  @ParameterizedTest @ValueSource(strings={"dev","test","prod"})
  void eachEnvironmentReceivesItsOwnImmutablePackageAndOnlyProductionMerges(String env){
    bindBuild(env,"RELEASE");
    var created=service.create(create(env,request()),"admin");
    assertThat(created.status()).isEqualTo(TaskStatus.WAITING_CONFIRMATION);assertThat(created.deliveryType()).isEqualTo("MOBILE");
    assertThat(created.environmentName()).isEqualTo(env);assertThat(created.commitId()).isEqualTo(SHA);verifyNoInteractions(merge,events);
    if(env.equals("prod"))verify(totp).verifyAndConsume("admin","123456");else verifyNoInteractions(totp);
    service.downloaded(created.id(),"publisher");assertThat(service.detail(created.id()).task().status()).isEqualTo(TaskStatus.WAITING_CONFIRMATION);verifyNoInteractions(merge);
    var result=service.confirm(created.id(),confirm(true,request()),"publisher");
    assertThat(result.task().status()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(tasks.findById(created.id()).orElseThrow().isMergeEnqueuePending()).isEqualTo(env.equals("prod"));
    if(env.equals("prod"))verify(merge).ensureForSuccessfulDeployment(any());else verifyNoInteractions(merge);
    verify(events,times(1)).publishEvent(any(WecomNotificationService.ReleaseFinished.class));
  }
  @Test void creationRetriesReturnSameRecordWithoutConsumingAnotherProductionCode(){
    String id=request();var first=service.create(create("prod",id),"admin");
    assertThat(service.create(create("prod",id),"admin").id()).isEqualTo(first.id());verify(totp,times(1)).verifyAndConsume(anyString(),anyString());
    assertThatThrownBy(()->service.create(create("test",id),"admin")).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void productionDeliveryUsesTheLoggedInAccountAndRejectsItsInvalidCodeBeforeWriting(){
    String id=request();var created=service.create(create("prod",id),"yuan");
    assertThat(tasks.findById(created.id()).orElseThrow().getCreatedBy()).isEqualTo("yuan");
    assertThat(service.create(create("prod",id),"yuan").id()).isEqualTo(created.id());
    verify(totp,times(1)).verifyAndConsume("yuan","123456");verifyNoMoreInteractions(totp);
    long before=tasks.count(),beforeDeliveries=deliveries.count();
    doThrow(new IllegalArgumentException("动态码不正确")).when(totp).verifyAndConsume("other","123456");
    assertThatThrownBy(()->service.create(create("prod",request()),"other")).hasMessage("动态码不正确");
    assertThat(tasks.count()).isEqualTo(before);assertThat(deliveries.count()).isEqualTo(beforeDeliveries);
  }
  @Test void missingDownloadWrongArtifactWrongHashAndWrongEnvironmentRecordCannotConfirm(){
    var prod=service.create(create("prod",request()),"admin");var test=service.create(create("prod",request()),"admin");
    service.downloaded(test.id(),"user");
    assertThatThrownBy(()->service.confirm(prod.id(),confirm(true,request()),"user")).hasMessageContaining("先通过本发布记录拉取");
    service.downloaded(prod.id(),"user");
    assertThatThrownBy(()->service.confirm(prod.id(),new MobileReleaseService.Confirm(artifact.getId()+1,DIGEST,true,request(),null),"user")).hasMessageContaining("不一致");
    assertThatThrownBy(()->service.confirm(prod.id(),new MobileReleaseService.Confirm(artifact.getId(),"d".repeat(64),true,request(),null),"user")).hasMessageContaining("不一致");
    assertThat(service.detail(prod.id()).task().status()).isEqualTo(TaskStatus.WAITING_CONFIRMATION);verifyNoInteractions(merge,events);
  }
  @Test void confirmationIdempotenceRejectsAnyMutationAndNotificationFailureDoesNotUndoSuccess(){
    var prod=service.create(create("prod",request()),"admin");service.downloaded(prod.id(),"publisher");String id=request();
    doThrow(new IllegalStateException("temporary failure")).when(merge).ensureForSuccessfulDeployment(any());
    doThrow(new IllegalStateException("temporary failure")).when(events).publishEvent(any(Object.class));
    service.confirm(prod.id(),confirm(true,id),"publisher");service.confirm(prod.id(),confirm(true,id),"publisher");
    assertThat(service.detail(prod.id()).task().status()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(tasks.findById(prod.id()).orElseThrow().isMergeEnqueuePending()).isTrue();
    verify(merge,times(1)).ensureForSuccessfulDeployment(any());
    assertThatThrownBy(()->service.confirm(prod.id(),confirm(false,id),"publisher")).hasMessageContaining("已有最终结果");
    assertThatThrownBy(()->service.confirm(prod.id(),confirm(true,request()),"publisher")).hasMessageContaining("已有最终结果");
  }
  @Test void productionFailureIsFinalWithoutMerge(){
    var prod=service.create(create("prod",request()),"admin");service.downloaded(prod.id(),"publisher");
    assertThat(service.confirm(prod.id(),confirm(false,request()),"publisher").task().status()).isEqualTo(TaskStatus.FAILED);verifyNoInteractions(merge);
  }
  @Test void redownloadingAfterConfirmationKeepsTheFirstRetrievalAuditTime(){
    bindBuild("test","RELEASE");
    var release=service.create(create("test",request()),"admin");service.downloaded(release.id(),"first");var first=service.detail(release.id()).downloadedAt();
    service.clock=Clock.offset(service.clock,Duration.ofMinutes(1));service.confirm(release.id(),confirm(true,request()),"publisher");
    service.clock=Clock.offset(service.clock,Duration.ofMinutes(1));service.downloaded(release.id(),"second");
    assertThat(service.detail(release.id()).downloadedAt()).isEqualTo(first);assertThat(deliveries.findByReleaseTaskId(release.id()).orElseThrow().getDownloadedBy()).isEqualTo("first");
  }
  @Test void separateCredentialsAreScopedRevocableRotatableAndExpire(){
    var a=service.create(create("prod",request()),"admin");var b=service.create(create("prod",request()),"admin");var token=service.issueAccess(a.id());String base="/api/mobile/releases/"+a.id();
    assertThat(service.validateAccess(token.fetchToken(),"GET",base+"/download")).isNotNull();
    assertThat(service.validateAccess(token.confirmToken(),"POST",base+"/confirm")).isNotNull();
    assertThat(service.validateAccess(token.fetchToken(),"POST",base+"/confirm")).isNull();
    assertThat(service.validateAccess(token.confirmToken(),"GET",base+"/download")).isNull();
    assertThat(service.validateAccess(token.fetchToken(),"GET","/api/mobile/releases/"+b.id()+"/download")).isNull();
    assertThat(service.validateAccess(token.confirmToken(),"POST","/api/mobile/releases")).isNull();
    assertThat(service.validateAccess(token.fetchToken(),"GET","/api/services")).isNull();
    var rotated=service.issueAccess(a.id());assertThat(service.validateAccess(token.fetchToken(),"GET",base)).isNull();
    assertThat(deliveries.findByReleaseTaskId(a.id()).orElseThrow().getFetchTokenHash()).doesNotContain(rotated.fetchToken());
    service.clock=Clock.offset(service.clock,Duration.ofHours(25));assertThat(service.validateAccess(rotated.fetchToken(),"GET",base)).isNull();
    var fresh=service.issueAccess(a.id());service.revokeAccess(a.id());assertThat(service.validateAccess(fresh.confirmToken(),"POST",base+"/confirm")).isNull();
  }
  @Test void failedOrForeignBuildCannotBeReleasedAndMissingArchiveConsumesNoApproval()throws Exception{
    build.setStatus(TaskStatus.FAILED);tasks.saveAndFlush(build);
    assertThatThrownBy(()->service.create(create("prod",request()),"admin")).hasMessageContaining("校验成功");
    build.setStatus(TaskStatus.SUCCESS);tasks.saveAndFlush(build);when(flutter.verifiedFile(artifact.getId())).thenThrow(new IllegalStateException("tampered"));
    assertThatThrownBy(()->service.create(create("prod",request()),"admin")).hasMessageContaining("校验失败");verifyNoInteractions(totp);
  }
  @Test void crossEnvironmentReleaseIsRejectedBeforeApprovalFileReadOrWrites()throws Exception{
    bindBuild("test","RELEASE");long before=tasks.count();
    assertThatThrownBy(()->service.create(create("prod",request()),"admin")).hasMessageContaining("构建环境与发布环境不一致");
    verifyNoInteractions(totp);verify(flutter,never()).verifiedFile(any());assertThat(tasks.count()).isEqualTo(before);assertThat(deliveries.count()).isZero();
  }
  @Test void preproductionMayPublishOnlyItsOwnExplicitlyRegisteredDebugPackage(){
    bindBuild("test","DEBUG");
    assertThat(service.create(create("test",request()),"admin").environmentName()).isEqualTo("test");
    assertThatThrownBy(()->service.create(create("prod",request()),"admin")).hasMessageContaining("构建环境与发布环境不一致");
    verifyNoInteractions(totp);assertThat(deliveries.count()).isEqualTo(1);
  }
  @Test void legacyUnboundArtifactsCannotCreateNewReleases(){
    metadata.setBuildEnvironmentName(null);metadata.setBuildMode(null);
    assertThatThrownBy(()->service.create(create("prod",request()),"admin")).hasMessageContaining("重新按环境打包");
    verifyNoInteractions(totp);assertThat(deliveries.count()).isZero();
  }
  @ParameterizedTest @ValueSource(strings={"buildMode","applicationId","certificateSha256","format","buildEnvironment"})
  void alteredMetadataOrTaskEnvironmentIsRejectedBeforeProductionApproval(String field){
    if(field.equals("buildEnvironment")){ReflectionTestUtils.setField(build,"environmentName","test");tasks.saveAndFlush(build);}
    else ReflectionTestUtils.setField(metadata,field,field.equals("buildMode")?"DEBUG":field.equals("format")?"APK":"changed");
    assertThatThrownBy(()->service.create(create("prod",request()),"admin")).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(totp);assertThat(deliveries.count()).isZero();
  }
  @Test void changingCurrentServiceProfileCannotChangeAnAlreadyBuiltPackage(){
    app.setConfigJson("{\"mobileBuild\":{\"environments\":{}}}");apps.saveAndFlush(app);
    assertThat(service.create(create("prod",request()),"admin").environmentName()).isEqualTo("prod");
  }
  @Test void existingDeliveryReplayDownloadAndConfirmationRemainAvailableForLegacyMetadata(){
    String id=request();var created=service.create(create("prod",id),"admin");
    metadata.setBuildEnvironmentName(null);metadata.setBuildMode(null);
    assertThat(service.create(create("prod",id),"admin").id()).isEqualTo(created.id());verify(totp,times(1)).verifyAndConsume(anyString(),anyString());
    assertThat(service.downloadFile(created.id())).exists();service.downloaded(created.id(),"publisher");
    assertThat(service.confirm(created.id(),confirm(true,request()),"publisher").task().status()).isEqualTo(TaskStatus.SUCCESS);
    verify(merge).ensureForSuccessfulDeployment(any());
  }
}
