package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.local.release.domain.*;
import com.local.release.repo.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;

/** Real database transactions: read-only previews, immutable history and durable cross-instance counters. */
@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class MobileVersionServiceTest {
  static final ObjectMapper JSON=new ObjectMapper();
  static final String SHA="a".repeat(40),OTHER_SHA="b".repeat(40),CERT="c".repeat(64),PROD_CERT="d".repeat(64),BRANCH="release20261018";
  @Autowired MobileVersionSequenceRepository sequences;
  @Autowired MobileVersionAllocationRepository allocations;
  @Autowired AppServiceRepository apps;
  @Autowired ReleaseTaskRepository tasks;
  @Autowired ArtifactRepository artifacts;
  @Autowired MobileArtifactRepository metadata;
  @Autowired PlatformTransactionManager manager;
  MobileVersionService service;
  AppService app;

  @BeforeEach void setup(){
    allocations.deleteAll();sequences.deleteAll();metadata.deleteAll();artifacts.deleteAll();tasks.deleteAll();apps.deleteAll();
    service=instance();app=app("demo-app","com.example.demo.pre");
  }
  MobileVersionService instance(){return new MobileVersionService(sequences,allocations,apps,tasks,artifacts,metadata);}
  AppService app(String key,String preId){
    AppService value=new AppService();value.setName(key);value.setServiceKey(key);value.setServiceType(ServiceType.FLUTTER_ANDROID);
    value.setGitRepo("git@github.com:example/"+key+".git");value.setDefaultBranch("master");value.setConfigJson(config(preId));return apps.saveAndFlush(value);
  }
  String config(String preId){
    return """
      {"mobileBuild":{"versionPolicy":"AUTO","flutterVersion":"3.47.3","outputFormat":"APK","environments":{
        "dev":{"enabled":true,"applicationId":"%s","expectedCertificateSha256":"%s","signingProfile":"pre-sign","buildMode":"DEBUG","allowDebugSigning":true},
        "test":{"enabled":true,"applicationId":"%s","expectedCertificateSha256":"%s","signingProfile":"pre-sign","buildMode":"DEBUG","allowDebugSigning":true},
        "prod":{"enabled":true,"applicationId":"%s.prod","expectedCertificateSha256":"%s","signingProfile":"prod-sign","buildMode":"RELEASE"}}}}
      """.formatted(preId,CERT,preId,CERT,preId,PROD_CERT);
  }
  void setting(AppService value,String env,String key,Object input)throws Exception{
    ObjectNode root=(ObjectNode)JSON.readTree(value.getConfigJson());ObjectNode target=env==null?(ObjectNode)root.path("mobileBuild"):(ObjectNode)root.path("mobileBuild").path("environments").path(env);
    target.set(key,JSON.valueToTree(input));value.setConfigJson(root.toString());apps.saveAndFlush(value);
  }
  ReleaseTask history(AppService owner,String env,String name,long code,TaskStatus status,String commit)throws Exception{
    ObjectNode snapshot=(ObjectNode)JSON.readTree(FlutterBuildConfiguration.snapshot(owner,env));
    ((ObjectNode)snapshot.path("mobileBuild")).put("versionName",name).put("versionCode",code);
    ReleaseTask task=new ReleaseTask(owner.getId(),TaskKind.BUILD,null,env,BRANCH,null,false);task.setDeliveryType("MOBILE");task.setCommitId(commit);task.setCreatedBy("admin");task.setStatus(status);task.setMobileBuildConfigSnapshot(snapshot.toString());return tasks.saveAndFlush(task);
  }
  ReleaseTask archive(AppService owner,String env,String name,long code,String commit)throws Exception{
    ReleaseTask task=history(owner,env,name,code,TaskStatus.SUCCESS,commit);
    Artifact artifact=artifacts.saveAndFlush(new Artifact(owner.getId(),task.getId(),"build-"+task.getId(),BRANCH,commit,null));
    var conf=FlutterBuildConfiguration.fromTask(task);
    MobileArtifact file=new MobileArtifact(artifact.getId(),task.getId(),owner.getId(),conf.applicationId(),name,code,"e".repeat(64),1024,conf.expectedCertificateSha256(),commit,conf.flutterVersion(),"fixture/app.apk",null,null,"APK");
    file.setBuildEnvironmentName(env);file.setBuildMode(conf.buildMode());metadata.saveAndFlush(file);return task;
  }
  ReleaseTask allocate(MobileVersionService allocator,AppService owner,String env,Long pre){
    return new TransactionTemplate(manager).execute(status->{
      var reservation=allocator.reserve(owner,FlutterBuildConfiguration.forEnvironment(owner,env),BRANCH,SHA,pre,"admin");
      ReleaseTask task=new ReleaseTask(owner.getId(),TaskKind.BUILD,null,env,BRANCH,null,false);task.setDeliveryType("MOBILE");task.setCommitId(SHA);task.setCreatedBy("admin");
      task.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.assignedSnapshot(owner,reservation.config(),reservation.allocation().getBaseVersion(),pre));
      task=tasks.saveAndFlush(task);allocator.bind(reservation,task.getId());return task;
    });
  }

  @Test void repeatedPreviewUsesHistoricalArtifactButNeverWritesOrConsumesNumbers()throws Exception{
    ReleaseTask historical=archive(app,"test","0.1.0-pre",21,SHA);String locked=historical.getMobileBuildConfigSnapshot();
    for(int i=0;i<3;i++){
      var preview=service.preview(app.getId(),"test",BRANCH,SHA,null);
      assertThat(preview.versionName()).isEqualTo("0.1.1-pre");assertThat(preview.versionCode()).isEqualTo(22L);assertThat(preview.allocationPending()).isTrue();
      assertThat(service.candidates(app.getId(),null,null)).extracting(MobileVersionService.Candidate::buildTaskId).containsExactly(historical.getId());
    }
    assertThat(allocations.count()).isZero();assertThat(sequences.count()).isZero();assertThat(tasks.count()).isEqualTo(1);
    assertThat(tasks.findById(historical.getId()).orElseThrow().getMobileBuildConfigSnapshot()).isEqualTo(locked);
  }

  @Test void productionWithoutSourceOnlyPromptsAndManualModeRemainsUnallocated()throws Exception{
    var prompt=service.preview(app.getId(),"prod",null,null,null);assertThat(prompt.versionName()).isNull();assertThat(prompt.preBuildTaskId()).isNull();
    setting(app,null,"versionPolicy","MANUAL");setting(app,"test","versionName","custom-original");setting(app,"test","versionCode",91);
    var manual=service.preview(app.getId(),"test",null,null,null);assertThat(manual.versionPolicy()).isEqualTo("MANUAL");assertThat(manual.versionName()).isEqualTo("custom-original");assertThat(manual.versionCode()).isEqualTo(91L);assertThat(manual.allocationPending()).isFalse();
    assertThatThrownBy(()->service.preview(app.getId(),"test",BRANCH,SHA,5L)).isInstanceOf(IllegalArgumentException.class);
    assertThat(sequences.count()).isZero();assertThat(allocations.count()).isZero();
  }

  @Test void failedCancelledAndQueuedHistoricalSnapshotsRemainHighWaterMarks()throws Exception{
    history(app,"test","0.1.2-pre",25,TaskStatus.FAILED,SHA);history(app,"test","0.1.3-pre",26,TaskStatus.CANCELLED,SHA);history(app,"test","0.1.4-pre",27,TaskStatus.PENDING,SHA);
    var result=service.preview(app.getId(),"test",BRANCH,SHA,null);assertThat(result.versionName()).isEqualTo("0.1.5-pre");assertThat(result.versionCode()).isEqualTo(28L);
  }

  @Test void decimalPatchAndMinorCarryAreAppliedToCommittedReservations()throws Exception{
    history(app,"test","0.1.9-pre",21,TaskStatus.SUCCESS,SHA);
    var first=allocate(service,app,"test",null);assertThat(FlutterBuildConfiguration.fromTask(first).versionName()).isEqualTo("0.2.0-pre");
    history(app,"test","0.9.9-pre",90,TaskStatus.SUCCESS,SHA);
    var second=allocate(service,app,"test",null);assertThat(FlutterBuildConfiguration.fromTask(second).versionName()).isEqualTo("1.0.0-pre");assertThat(FlutterBuildConfiguration.fromTask(second).versionCode()).isEqualTo(91L);
  }

  @Test void packageCodeIsSharedAcrossServicesAndNonProductionEnvironments()throws Exception{
    AppService sibling=app("second-app","com.example.demo.pre");setting(sibling,"dev","externalVersionCodeFloor",80);
    ReleaseTask one=allocate(service,app,"test",null),two=allocate(instance(),sibling,"dev",null),three=allocate(instance(),app,"dev",null);
    assertThat(List.of(one,two,three)).extracting(t->FlutterBuildConfiguration.fromTask(t).versionCode()).containsExactly(81L,82L,83L);
    assertThat(FlutterBuildConfiguration.fromTask(one).versionName()).isEqualTo("0.1.0-pre");assertThat(FlutterBuildConfiguration.fromTask(two).versionName()).isEqualTo("0.1.0-dev");
    assertThat(allocations.findByApplicationId("com.example.demo.pre")).hasSize(3);
  }

  @Test void counterAndAllocationRollBackTogetherIfTaskCreationFails(){
    assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->{
      service.reserve(app,FlutterBuildConfiguration.forEnvironment(app,"test"),BRANCH,SHA,null,"admin");throw new IllegalStateException("fixture task failure");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(sequences.count()).isZero();assertThat(allocations.count()).isZero();assertThat(tasks.count()).isZero();
    assertThat(FlutterBuildConfiguration.fromTask(allocate(service,app,"test",null)).versionCode()).isEqualTo(1L);
  }

  @Test void committedFailureKeepsItsAllocationAndSnapshotWhenNextTaskIsCreated(){
    ReleaseTask first=allocate(service,app,"test",null);String snapshot=first.getMobileBuildConfigSnapshot();first.setStatus(TaskStatus.FAILED);tasks.saveAndFlush(first);
    ReleaseTask second=allocate(instance(),app,"test",null);
    assertThat(FlutterBuildConfiguration.fromTask(second).versionName()).isEqualTo("0.1.1-pre");assertThat(FlutterBuildConfiguration.fromTask(second).versionCode()).isEqualTo(2L);
    assertThat(tasks.findById(first.getId()).orElseThrow().getMobileBuildConfigSnapshot()).isEqualTo(snapshot);assertThat(allocations.count()).isEqualTo(2);
  }

  @Test void initialRowsAndParallelInstancesAllocateUniqueContiguousNumbers()throws Exception{
    int count=4;CountDownLatch ready=new CountDownLatch(count),start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(count);
    try{
      List<Future<ReleaseTask>> results=new ArrayList<>();
      for(int i=0;i<count;i++)results.add(pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("start timeout");
        for(int attempt=0;;attempt++)try{return allocate(instance(),app,"test",null);}catch(DataIntegrityViolationException|PessimisticLockingFailureException race){if(attempt>=3)throw race;}
      }));
      assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();List<ReleaseTask> completed=new ArrayList<>();for(var future:results)completed.add(future.get(20,TimeUnit.SECONDS));
      assertThat(completed).extracting(t->FlutterBuildConfiguration.fromTask(t).versionCode()).containsExactlyInAnyOrder(1L,2L,3L,4L);
      assertThat(completed).extracting(t->FlutterBuildConfiguration.fromTask(t).versionName()).containsExactlyInAnyOrder("0.1.0-pre","0.1.1-pre","0.1.2-pre","0.1.3-pre");
      assertThat(allocations.count()).isEqualTo(count);assertThat(tasks.count()).isEqualTo(count);
      assertThat(allocations.findAll()).extracting(MobileVersionAllocation::getBuildTaskId).doesNotContainNull().doesNotHaveDuplicates();
    }finally{start.countDown();pool.shutdownNow();}
  }

  @Test void reserveAndBindRequireTheTaskTransactionAndAllocationCannotBeRebound(){
    assertThatThrownBy(()->service.reserve(app,FlutterBuildConfiguration.forEnvironment(app,"test"),BRANCH,SHA,null,"admin")).isInstanceOf(IllegalStateException.class);
    ReleaseTask task=allocate(service,app,"test",null);var allocation=allocations.findByBuildTaskId(task.getId()).orElseThrow();var reservation=new MobileVersionService.Reservation(allocation,FlutterBuildConfiguration.fromTask(task));
    assertThatThrownBy(()->service.bind(reservation,task.getId())).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->{service.bind(reservation,task.getId()+100);return null;})).isInstanceOf(IllegalStateException.class);
    assertThat(allocations.findByBuildTaskId(task.getId())).isPresent();
  }

  @Test void productionAllocatesIndependentDisplayNameWhileRetainingPreCodeAndSigningIdentity()throws Exception{
    ReleaseTask pre=archive(app,"test","0.1.8-pre",21,SHA);var preview=service.preview(app.getId(),"prod",BRANCH,SHA,pre.getId());
    assertThat(preview.versionName()).isEqualTo("0.0.1");assertThat(preview.baseVersion()).isEqualTo("0.0.1");
    ReleaseTask production=allocate(service,app,"prod",pre.getId());var config=FlutterBuildConfiguration.fromTask(production);
    assertThat(config.versionName()).isEqualTo("0.0.1");assertThat(config.applicationId()).endsWith(".prod");assertThat(config.expectedCertificateSha256()).isEqualTo(PROD_CERT);assertThat(config.buildMode()).isEqualTo("RELEASE");
    assertThat(FlutterBuildConfiguration.taskPreBuildTaskId(production)).isEqualTo(pre.getId());assertThat(production.getCommitId()).isEqualTo(pre.getCommitId());
    var rebuilt=FlutterBuildConfiguration.fromTask(allocate(instance(),app,"prod",pre.getId()));assertThat(rebuilt.versionName()).isEqualTo("0.0.2");assertThat(rebuilt.versionCode()).isEqualTo(config.versionCode()+1);
  }

  @Test void legacyProductionNamesRestartOnlyDisplaySeriesWithoutRewritingHistoryOrCode()throws Exception{
    ReleaseTask olderPre=archive(app,"test","0.1.7-pre",20,SHA),pre=archive(app,"test","0.1.8-pre",21,SHA);
    ReleaseTask old=archive(app,"prod","0.1.8-prd",1,SHA);String snapshot=old.getMobileBuildConfigSnapshot();
    var allocation=allocations.saveAndFlush(new MobileVersionAllocation(app.getId(),FlutterBuildConfiguration.fromTask(old).applicationId(),"prod","0.1.8-prd","0.1.8",1,pre.getId(),BRANCH,SHA,"admin"));
    allocation.bind(old.getId());allocations.saveAndFlush(allocation);sequences.saveAndFlush(new MobileVersionSequence("NAME:"+app.getId()+":prod",18));
    for(int i=0;i<3;i++){var preview=service.preview(app.getId(),"prod",BRANCH,SHA,pre.getId());assertThat(preview.versionName()).isEqualTo("0.0.1");assertThat(preview.versionCode()).isEqualTo(2L);}
    assertThat(sequences.count()).isEqualTo(1);assertThat(allocations.count()).isEqualTo(1);
    assertThatThrownBy(()->allocate(instance(),app,"prod",olderPre.getId())).hasMessageContaining("早于");
    ReleaseTask first=allocate(service,app,"prod",pre.getId());String firstSnapshot=first.getMobileBuildConfigSnapshot();
    assertThat(FlutterBuildConfiguration.fromTask(first).versionName()).isEqualTo("0.0.1");assertThat(FlutterBuildConfiguration.fromTask(first).versionCode()).isEqualTo(2L);
    first.setStatus(TaskStatus.FAILED);tasks.saveAndFlush(first);ReleaseTask second=allocate(instance(),app,"prod",pre.getId());
    assertThat(FlutterBuildConfiguration.fromTask(second).versionName()).isEqualTo("0.0.2");assertThat(FlutterBuildConfiguration.fromTask(second).versionCode()).isEqualTo(3L);
    assertThat(tasks.findById(old.getId()).orElseThrow().getMobileBuildConfigSnapshot()).isEqualTo(snapshot);
    assertThat(tasks.findById(first.getId()).orElseThrow().getMobileBuildConfigSnapshot()).isEqualTo(firstSnapshot);
    assertThat(metadata.findByBuildTaskId(old.getId()).orElseThrow().getVersionName()).isEqualTo("0.1.8-prd");
    assertThat(service.preview(app.getId(),"test",BRANCH,SHA,null).versionName()).isEqualTo("0.1.9-pre");
  }

  @Test void productionSeriesIsIndependentPerAppAndRespectsExternalAndroidCodeFloor()throws Exception{
    AppService local=app("alt-app","com.example.alt.pre");ReleaseTask demoPre=archive(app,"test","0.2.5-pre",30,SHA),otherPre=archive(local,"test","0.1.6-pre",7,SHA);
    setting(app,"prod","externalVersionCodeFloor",99);
    var primary=FlutterBuildConfiguration.fromTask(allocate(service,app,"prod",demoPre.getId()));
    var localConfig=FlutterBuildConfiguration.fromTask(allocate(instance(),local,"prod",otherPre.getId()));
    assertThat(primary.versionName()).isEqualTo("0.0.1");assertThat(primary.versionCode()).isEqualTo(100L);
    assertThat(localConfig.versionName()).isEqualTo("0.0.1");assertThat(localConfig.versionCode()).isEqualTo(1L);
    assertThat(service.preview(app.getId(),"prod",BRANCH,SHA,demoPre.getId()).versionName()).isEqualTo("0.0.2");
  }

  @Test void productionHistoryRecoversDisplayAndSourceFloorsWhenCountersAreAbsent()throws Exception{
    ReleaseTask older=archive(app,"test","0.1.7-pre",20,SHA),pre=archive(app,"test","0.2.5-pre",30,SHA);
    allocate(service,app,"prod",pre.getId());sequences.deleteAll();
    var next=service.preview(app.getId(),"prod",BRANCH,SHA,pre.getId());assertThat(next.versionName()).isEqualTo("0.0.2");assertThat(next.versionCode()).isEqualTo(2L);
    assertThatThrownBy(()->service.preview(app.getId(),"prod",BRANCH,SHA,older.getId())).hasMessageContaining("早于");
    allocations.deleteAll(); // Frozen tasks also retain the allocated display and source versions.
    assertThat(service.preview(app.getId(),"prod",BRANCH,SHA,pre.getId()).versionName()).isEqualTo("0.0.2");
    assertThatThrownBy(()->service.preview(app.getId(),"prod",BRANCH,SHA,older.getId())).hasMessageContaining("早于");
    history(app,"prod","0.0.9",9,TaskStatus.CANCELLED,SHA);
    assertThat(service.preview(app.getId(),"prod",BRANCH,SHA,pre.getId()).versionName()).isEqualTo("0.1.0");
  }

  @Test void concurrentProductionTasksAllocateDistinctNamesAndPreserveLegacyCodeFloor()throws Exception{
    ReleaseTask pre=archive(app,"test","0.1.8-pre",21,SHA);archive(app,"prod","0.1.8-prd",1,SHA);
    int count=4;CountDownLatch ready=new CountDownLatch(count),start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(count);
    try{List<Future<ReleaseTask>> results=new ArrayList<>();
      for(int i=0;i<count;i++)results.add(pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("start timeout");
        for(int attempt=0;;attempt++)try{return allocate(instance(),app,"prod",pre.getId());}catch(DataIntegrityViolationException|PessimisticLockingFailureException race){if(attempt>=3)throw race;}
      }));
      assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();List<FlutterBuildConfiguration> completed=new ArrayList<>();for(var future:results)completed.add(FlutterBuildConfiguration.fromTask(future.get(20,TimeUnit.SECONDS)));
      assertThat(completed).extracting(FlutterBuildConfiguration::versionName).containsExactlyInAnyOrder("0.0.1","0.0.2","0.0.3","0.0.4");
      assertThat(completed).extracting(FlutterBuildConfiguration::versionCode).containsExactlyInAnyOrder(2L,3L,4L,5L);
    }finally{start.countDown();pool.shutdownNow();}
  }

  @Test void productionSourceMismatchAndRollbackAreRejectedWithoutConsumingNumbers()throws Exception{
    ReleaseTask old=archive(app,"test","0.1.0-pre",10,SHA),latest=archive(app,"test","0.2.0-pre",20,SHA);
    assertThatThrownBy(()->service.preview(app.getId(),"prod","other-branch",SHA,latest.getId())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->service.preview(app.getId(),"prod",BRANCH,OTHER_SHA,latest.getId())).isInstanceOf(IllegalArgumentException.class);
    allocate(service,app,"prod",latest.getId());
    assertThatThrownBy(()->allocate(instance(),app,"prod",old.getId())).isInstanceOf(IllegalArgumentException.class);
    assertThat(allocations.count()).isEqualTo(1);assertThat(service.candidates(app.getId(),null,null)).extracting(MobileVersionService.Candidate::buildTaskId).containsExactly(latest.getId());
    assertThat(FlutterBuildConfiguration.fromTask(allocate(service,app,"prod",latest.getId())).versionCode()).isEqualTo(2L);
  }

  @Test void candidatesRequireSameAppExactCommitAndSuccessfulNonSimulatedMobilePre()throws Exception{
    ReleaseTask valid=archive(app,"test","0.1.0-pre",21,SHA),different=archive(app,"test","0.1.1-pre",22,OTHER_SHA);
    AppService other=app("other","com.example.other.pre");ReleaseTask foreign=archive(other,"test","0.1.0-pre",21,SHA);
    ReleaseTask failed=archive(app,"test","0.1.2-pre",23,SHA);failed.setStatus(TaskStatus.FAILED);tasks.saveAndFlush(failed);
    ReleaseTask simulated=archive(app,"test","0.1.3-pre",24,SHA);ReflectionTestUtils.setField(simulated,"simulate",true);tasks.saveAndFlush(simulated);
    ReleaseTask backend=archive(app,"test","0.1.4-pre",25,SHA);backend.setDeliveryType("SERVER");tasks.saveAndFlush(backend);
    ReleaseTask deploy=archive(app,"test","0.1.5-pre",26,SHA);ReflectionTestUtils.setField(deploy,"taskKind",TaskKind.DEPLOY);tasks.saveAndFlush(deploy);
    archive(app,"dev","0.1.6-dev",27,SHA);
    assertThat(service.candidates(app.getId(),BRANCH,SHA)).extracting(MobileVersionService.Candidate::buildTaskId).containsExactly(valid.getId());
    assertThat(service.candidates(app.getId(),null,null)).extracting(MobileVersionService.Candidate::buildTaskId).containsExactlyInAnyOrder(valid.getId(),different.getId());
    assertThatThrownBy(()->service.preview(app.getId(),"prod",BRANCH,SHA,foreign.getId())).isInstanceOf(IllegalArgumentException.class);
    assertThat(sequences.count()).isZero();assertThat(allocations.count()).isZero();
  }

  @Test void candidatesRejectArtifactSnapshotOrMetadataTampering()throws Exception{
    ReleaseTask wrongCert=archive(app,"test","0.1.0-pre",21,SHA);MobileArtifact file=metadata.findByBuildTaskId(wrongCert.getId()).orElseThrow();ReflectionTestUtils.setField(file,"certificateSha256",PROD_CERT);metadata.saveAndFlush(file);
    ReleaseTask wrongCommit=archive(app,"test","0.1.1-pre",22,SHA);Artifact artifact=artifacts.findByBuildTaskId(wrongCommit.getId()).orElseThrow();ReflectionTestUtils.setField(artifact,"commitId",OTHER_SHA);artifacts.saveAndFlush(artifact);
    ReleaseTask missing=history(app,"test","0.1.2-pre",23,TaskStatus.SUCCESS,SHA);
    assertThat(service.candidates(app.getId(),null,null)).isEmpty();
    for(long id:List.of(wrongCert.getId(),wrongCommit.getId(),missing.getId()))assertThatThrownBy(()->service.preview(app.getId(),"prod",BRANCH,SHA,id)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void exhaustedNumbersAndIncompatibleHistoryFailClosed()throws Exception{
    setting(app,"test","externalVersionCodeFloor",2100000000L);
    assertThatThrownBy(()->service.preview(app.getId(),"test",BRANCH,SHA,null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2100000000");
    setting(app,"test","externalVersionCodeFloor",0);
    history(app,"test","custom-vendor-name",21,TaskStatus.FAILED,SHA);
    assertThatThrownBy(()->service.preview(app.getId(),"test",BRANCH,SHA,null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("历史 App 版本名");
    assertThatThrownBy(()->allocate(service,app,"test",null)).isInstanceOf(IllegalArgumentException.class);assertThat(allocations.count()).isZero();assertThat(sequences.count()).isZero();
  }

  @Test void disabledAppsBackendServicesAndInvalidEnvironmentCannotPreviewOrList(){
    app.setEnabled(false);apps.saveAndFlush(app);assertThatThrownBy(()->service.preview(app.getId(),"test",BRANCH,SHA,null)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.candidates(app.getId(),null,null)).isInstanceOf(IllegalArgumentException.class);
    app.setEnabled(true);app.setServiceType(ServiceType.BACKEND);apps.saveAndFlush(app);assertThatThrownBy(()->service.preview(app.getId(),"test",BRANCH,SHA,null)).isInstanceOf(IllegalArgumentException.class);
    app.setServiceType(ServiceType.FLUTTER_ANDROID);apps.saveAndFlush(app);assertThatThrownBy(()->service.preview(app.getId(),"production",BRANCH,SHA,null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->service.preview(app.getId(),"test",BRANCH,SHA,1L)).isInstanceOf(IllegalArgumentException.class);assertThat(sequences.count()).isZero();assertThat(allocations.count()).isZero();
  }
}
