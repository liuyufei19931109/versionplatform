package com.local.release.miniprogram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.AdminUser;
import com.local.release.repo.AdminUserRepository;
import com.local.release.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.local.release.miniprogram.MiniProgramJob.*;

@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class MiniProgramServiceTest {
  @Autowired MiniProgramProjectRepository projects;
  @Autowired MiniProgramJobRepository jobs;
  @Autowired AdminUserRepository users;
  MiniProgramService service;MiniProgramSettings settings;MiniProgramRunner runner;PolicyGitRepository policy;ReleaseExecutor serial;MiniProgramProject project;
  MiniProgramReadiness readiness;
  final String sha="a".repeat(40);
  @BeforeEach void setup()throws Exception{
    jobs.deleteAll();projects.deleteAll();users.deleteAll();
    AdminUser admin=new AdminUser("admin","test");users.saveAndFlush(admin);
    AdminUser alice=new AdminUser("alice","test");alice.setMenuPermissions(List.of("miniprograms"));users.saveAndFlush(alice);
    users.saveAndFlush(new AdminUser("bob","test"));
    settings=new MiniProgramSettings();settings.enabled=true;settings.uploadEnabled=true;
    runner=mock(MiniProgramRunner.class);policy=mock(PolicyGitRepository.class);serial=mock(ReleaseExecutor.class);
    doAnswer(call->{call.<Runnable>getArgument(1).run();return null;}).when(serial).executeCancellableRuntimeOperation(anyString(),any());
    when(policy.check(anyString(),anyString())).thenReturn(new PolicyGitRepository.CheckResult("b".repeat(40),true));
    readiness=mock(MiniProgramReadiness.class);
    service=new MiniProgramService(settings,projects,jobs,new MiniProgramAccess(users),mock(MiniProgramGit.class),policy,runner,serial,new ObjectMapper().findAndRegisterModules(),readiness);
    project=service.save(null,MiniProgramConfigurationTest.config(true),"admin");
  }
  @AfterEach void stop()throws Exception{service.stop();Thread.sleep(50);}
  MiniProgramService.Request request(Action action){return new MiniProgramService.Request(project.getId(),action,"feature/demo",sha,"1.0.0","reviewed",UUID.randomUUID().toString());}
  @Test void hiddenFeatureAndMenuPermissionCannotBeBypassed()throws Exception{
    assertThatThrownBy(()->service.create(request(Action.PREVIEW),"bob")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(()->service.save(project.getId(),project.configuration(),"alice")).isInstanceOf(AccessDeniedException.class);
    settings.enabled=false;assertThatThrownBy(()->service.create(request(Action.PREVIEW),"admin")).hasMessageContaining("尚未启用");
    assertThat(jobs.count()).isZero();verifyNoInteractions(runner);
  }
  @Test void snapshotSurvivesConfigurationEditsAndRepeatedRequestsDoNotUploadTwice()throws Exception{
    CountDownLatch waiting=new CountDownLatch(1),resume=new CountDownLatch(1);
    doAnswer(call->{waiting.countDown();resume.await(5,TimeUnit.SECONDS);call.<Runnable>getArgument(1).run();return null;}).when(serial).executeCancellableRuntimeOperation(anyString(),any());
    var request=request(Action.UPLOAD);var job=service.create(request,"alice");assertThat(waiting.await(5,TimeUnit.SECONDS)).isTrue();
    assertThat(service.create(request,"alice").getId()).isEqualTo(job.getId());
    var c=project.configuration();service.save(project.getId(),new MiniProgramConfiguration("Renamed",c.code(),c.appId(),"https://github.com/other/repo.git",c.defaultBranch(),"",2,true,true),"admin");
    resume.countDown();assertThat(await(job.getId()).getStatus()).isEqualTo(Status.SUCCESS);
    verify(runner,times(1)).execute(any(),argThat(snapshot->snapshot.gitRepo().equals(c.gitRepo())&&snapshot.robot()==1),any());
    verify(policy).check(c.gitRepo(),sha);assertThat(jobs.count()).isEqualTo(1);
    assertThatThrownBy(()->service.create(new MiniProgramService.Request(project.getId(),Action.PREVIEW,request.branch(),sha,"1.0.1","",request.requestId()),"alice")).hasMessageContaining("请求标识");
  }
  @Test void baselineFailurePreventsWechatExecution()throws Exception{
    when(policy.check(anyString(),anyString())).thenReturn(new PolicyGitRepository.CheckResult("b".repeat(40),false));
    var job=await(service.create(request(Action.UPLOAD),"admin").getId());
    assertThat(job.getStatus()).isEqualTo(Status.FAILED);assertThat(job.getErrorMessage()).contains("master");verify(runner,never()).execute(any(),any(),any());
  }
  @Test void lostWechatResponseIsUnknownAndNeverRetriedOnRestart()throws Exception{
    doAnswer(call->{call.<BiConsumer<String,String>>getArgument(2).accept("WECHAT_TRANSFER","started");throw new IllegalStateException("SECRET");}).when(runner).execute(any(),any(),any());
    var job=await(service.create(request(Action.UPLOAD),"admin").getId());
    assertThat(job.getStatus()).isEqualTo(Status.UNKNOWN);assertThat(job.getErrorMessage()).contains("待核对").doesNotContain("SECRET");
    service.recover();assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(Status.UNKNOWN);verify(runner,times(1)).execute(any(),any(),any());
  }
  @Test void cancellingAWaiterDoesNotStartChecksOrUpload()throws Exception{
    CountDownLatch waiting=new CountDownLatch(1);
    doAnswer(call->{waiting.countDown();new CountDownLatch(1).await();return null;}).when(serial).executeCancellableRuntimeOperation(anyString(),any());
    var job=service.create(request(Action.PREVIEW),"alice");assertThat(waiting.await(5,TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(()->service.cancel(job.getId(),"bob")).isInstanceOf(AccessDeniedException.class);
    service.cancel(job.getId(),"alice");assertThat(await(job.getId()).getStatus()).isEqualTo(Status.CANCELLED);verifyNoInteractions(policy);verify(runner,never()).execute(any(),any(),any());
  }
  @Test void explicitWechatRejectionIsFailedWithAnActionableReason()throws Exception{
    doAnswer(call->{call.<BiConsumer<String,String>>getArgument(2).accept("WECHAT_TRANSFER","started");throw new MiniProgramRunner.RemoteRejected("微信拒绝连接，请检查上传 IP 白名单");}).when(runner).execute(any(),any(),any());
    var job=await(service.create(request(Action.UPLOAD),"admin").getId());
    assertThat(job.getStatus()).isEqualTo(Status.FAILED);assertThat(job.getErrorMessage()).contains("IP 白名单");
    verify(runner,times(1)).execute(any(),any(),any());
  }
  @Test void startupRecoveryDoesNotReplayQueuedOrTransferringTasks(){
    var queued=new MiniProgramJob(project,Action.PREVIEW,"master",sha,"1.0.0","","admin","{}","recovery-queued","a");
    var transfer=new MiniProgramJob(project,Action.UPLOAD,"master",sha,"1.0.0","","admin","{}","recovery-transfer","b");transfer.start();transfer.stage("WECHAT_TRANSFER");
    queued=jobs.saveAndFlush(queued);transfer=jobs.saveAndFlush(transfer);service.recover();
    assertThat(jobs.findById(queued.getId()).orElseThrow().getStatus()).isEqualTo(Status.FAILED);
    assertThat(jobs.findById(transfer.getId()).orElseThrow().getStatus()).isEqualTo(Status.UNKNOWN);verifyNoInteractions(serial,runner,policy);
  }
  @Test void administratorReconciliationIsAuditedIdempotentAndDoesNotRewriteExecution()throws Exception{
    var unknown=historyJob(project,Action.UPLOAD,Status.UNKNOWN);
    var finished=jobs.findById(unknown.getId()).orElseThrow().getFinishedAt();
    assertThatThrownBy(()->service.create(request(Action.UPLOAD),"admin")).isInstanceOf(MiniProgramService.Conflict.class).hasMessageContaining("#"+unknown.getId());
    var result=new MiniProgramService.Reconciliation(Resolution.CONFIRMED_UPLOADED,"微信后台已找到 1.0.0\n已核对上传时间和说明");
    assertThatThrownBy(()->service.reconcile(unknown.getId(),result,"alice")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(()->service.reconcile(unknown.getId(),new MiniProgramService.Reconciliation(result.resolution()," "),"admin")).hasMessageContaining("核对说明");
    var saved=service.reconcile(unknown.getId(),result,"admin");
    assertThat(saved.getStatus()).isEqualTo(Status.UNKNOWN);assertThat(saved.getFinishedAt()).isEqualTo(finished);
    assertThat(saved.getResolution()).isEqualTo(Resolution.CONFIRMED_UPLOADED);assertThat(saved.getResolvedBy()).isEqualTo("admin");assertThat(saved.getResolutionNote()).isEqualTo(result.note());
    var resolvedAt=jobs.findById(unknown.getId()).orElseThrow().getResolvedAt();
    assertThat(service.reconcile(unknown.getId(),result,"admin").getResolvedAt()).isEqualTo(resolvedAt);
    assertThatThrownBy(()->service.reconcile(unknown.getId(),new MiniProgramService.Reconciliation(Resolution.CONFIRMED_NOT_UPLOADED,"另一个结果"),"admin")).isInstanceOf(MiniProgramService.Conflict.class);
    verifyNoInteractions(serial,policy,runner);
    assertThat(await(service.create(request(Action.UPLOAD),"admin").getId()).getStatus()).isEqualTo(Status.SUCCESS);
  }
  @Test void laterQueuedUploadRechecksUnresolvedEarlierOutcomeBeforeCallingWechat()throws Exception{
    CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);
    doAnswer(call->{call.<BiConsumer<String,String>>getArgument(2).accept("WECHAT_TRANSFER","started");started.countDown();finish.await(5,TimeUnit.SECONDS);throw new IllegalStateException("lost response");}).when(runner).execute(any(),any(),any());
    var first=service.create(request(Action.UPLOAD),"admin");assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
    var second=service.create(request(Action.UPLOAD),"alice");finish.countDown();
    assertThat(await(first.getId()).getStatus()).isEqualTo(Status.UNKNOWN);
    var blocked=await(second.getId());assertThat(blocked.getStatus()).isEqualTo(Status.FAILED);assertThat(blocked.getErrorMessage()).contains("结果待核对");
    verify(runner,times(1)).execute(any(),any(),any());
  }
  @Test void onlyUnknownUploadCanBeReconciledAndOtherOperationsRemainAvailable()throws Exception{
    var preview=historyJob(project,Action.PREVIEW,Status.UNKNOWN);
    assertThatThrownBy(()->service.reconcile(preview.getId(),new MiniProgramService.Reconciliation(Resolution.CONFIRMED_UPLOADED,"核对"),"admin")).isInstanceOf(MiniProgramService.Conflict.class);
    var upload=historyJob(project,Action.UPLOAD,Status.UNKNOWN);
    assertThat(await(service.create(request(Action.PREVIEW),"admin").getId()).getStatus()).isEqualTo(Status.SUCCESS);
    service.reconcile(upload.getId(),new MiniProgramService.Reconciliation(Resolution.CONFIRMED_NOT_UPLOADED,"微信后台未找到此版本，核对时间已记录"),"admin");
    assertThat(await(service.create(request(Action.UPLOAD),"admin").getId()).getStatus()).isEqualTo(Status.SUCCESS);
  }
  @Test void historyFiltersAndPaginationRemainScopedToOneProject(){
    var c=project.configuration();var other=service.save(null,new MiniProgramConfiguration("Other","other-mini","wx1111111111111111",c.gitRepo(),c.defaultBranch(),"",1,true,true),"admin");
    historyJob(other,Action.UPLOAD,Status.UNKNOWN);
    historyJob(project,Action.PREVIEW,Status.SUCCESS);
    var older=historyJob(project,Action.UPLOAD,Status.UNKNOWN);var newer=historyJob(project,Action.UPLOAD,Status.UNKNOWN);
    var first=service.history(project.getId(),Action.UPLOAD,Status.UNKNOWN,false,1,1,"alice");
    assertThat(first.total()).isEqualTo(2);assertThat(first.items()).extracting(MiniProgramService.JobSummary::id).containsExactly(newer.getId());
    assertThat(service.history(project.getId(),Action.UPLOAD,null,false,2,1,"alice").items()).extracting(MiniProgramService.JobSummary::id).containsExactly(older.getId());
    service.reconcile(older.getId(),new MiniProgramService.Reconciliation(Resolution.CONFIRMED_NOT_UPLOADED,"已确认"),"admin");
    assertThat(service.history(project.getId(),null,null,true,1,20,"alice").items()).extracting(MiniProgramService.JobSummary::id).containsExactly(newer.getId());
    assertThatThrownBy(()->service.history(project.getId(),null,null,false,0,20,"alice")).hasMessageContaining("分页");
    assertThatThrownBy(()->service.history(project.getId(),null,null,false,1,100,"alice")).hasMessageContaining("分页");
    assertThatThrownBy(()->service.history(project.getId(),null,null,false,1,20,"bob")).isInstanceOf(AccessDeniedException.class);
  }
  @Test void readinessExplainsUnresolvedUploadWithoutBlockingPreviewOrExecutingTasks()throws Exception{
    when(readiness.inspect(any())).thenReturn(new MiniProgramReadiness.Report(true,true,true,java.time.LocalDateTime.now(),List.of()));
    var unknown=historyJob(project,Action.UPLOAD,Status.UNKNOWN);
    var report=service.readiness(project.getId(),"alice");
    assertThat(report.localReady()).isTrue();assertThat(report.previewReady()).isTrue();assertThat(report.uploadReady()).isFalse();
    assertThat(report.checks()).anyMatch(c->c.message().contains("#"+unknown.getId()));
    verifyNoInteractions(serial,runner,policy);
  }
  MiniProgramJob historyJob(MiniProgramProject p,Action action,Status status){
    var item=new MiniProgramJob(p,action,"master",sha,"1.0.0","","admin","{}",UUID.randomUUID().toString(),"fingerprint");
    item.finish(status,null);return jobs.saveAndFlush(item);
  }
  MiniProgramJob await(Long id)throws Exception{
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
    while(System.nanoTime()<deadline){var job=jobs.findById(id).orElseThrow();if(job.getFinishedAt()!=null)return job;Thread.sleep(20);}
    throw new AssertionError("mini job did not finish: "+jobs.findById(id).orElseThrow().getStatus());
  }
}
