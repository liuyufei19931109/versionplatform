package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReleaseExecutorTest {
  static final String SHA="1234567890abcdef1234567890abcdef12345678";
  final Map<Long,ReleaseTask> taskRows=new ConcurrentHashMap<>();
  final Map<Long,List<ReleaseStep>> stepRows=new ConcurrentHashMap<>();
  final AtomicLong ids=new AtomicLong();
  ReleaseTaskRepository tasks;ReleaseStepRepository steps;AppServiceRepository services;CommandRunner runner;ReleaseExecutor executor;
  ArtifactRepository artifacts;BuildStorageService storage;PlatformTransactionManager tx;
  BranchPolicyService branchPolicy;FlutterBuildService flutter;ProductionMergeService productionMerge;
  ApplicationEventPublisher publisher;

  @BeforeEach void setup()throws Exception{
    tasks=mock(ReleaseTaskRepository.class);steps=mock(ReleaseStepRepository.class);services=mock(AppServiceRepository.class);runner=mock(CommandRunner.class);publisher=mock(ApplicationEventPublisher.class);
    tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
    artifacts=mock(ArtifactRepository.class);storage=mock(BuildStorageService.class);
    branchPolicy=mock(BranchPolicyService.class);flutter=mock(FlutterBuildService.class);productionMerge=mock(ProductionMergeService.class);
    executor=new ReleaseExecutor(tasks,steps,services,artifacts,mock(ServiceEnvironmentRepository.class),mock(DeploymentServerRepository.class),runner,publisher,tx,storage,branchPolicy,flutter,productionMerge);
    when(tasks.save(any())).thenAnswer(call->{ReleaseTask task=call.getArgument(0);if(task.getId()==null){ReflectionTestUtils.setField(task,"id",ids.incrementAndGet());ReflectionTestUtils.setField(task,"createdAt",LocalDateTime.now());}taskRows.put(task.getId(),task);return task;});
    when(tasks.findById(anyLong())).thenAnswer(call->Optional.ofNullable(taskRows.get(call.getArgument(0))));
    when(tasks.findByRequestKey(anyString())).thenAnswer(call->taskRows.values().stream().filter(t->Objects.equals(t.getRequestKey(),call.getArgument(0))).findFirst());
    when(tasks.findByStatusIn(anyList())).thenAnswer(call->taskRows.values().stream().filter(t->((List<?>)call.getArgument(0)).contains(t.getStatus())).toList());
    when(steps.save(any())).thenAnswer(call->{ReleaseStep step=call.getArgument(0);List<ReleaseStep> rows=stepRows.computeIfAbsent(step.getReleaseTaskId(),id->new ArrayList<>());if(!rows.contains(step))rows.add(step);return step;});
    when(steps.findByReleaseTaskIdOrderById(anyLong())).thenAnswer(call->stepRows.getOrDefault(call.getArgument(0),List.of()));
    when(services.findById(anyLong())).thenAnswer(call->{AppService service=new AppService();ReflectionTestUtils.setField(service,"id",call.getArgument(0));service.setName("service");service.setServiceKey("service");service.setImageRepository("registry/service");return Optional.of(service);});
    when(runner.execute(any(),any(),any(),any(),any(),anyList(),any())).thenReturn("ok\n");
    when(runner.imageVersion(any())).thenReturn("main-123456-b1");when(runner.imageTag(any(),any())).thenReturn("registry/service:main-123456-b1");
  }
  ReleaseTask build(long serviceId,String request){return executor.createBuild(serviceId,"main","",false,"admin",SHA,request);}
  ReleaseTask mobileBuild(long serviceId,String env,String request){return executor.createBuild(serviceId,"main","",false,"admin",SHA,request,env);}
  private String mobileConfig(String extra){return "{\"mobileBuild\":{\"flutterVersion\":\"3.35.4\",\"environments\":{\"test\":{\"enabled\":true,\"applicationId\":\"com.example.app.pre\",\"expectedCertificateSha256\":\""+"a".repeat(64)+"\",\"signingProfile\":\"test-profile\""+extra+"},\"prod\":{\"enabled\":true,\"applicationId\":\"com.example.app\",\"expectedCertificateSha256\":\""+"b".repeat(64)+"\",\"signingProfile\":\"prod-profile\""+extra+"}}}}";}
  String request(){return UUID.randomUUID().toString();}

  private ReleaseTask queuedDeployment(String environment){
    ServiceEnvironmentRepository configs=(ServiceEnvironmentRepository)ReflectionTestUtils.getField(executor,"environments");
    DeploymentServerRepository nodes=(DeploymentServerRepository)ReflectionTestUtils.getField(executor,"servers");
    ServiceEnvironment config=new ServiceEnvironment();config.setServiceId(1L);config.setEnvironmentName(environment);config.setServerIds(List.of(1L));
    when(configs.findByServiceIdAndEnvironmentName(1L,environment)).thenReturn(Optional.of(config));
    when(nodes.findById(1L)).thenReturn(Optional.of(new DeploymentServer()));
    ReleaseTask task=new ReleaseTask(1L,TaskKind.DEPLOY,null,environment,"main",null,false);
    task.setCommitId(SHA);task.setCreatedBy("admin");task.setTargetServerIds(List.of(1L));tasks.save(task);
    for(StepType type:List.of(StepType.SSH_CONNECT,StepType.START_SERVICE,StepType.HEALTH_CHECK))steps.save(new ReleaseStep(task.getId(),type));
    ReflectionTestUtils.invokeMethod(executor,"enqueue",task);return task;
  }

  @Test void runningCancellationWaitsForSshCleanupKeepsSerialSlotAndNeverNotifiesOrMerges()throws Exception{
    ReleaseTask deploy=queuedDeployment("prod");
    CountDownLatch entered=new CountDownLatch(1),cleaning=new CountDownLatch(1),finishCleanup=new CountDownLatch(1),runtimeEntered=new CountDownLatch(1);
    AtomicBoolean cleanupInterruptedAgain=new AtomicBoolean(),nextTaskInterrupted=new AtomicBoolean();
    when(runner.execute(any(),any(),any(),any(),any(),anyList(),any())).thenAnswer(call->{
      ReleaseTask task=call.getArgument(2);
      if(task==deploy&&call.getArgument(0)==StepType.START_SERVICE){
        entered.countDown();
        try{new CountDownLatch(1).await(10,TimeUnit.SECONDS);throw new AssertionError("Expected cancellation interrupt");}
        catch(InterruptedException expected){
          cleaning.countDown();
          try{assertThat(finishCleanup.await(5,TimeUnit.SECONDS)).isTrue();}
          catch(InterruptedException repeated){cleanupInterruptedAgain.set(true);throw repeated;}
          Thread.currentThread().interrupt();throw expected;
        }
      }
      if(task.getTaskKind()==TaskKind.BUILD)nextTaskInterrupted.set(nextTaskInterrupted.get()||Thread.currentThread().isInterrupted());
      return "ok\n";
    });
    ReleaseTask next=build(2L,request());ExecutorService pool=Executors.newFixedThreadPool(2);
    try{
      Future<?> worker=pool.submit(()->executor.execute(new ReleaseExecutor.ReleaseQueued(deploy.getId())));
      assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
      Future<?> runtime=pool.submit(()->executor.executeRuntimeOperation("other runtime operation",runtimeEntered::countDown));
      executor.cancelDeployment(deploy.getId());assertThat(cleaning.await(5,TimeUnit.SECONDS)).isTrue();executor.cancelDeployment(deploy.getId());
      assertThat(deploy.getStatus()).isEqualTo(TaskStatus.RUNNING);assertThat(executor.queue().runningTaskId()).isEqualTo(deploy.getId());
      assertThat(next.getStatus()).isEqualTo(TaskStatus.PENDING);assertThat(runtimeEntered.getCount()).isEqualTo(1);assertThat(worker.isDone()).isFalse();
      finishCleanup.countDown();worker.get(10,TimeUnit.SECONDS);runtime.get(10,TimeUnit.SECONDS);
      assertThat(deploy.getStatus()).isEqualTo(TaskStatus.CANCELLED);assertThat(deploy.getErrorMessage()).contains("远端状态需核对","不会自动回滚");
      assertThat(deploy.getMergeEnqueuePending()).isFalse();assertThat(next.getStatus()).isEqualTo(TaskStatus.SUCCESS);
      assertThat(cleanupInterruptedAgain).isFalse();assertThat(nextTaskInterrupted).isFalse();
      assertThat(stepRows.get(deploy.getId())).extracting(ReleaseStep::getStatus).containsExactly(StepStatus.SUCCESS,StepStatus.SKIPPED,StepStatus.SKIPPED);
      verify(runner,never()).execute(eq(StepType.HEALTH_CHECK),any(),same(deploy),any(),any(),anyList(),any());
      verify(publisher,never()).publishEvent(any(WecomNotificationService.ReleaseFinished.class));verifyNoInteractions(productionMerge);
      executor.cancelDeployment(deploy.getId()); // A late repeat cannot interrupt the following task.
    }finally{finishCleanup.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
  }

  @Test void swallowedCommandInterruptCannotWriteSuccessOrContinueToNextStep()throws Exception{
    ReleaseTask deploy=queuedDeployment("prod");CountDownLatch entered=new CountDownLatch(1);
    when(runner.execute(eq(StepType.START_SERVICE),any(),same(deploy),any(),any(),anyList(),any())).thenAnswer(call->{
      entered.countDown();try{new CountDownLatch(1).await(10,TimeUnit.SECONDS);}catch(InterruptedException swallowed){}
      return "remote command returned success after cancellation";
    });
    ExecutorService pool=Executors.newSingleThreadExecutor();
    try{
      Future<?> worker=pool.submit(()->executor.execute(new ReleaseExecutor.ReleaseQueued(deploy.getId())));
      assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();executor.cancelDeployment(deploy.getId());worker.get(10,TimeUnit.SECONDS);
      assertThat(deploy.getStatus()).isEqualTo(TaskStatus.CANCELLED);assertThat(stepRows.get(deploy.getId()).get(1).getStatus()).isEqualTo(StepStatus.SKIPPED);
      verify(runner,never()).execute(eq(StepType.HEALTH_CHECK),any(),same(deploy),any(),any(),anyList(),any());
      verify(publisher,never()).publishEvent(any(WecomNotificationService.ReleaseFinished.class));verifyNoInteractions(productionMerge);
    }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
  }

  @Test void successfulCommitWinsBeforeLateCancellationAndIsNeverOverwritten()throws Exception{
    ReleaseTask deploy=queuedDeployment("prod");CountDownLatch committing=new CountDownLatch(1),finishCommit=new CountDownLatch(1),cancelCalled=new CountDownLatch(1);
    doAnswer(call->{
      if(deploy.getStatus()==TaskStatus.SUCCESS){committing.countDown();assertThat(finishCommit.await(5,TimeUnit.SECONDS)).isTrue();}
      return null;
    }).when(tx).commit(any());
    ExecutorService pool=Executors.newFixedThreadPool(2);
    try{
      Future<?> worker=pool.submit(()->executor.execute(new ReleaseExecutor.ReleaseQueued(deploy.getId())));
      assertThat(committing.await(5,TimeUnit.SECONDS)).isTrue();
      Future<?> cancel=pool.submit(()->{cancelCalled.countDown();assertThatThrownBy(()->executor.cancelDeployment(deploy.getId())).isInstanceOf(IllegalArgumentException.class);});
      assertThat(cancelCalled.await(5,TimeUnit.SECONDS)).isTrue();assertThat(cancel.isDone()).isFalse();
      finishCommit.countDown();worker.get(10,TimeUnit.SECONDS);cancel.get(10,TimeUnit.SECONDS);
      assertThat(deploy.getStatus()).isEqualTo(TaskStatus.SUCCESS);verify(productionMerge).ensureForSuccessfulDeployment(deploy);
      verify(publisher).publishEvent(any(WecomNotificationService.ReleaseFinished.class));
    }finally{finishCommit.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
  }

  @Test void mobileDeploymentCannotBeCancelledThroughServerExecutionPath(){
    ReleaseTask mobile=new ReleaseTask(1L,TaskKind.DEPLOY,1L,"prod","main",null,false);mobile.setDeliveryType("MOBILE");tasks.save(mobile);
    assertThatThrownBy(()->executor.cancelDeployment(mobile.getId())).hasMessageContaining("App 分发");assertThat(mobile.getStatus()).isEqualTo(TaskStatus.PENDING);
  }

  @Test void frontendCloudOnlyReplacesCompilationAndPreservesBaselineImagesAndHistory()throws Exception{
    AppService service=new AppService();ReflectionTestUtils.setField(service,"id",35L);service.setName("Frontend");service.setServiceKey("example-ui");service.setServiceType(ServiceType.FRONTEND);service.setGitRepo("git@github.com:example-org/example-ui.git");service.setBuildCommand("npm ci && npm run build");service.setJarPath("dist");service.setImageRepository("registry/frontend");
    when(services.findById(35L)).thenReturn(Optional.of(service));
    GitHubFrontendBuildService cloud=mock(GitHubFrontendBuildService.class);executor.setFrontendCloud(cloud);when(cloud.engineForNewTask(service)).thenReturn("GITHUB");when(runner.sourcePath(any())).thenReturn(java.nio.file.Path.of("source"));when(cloud.build(any(),any(),any())).thenReturn("verified output");
    ReleaseTask task=build(35L,request());assertThat(task.getFrontendBuildEngine()).isEqualTo("GITHUB");verify(cloud).prepare(task,service);
    executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    verify(branchPolicy).check(same(service),same(task),any());verify(cloud).build(same(task),any(),any());
    verify(runner,never()).execute(eq(StepType.BUILD_JAR),any(),same(task),any(),any(),anyList(),any());
    verify(runner).execute(eq(StepType.BUILD_IMAGE),same(service),same(task),any(),any(),anyList(),any());verify(runner).execute(eq(StepType.PUSH_IMAGE),same(service),same(task),any(),any(),anyList(),any());
    when(cloud.engineForNewTask(service)).thenReturn("LOCAL");ReleaseTask local=build(35L,request());executor.execute(new ReleaseExecutor.ReleaseQueued(local.getId()));
    assertThat(local.getStatus()).isEqualTo(TaskStatus.SUCCESS);verify(runner).execute(eq(StepType.BUILD_JAR),same(service),same(local),any(),any(),anyList(),any());
    assertThat(task.getFrontendBuildEngine()).isEqualTo("GITHUB");
  }

  @Test void masterPolicyBlocksBeforeAnyBuildCommandAndRunsAgainOnRetry()throws Exception{
    ReleaseTask task=build(1L,request());
    doThrow(new IllegalStateException("当前版本未包含最新 master")).when(branchPolicy).check(any(),eq(task),any());
    executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));
    assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);assertThat(task.getErrorMessage()).contains("master");
    verify(runner,never()).execute(any(),any(),any(),any(),any(),anyList(),any());
    doReturn(null).when(branchPolicy).check(any(),eq(task),any());executor.retryBuild(task.getId(),request(),"admin");
    executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    verify(branchPolicy,times(2)).check(any(),eq(task),any());
  }

  @Test void successfulGitAndBuildStepsCannotBypassNewMasterCheckOnPushRetry()throws Exception{
    ReleaseTask task=build(1L,request());
    doThrow(new IllegalStateException("push failed")).when(runner).execute(eq(StepType.PUSH_IMAGE),any(),any(),any(),any(),anyList(),any());
    executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));
    assertThat(stepRows.get(task.getId()).getFirst().getStatus()).isEqualTo(StepStatus.SUCCESS);
    doThrow(new IllegalStateException("master 已更新")).when(branchPolicy).check(any(),eq(task),any());
    executor.retryBuild(task.getId(),request(),"admin");executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));
    assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);assertThat(task.getErrorMessage()).contains("master 已更新");
    verify(runner,times(1)).execute(eq(StepType.PUSH_IMAGE),any(),any(),any(),any(),anyList(),any());
  }

  @Test void pendingMobileConfirmationSurvivesRestartAndIsNotAddedToServerQueue(){
    ReleaseTask task=new ReleaseTask(1L,TaskKind.DEPLOY,1L,"prod","master",null,false);task.setDeliveryType("MOBILE");task.setStatus(TaskStatus.WAITING_CONFIRMATION);tasks.save(task);
    executor.recover();assertThat(task.getStatus()).isEqualTo(TaskStatus.WAITING_CONFIRMATION);assertThat(executor.queue().pendingCount()).isZero();verifyNoInteractions(runner,productionMerge);
  }

  @Test void flutterUsesDedicatedStepsAndNeverCallsDockerBuildPushOrImageMaintenance()throws Exception{
    AppService app=new AppService();ReflectionTestUtils.setField(app,"id",1L);app.setName("app");app.setServiceKey("app");app.setServiceType(ServiceType.FLUTTER_ANDROID);
    app.setConfigJson(mobileConfig(""));
    when(services.findById(1L)).thenReturn(Optional.of(app));ReleaseTask task=mobileBuild(1L,"prod",request());
    assertThat(stepRows.get(task.getId())).extracting(ReleaseStep::getStepType).containsExactly(StepType.GIT_PULL,StepType.BUILD_AAB,StepType.VALIDATE_AAB,StepType.ARCHIVE_ARTIFACT);
    when(runner.sourcePath(task)).thenReturn(java.nio.file.Path.of("test-source"));
    executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    verify(branchPolicy).check(eq(app),eq(task),any());
    for(StepType type:List.of(StepType.BUILD_AAB,StepType.VALIDATE_AAB,StepType.ARCHIVE_ARTIFACT))verify(flutter).execute(eq(type),eq(app),eq(task),any(),any());
    verify(flutter).persistArtifact(eq(app),eq(task),anyString());verify(flutter).cleanupAfterSuccess(task.getId());verifyNoInteractions(storage);
    verify(runner,never()).execute(eq(StepType.BUILD_IMAGE),any(),any(),any(),any(),anyList(),any());verify(runner,never()).execute(eq(StepType.PUSH_IMAGE),any(),any(),any(),any(),anyList(),any());
    verify(runner,never()).imageTag(any(),any());
  }
  @Test void apkRetryKeepsTaskSnapshotAndPipelineAfterServiceChanges()throws Exception{
    AppService app=new AppService();ReflectionTestUtils.setField(app,"id",1L);app.setServiceType(ServiceType.FLUTTER_ANDROID);app.setConfigJson(mobileConfig(",\"outputFormat\":\"APK\",\"flavor\":\"production\""));
    when(services.findById(1L)).thenReturn(Optional.of(app));ReleaseTask task=mobileBuild(1L,"prod",request());String snapshot=task.getMobileBuildConfigSnapshot();
    when(runner.sourcePath(task)).thenReturn(java.nio.file.Path.of("source"));
    doThrow(new IllegalStateException("validation interrupted")).when(flutter).execute(eq(StepType.VALIDATE_APK),any(),eq(task),any(),any());
    executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
    app.setConfigJson("{}");doReturn("valid").when(flutter).execute(eq(StepType.VALIDATE_APK),any(),eq(task),any(),any());
    executor.retryBuild(task.getId(),request(),"admin");executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));
    assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(task.getMobileBuildConfigSnapshot()).isEqualTo(snapshot);
    assertThat(stepRows.get(task.getId())).extracting(ReleaseStep::getStepType).containsExactly(StepType.GIT_PULL,StepType.BUILD_APK,StepType.VALIDATE_APK,StepType.ARCHIVE_ARTIFACT);
    verify(flutter,times(1)).execute(eq(StepType.BUILD_APK),any(),eq(task),any(),any());verify(flutter,times(2)).execute(eq(StepType.VALIDATE_APK),any(),eq(task),any(),any());
    assertThat(FlutterBuildConfiguration.fromTask(task).flavor()).isEqualTo("production");verifyNoInteractions(storage);
  }
  @Test void oldAppWithoutSnapshotCannotRetryOrResumeUsingNewServiceSettings(){
    AppService app=new AppService();app.setServiceType(ServiceType.FLUTTER_ANDROID);when(services.findById(1L)).thenReturn(Optional.of(app));
    ReleaseTask task=new ReleaseTask(1L,TaskKind.BUILD,null,"build","master",null,false);task.setDeliveryType("MOBILE");task.setCommitId(SHA);task.setStatus(TaskStatus.FAILED);tasks.save(task);
    assertThatThrownBy(()->executor.retryBuild(task.getId(),request(),"admin")).hasMessageContaining("没有锁定构建配置");
    task.setStatus(TaskStatus.RUNNING);executor.recover();assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);assertThat(task.getErrorMessage()).contains("没有锁定构建配置");verifyNoInteractions(runner,flutter);
  }
  @Test void cloudChoiceIsLockedAndDoesNotChangeServerBuildsOrLegacyLocalTasks()throws Exception{
    var cloud=mock(GitHubMobileBuildService.class);executor.setCloudBuild(cloud);when(cloud.engineForNewTask(any())).thenReturn("GITHUB");
    AppService app=new AppService();ReflectionTestUtils.setField(app,"id",1L);app.setServiceType(ServiceType.FLUTTER_ANDROID);app.setConfigJson(mobileConfig(",\"outputFormat\":\"APK\""));when(services.findById(1L)).thenReturn(Optional.of(app));
    String id=request();ReleaseTask mobile=mobileBuild(1L,"test",id);assertThat(mobile.getMobileBuildEngine()).isEqualTo("GITHUB");verify(cloud).prepare(mobile,app);
    when(cloud.engineForNewTask(any())).thenReturn("LOCAL");assertThat(mobileBuild(1L,"test",id)).isSameAs(mobile);assertThat(mobile.getMobileBuildEngine()).isEqualTo("GITHUB");
    ReleaseTask server=build(2L,request());assertThat(server.getDeliveryType()).isEqualTo("SERVER");verify(cloud,never()).prepare(eq(server),any());
    mobile.setStatus(TaskStatus.RUNNING);when(cloud.continuesOriginalRun(mobile)).thenReturn(true);executor.recover();executor.execute(new ReleaseExecutor.ReleaseQueued(mobile.getId()));
    verify(branchPolicy,never()).check(eq(app),eq(mobile),any());assertThat(mobile.getStatus()).isEqualTo(TaskStatus.SUCCESS);verify(flutter).persistArtifact(eq(app),eq(mobile),anyString());
    verify(cloud,times(1)).prepare(any(),any());
  }
  @Test void mobileBuildRequiresAnExplicitConfiguredEnvironmentBeforeAnyTaskIsPersisted(){
    AppService app=new AppService();ReflectionTestUtils.setField(app,"id",1L);app.setServiceType(ServiceType.FLUTTER_ANDROID);app.setConfigJson(mobileConfig(""));when(services.findById(1L)).thenReturn(Optional.of(app));
    assertThatThrownBy(()->build(1L,request())).hasMessageContaining("选择 App 构建环境");
    assertThatThrownBy(()->mobileBuild(1L,"dev",request())).isInstanceOf(IllegalArgumentException.class);
    assertThat(taskRows).isEmpty();assertThat(stepRows).isEmpty();verifyNoInteractions(publisher);
  }
  @Test void mobileBuildIdempotencyIncludesEnvironmentAndSnapshotRemainsLocked(){
    AppService app=new AppService();ReflectionTestUtils.setField(app,"id",1L);app.setServiceType(ServiceType.FLUTTER_ANDROID);app.setConfigJson(mobileConfig(""));when(services.findById(1L)).thenReturn(Optional.of(app));
    String id=request();ReleaseTask task=mobileBuild(1L,"test",id);String snapshot=task.getMobileBuildConfigSnapshot();
    assertThat(task.getEnvironmentName()).isEqualTo("test");assertThat(FlutterBuildConfiguration.fromTask(task).environmentName()).isEqualTo("test");
    app.setConfigJson("{}");app.setEnabled(false);
    assertThat(mobileBuild(1L,"test",id)).isSameAs(task);assertThat(task.getMobileBuildConfigSnapshot()).isEqualTo(snapshot);
    assertThatThrownBy(()->mobileBuild(1L,"prod",id)).hasMessageContaining("其他配置");assertThat(taskRows).hasSize(1);
  }
  @Test void serverBuildRemainsEnvironmentIndependentWithTheOriginalIdempotencyContract(){
    String id=request();ReleaseTask first=build(1L,id);
    assertThat(executor.createBuild(1L,"main","",false,"admin",SHA,id,"prod")).isSameAs(first);
    assertThat(first.getEnvironmentName()).isEqualTo("build");assertThat(first.getDeliveryType()).isEqualTo("SERVER");assertThat(first.getMobileBuildConfigSnapshot()).isNull();
  }

  @Test void notificationFailureCannotTurnCommittedDeploymentIntoFailure(){
    ServiceEnvironmentRepository configs=(ServiceEnvironmentRepository)ReflectionTestUtils.getField(executor,"environments");
    DeploymentServerRepository nodes=(DeploymentServerRepository)ReflectionTestUtils.getField(executor,"servers");
    ServiceEnvironment config=new ServiceEnvironment();config.setServiceId(1L);config.setEnvironmentName("test");config.setServerIds(List.of(1L));
    when(configs.findByServiceIdAndEnvironmentName(1L,"test")).thenReturn(Optional.of(config));
    when(nodes.findById(1L)).thenReturn(Optional.of(new DeploymentServer()));
    ReleaseTask task=tasks.save(new ReleaseTask(1L,TaskKind.DEPLOY,null,"test","main",null,false));
    doThrow(new IllegalStateException("notification database unavailable")).when(publisher).publishEvent(any(WecomNotificationService.ReleaseFinished.class));
    ReflectionTestUtils.invokeMethod(executor,"runTask",task);
    assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    var order=inOrder(tx,publisher);order.verify(tx).commit(any());order.verify(publisher).publishEvent(any(WecomNotificationService.ReleaseFinished.class));
  }

  @Test void interruptedDeploymentRecordsFailureBeforeNotificationAndSurvivesNotifierException(){
    ReleaseTask task=tasks.save(new ReleaseTask(1L,TaskKind.DEPLOY,null,"prod","main",null,false));task.setStatus(TaskStatus.RUNNING);task.setStartedAt(LocalDateTime.now());
    doThrow(new IllegalStateException("notification unavailable")).when(publisher).publishEvent(any(WecomNotificationService.ReleaseFinished.class));
    executor.recover();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);assertThat(task.getErrorMessage()).contains("重启");
    verify(publisher).publishEvent(any(WecomNotificationService.ReleaseFinished.class));
  }

  @Test void concurrentCreationWithSameRequestCreatesOneTask()throws Exception{
    String request=request();try(ExecutorService pool=Executors.newFixedThreadPool(8)){
      List<Future<ReleaseTask>> created=new ArrayList<>();for(int n=0;n<20;n++)created.add(pool.submit(()->build(1L,request)));
      Set<Long> result=new HashSet<>();for(Future<ReleaseTask> item:created)result.add(item.get().getId());
      assertThat(result).hasSize(1);assertThat(taskRows).hasSize(1);assertThat(executor.queue().pendingCount()).isEqualTo(1);
    }
    assertThatThrownBy(()->executor.createBuild(2L,"main","",false,"admin",SHA,request)).hasMessageContaining("其他配置");
    verify(publisher,times(1)).publishEvent(any(ReleaseExecutor.ReleaseQueued.class));
  }

  @Test void fullQueueCreatesNoOrphanAndCancelledSlotCanBeReused(){
    executor.queueCapacity=1;ReleaseTask first=build(1L,request());
    assertThatThrownBy(()->build(2L,request())).hasMessageContaining("队列已满");assertThat(taskRows).hasSize(1);
    executor.cancelBuild(first.getId());assertThat(first.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    ReleaseTask next=build(2L,request());assertThat(executor.queue().pendingTasks()).extracting(ReleaseExecutor.QueuedTask::taskId).containsExactly(next.getId());
    executor.execute(new ReleaseExecutor.ReleaseQueued(first.getId()));
    assertThat(next.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(first.getStatus()).isEqualTo(TaskStatus.CANCELLED);
  }

  @Test void differentServicesAndDuplicateEventsRunStrictlyOnceAndSerially()throws Exception{
    AtomicInteger active=new AtomicInteger(),maxActive=new AtomicInteger(),calls=new AtomicInteger();
    CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
    when(runner.execute(any(),any(),any(),any(),any(),anyList(),any())).thenAnswer(call->{
      int current=active.incrementAndGet();maxActive.accumulateAndGet(current,Math::max);calls.incrementAndGet();started.countDown();
      assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();active.decrementAndGet();return "ok";
    });
    ReleaseTask first=build(1L,request());
    try(ExecutorService pool=Executors.newFixedThreadPool(2)){
      Future<?> worker=pool.submit(()->executor.execute(new ReleaseExecutor.ReleaseQueued(first.getId())));
      assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();ReleaseTask second=build(2L,request());
      assertThat(executor.queue().runningTaskId()).isEqualTo(first.getId());assertThat(executor.queue().pendingTasks().getFirst().position()).isEqualTo(1);
      assertThatThrownBy(()->executor.cancelBuild(first.getId())).hasMessageContaining("只能取消");
      Future<?> duplicate=pool.submit(()->executor.execute(new ReleaseExecutor.ReleaseQueued(first.getId())));
      release.countDown();worker.get(10,TimeUnit.SECONDS);duplicate.get(10,TimeUnit.SECONDS);
      assertThat(first.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(second.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    }finally{release.countDown();}
    assertThat(maxActive).hasValue(1);assertThat(calls).hasValue(8);assertThat(executor.queue().pendingCount()).isZero();
  }

  @Test void retryIsFailureOnlyAndRepeatedRequestDoesNotRunAgainAfterAnotherFailure()throws Exception{
    when(runner.execute(any(),any(),any(),any(),any(),anyList(),any())).thenThrow(new IllegalStateException("build failed"));
    ReleaseTask task=build(1L,request());
    assertThatThrownBy(()->executor.retryBuild(task.getId(),request(),"admin")).hasMessageContaining("仅失败任务");
    executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
    String retry=request();executor.retryBuild(task.getId(),retry,"admin");executor.retryBuild(task.getId(),retry,"admin");
    assertThat(executor.queue().pendingCount()).isEqualTo(1);executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));
    executor.retryBuild(task.getId(),retry,"admin");assertThat(executor.queue().pendingCount()).isZero();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
    executor.retryBuild(task.getId(),request(),"admin");assertThat(executor.queue().pendingCount()).isEqualTo(1);
    executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));
    executor.retryBuild(task.getId(),retry,"admin");assertThat(executor.queue().pendingCount()).isZero();
  }

  @Test void buildEndpointsCannotRetryOrCancelDeployment(){
    ReleaseTask deploy=new ReleaseTask(1L,TaskKind.DEPLOY,2L,"prod","main","",false);deploy.setStatus(TaskStatus.FAILED);tasks.save(deploy);
    assertThatThrownBy(()->executor.retryBuild(deploy.getId(),request(),"admin")).hasMessageContaining("不是打包任务");
    assertThatThrownBy(()->executor.cancelBuild(deploy.getId())).hasMessageContaining("不是打包任务");assertThat(executor.queue().pendingCount()).isZero();
  }

  @Test void recoveryOrdersPendingAndDoesNotReplayInterruptedDeployment(){
    ReleaseTask newer=new ReleaseTask(1L,TaskKind.BUILD,null,"build","main","",false);newer.setCommitId(SHA);tasks.save(newer);
    ReleaseTask older=new ReleaseTask(2L,TaskKind.BUILD,null,"build","main","",false);older.setCommitId(SHA);tasks.save(older);ReflectionTestUtils.setField(older,"createdAt",LocalDateTime.now().minusHours(1));
    ReleaseTask production=new ReleaseTask(1L,TaskKind.DEPLOY,2L,"prod","main","",false);production.setStatus(TaskStatus.RUNNING);tasks.save(production);
    executor.recover();
    assertThat(executor.queue().pendingTasks()).extracting(ReleaseExecutor.QueuedTask::taskId).containsExactly(older.getId(),newer.getId());
    assertThat(production.getStatus()).isEqualTo(TaskStatus.FAILED);assertThat(production.getErrorMessage()).contains("核对目标服务器");
  }

  @Test void preparationFailureMarksFailedAndReleasesQueue(){
    ReleaseTask first=build(1L,request()),second=build(2L,request());when(services.findById(1L)).thenReturn(Optional.empty());
    executor.execute(new ReleaseExecutor.ReleaseQueued(first.getId()));
    assertThat(first.getStatus()).isEqualTo(TaskStatus.FAILED);assertThat(second.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(executor.queue().runningTaskId()).isNull();
  }

  @Test void storageMaintenanceRunsAfterArtifactAndSuccessTransactionCommit() {
    ReleaseTask task=build(1L,request());clearInvocations(tx,tasks);
    doAnswer(call->{assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);return null;}).when(storage).afterSuccessfulBuild(task.getId());
    executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));
    var order=inOrder(artifacts,tasks,tx,storage);
    order.verify(artifacts).save(any(Artifact.class));order.verify(tasks).save(task);order.verify(tx).commit(any());
    order.verify(storage).afterSuccessfulBuild(task.getId());
  }

  @Test void maintenanceExceptionCannotFailSuccessfulBuildOrBlockFollowingBuild() {
    ReleaseTask first=build(1L,request()),second=build(2L,request());
    doThrow(new IllegalStateException("maintenance disk full")).when(storage).afterSuccessfulBuild(first.getId());
    executor.execute(new ReleaseExecutor.ReleaseQueued(first.getId()));
    assertThat(first.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(second.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    verify(storage).afterSuccessfulBuild(second.getId());assertThat(executor.queue().runningTaskId()).isNull();
  }

  @Test void failedPushPreservesOutputsUntilSuccessfulResumeAndDoesNotRebuildCompletedSteps()throws Exception {
    AtomicBoolean failPush=new AtomicBoolean(true);
    when(runner.execute(eq(StepType.PUSH_IMAGE),any(),any(),any(),any(),anyList(),any())).thenAnswer(call->{
      if(failPush.getAndSet(false))throw new IllegalStateException("push timeout");return "pushed";
    });
    ReleaseTask task=build(1L,request());executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));
    assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);verifyNoInteractions(storage);
    executor.retryBuild(task.getId(),request(),"admin");executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));
    assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);verify(storage).afterSuccessfulBuild(task.getId());
    verify(runner,times(1)).execute(eq(StepType.GIT_PULL),any(),any(),any(),any(),anyList(),any());
    verify(runner,times(1)).execute(eq(StepType.BUILD_IMAGE),any(),any(),any(),any(),anyList(),any());
    verify(runner,times(2)).execute(eq(StepType.PUSH_IMAGE),any(),any(),any(),any(),anyList(),any());
  }

  @Test void runtimeOperationAndBuildsCannotExecuteConcurrently()throws Exception {
    CountDownLatch buildStarted=new CountDownLatch(1),releaseBuild=new CountDownLatch(1),restartStarted=new CountDownLatch(1);
    AtomicBoolean building=new AtomicBoolean();
    when(runner.execute(any(),any(),any(),any(),any(),anyList(),any())).thenAnswer(call->{
      building.set(true);buildStarted.countDown();assertThat(releaseBuild.await(5,TimeUnit.SECONDS)).isTrue();building.set(false);return "ok";
    });
    ReleaseTask task=build(1L,request());
    try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
      Future<?> build=pool.submit(()->executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId())));
      assertThat(buildStarted.await(5,TimeUnit.SECONDS)).isTrue();
      Future<?> restart=pool.submit(()->executor.executeRuntimeOperation("restart node",()->{
        assertThat(building).isFalse();assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(executor.queue().runningRuntimeOperation()).isEqualTo("restart node");restartStarted.countDown();
      }));
      assertThat(restartStarted.await(100,TimeUnit.MILLISECONDS)).isFalse();
      releaseBuild.countDown();build.get(10,TimeUnit.SECONDS);restart.get(10,TimeUnit.SECONDS);
      assertThat(executor.queue().runningRuntimeOperation()).isNull();assertThat(executor.queue().pendingRuntimeOperations()).isZero();
    }finally{releaseBuild.countDown();}
    assertThat(taskRows).hasSize(1);
  }

  @Test void buildSubmittedDuringRestartWaitsUntilRestartFinishes()throws Exception {
    CountDownLatch restartStarted=new CountDownLatch(1),finishRestart=new CountDownLatch(1);
    try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
      Future<?> restart=pool.submit(()->executor.executeRuntimeOperation("restart",()->{
        restartStarted.countDown();try{assertThat(finishRestart.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}
      }));
      assertThat(restartStarted.await(5,TimeUnit.SECONDS)).isTrue();ReleaseTask task=build(1L,request());
      Future<?> build=pool.submit(()->executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId())));
      assertThat(executor.queue().runningRuntimeOperation()).isEqualTo("restart");
      assertThatThrownBy(()->build.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
      verify(runner,never()).execute(any(),any(),any(),any(),any(),anyList(),any());
      finishRestart.countDown();restart.get(5,TimeUnit.SECONDS);build.get(5,TimeUnit.SECONDS);
      assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    }finally{finishRestart.countDown();}
  }

  @Test void cancelledRuntimeWaitLeavesQueueImmediatelyWithoutReleasingAnotherOperationsSlot()throws Exception{
    CountDownLatch holderStarted=new CountDownLatch(1),releaseHolder=new CountDownLatch(1);
    AtomicBoolean actionRan=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();
    Thread holder=Thread.ofVirtual().start(()->executor.executeRuntimeOperation("holder",()->{holderStarted.countDown();try{assertThat(releaseHolder.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException error){throw new RuntimeException(error);}}));
    assertThat(holderStarted.await(5,TimeUnit.SECONDS)).isTrue();
    Thread waiting=Thread.ofVirtual().start(()->{try{executor.executeCancellableRuntimeOperation("cancelled waiting",()->actionRan.set(true));}catch(Throwable error){failure.set(error);}});
    try{
      long deadline=System.nanoTime()+3_000_000_000L;while(executor.queue().pendingRuntimeOperations()!=1&&System.nanoTime()<deadline)Thread.sleep(10);
      assertThat(executor.queue().pendingRuntimeOperations()).isEqualTo(1);waiting.interrupt();waiting.join(2000);
      assertThat(waiting.isAlive()).isFalse();assertThat(failure.get()).isInstanceOf(InterruptedException.class);assertThat(actionRan).isFalse();
      assertThat(executor.queue().pendingRuntimeOperations()).isZero();assertThat(executor.queue().runningRuntimeOperation()).isEqualTo("holder");assertThat(releaseHolder.getCount()).isEqualTo(1);
    }finally{releaseHolder.countDown();waiting.interrupt();waiting.join(2000);holder.join(3000);}
    assertThat(executor.queue().pendingRuntimeOperations()).isZero();assertThat(executor.queue().runningRuntimeOperation()).isNull();
    executor.executeCancellableRuntimeOperation("next",()->actionRan.set(true));assertThat(actionRan).isTrue();assertThat(executor.queue().pendingRuntimeOperations()).isZero();
  }

  @Test void cancelledRuntimeEntryAndActionFailureDoNotLeakQueueCountersOrSerialLock()throws Exception{
    AtomicBoolean actionRan=new AtomicBoolean();Thread.currentThread().interrupt();
    try{assertThatThrownBy(()->executor.executeCancellableRuntimeOperation("already cancelled",()->actionRan.set(true))).isInstanceOf(InterruptedException.class);}
    finally{Thread.interrupted();}
    assertThat(actionRan).isFalse();assertThat(executor.queue().pendingRuntimeOperations()).isZero();
    assertThatThrownBy(()->executor.executeCancellableRuntimeOperation("failing",()->{throw new IllegalStateException("fixture");})).hasMessage("fixture");
    assertThat(executor.queue().pendingRuntimeOperations()).isZero();assertThat(executor.queue().runningRuntimeOperation()).isNull();
    executor.executeCancellableRuntimeOperation("next",()->actionRan.set(true));assertThat(actionRan).isTrue();
  }

  @Test void simulationAndMissingCommitAreRejectedBeforeSaving(){
    assertThatThrownBy(()->executor.createBuild(1L,"main","",true,"admin",SHA,request())).hasMessageContaining("模拟");
    assertThatThrownBy(()->executor.createBuild(1L,"main","",false,"admin",null,request())).hasMessageContaining("Commit");assertThat(taskRows).isEmpty();
  }
  @Test void automaticBuildReservesOnceAndKeepsVersionAcrossRequestReplayAndFailedRetry(){
    MobileVersionService versions=mock(MobileVersionService.class);ReflectionTestUtils.setField(executor,"mobileVersions",versions);
    AppService app=new AppService();ReflectionTestUtils.setField(app,"id",1L);app.setServiceType(ServiceType.FLUTTER_ANDROID);app.setConfigJson(mobileConfig("").replace("\"mobileBuild\":{","\"mobileBuild\":{\"versionPolicy\":\"AUTO\","));when(services.findById(1L)).thenReturn(Optional.of(app));
    var assigned=FlutterBuildConfiguration.forEnvironment(app,"test").withVersion("0.1.1-pre",22);
    var allocation=new MobileVersionAllocation(1L,assigned.applicationId(),"test","0.1.1-pre","0.1.1",22,null,"main",SHA,"admin");
    when(versions.reserve(same(app),any(),eq("main"),eq(SHA),isNull(),eq("admin"))).thenReturn(new MobileVersionService.Reservation(allocation,assigned));
    String requestId=request();ReleaseTask first=mobileBuild(1L,"test",requestId);String snapshot=first.getMobileBuildConfigSnapshot();
    assertThat(mobileBuild(1L,"test",requestId)).isSameAs(first);assertThat(FlutterBuildConfiguration.fromTask(first).versionCode()).isEqualTo(22);assertThat(FlutterBuildConfiguration.fromTask(first).versionName()).isEqualTo("0.1.1-pre");
    first.setStatus(TaskStatus.FAILED);stepRows.get(first.getId()).getFirst().setStatus(StepStatus.SUCCESS);executor.retryBuild(first.getId(),request(),"admin");
    assertThat(first.getMobileBuildConfigSnapshot()).isEqualTo(snapshot);assertThat(stepRows.get(first.getId()).getFirst().getStatus()).isEqualTo(StepStatus.SUCCESS);
    verify(versions,times(1)).reserve(any(),any(),anyString(),anyString(),nullable(Long.class),anyString());verify(versions,times(1)).bind(any(),eq(first.getId()));
  }
  @Test void productionSourceParticipatesInIdempotencyAndInvalidManualSourceCannotCreateTask(){
    MobileVersionService versions=mock(MobileVersionService.class);ReflectionTestUtils.setField(executor,"mobileVersions",versions);
    AppService app=new AppService();ReflectionTestUtils.setField(app,"id",1L);app.setServiceType(ServiceType.FLUTTER_ANDROID);app.setConfigJson(mobileConfig("").replace("\"mobileBuild\":{","\"mobileBuild\":{\"versionPolicy\":\"AUTO\","));when(services.findById(1L)).thenReturn(Optional.of(app));
    var assigned=FlutterBuildConfiguration.forEnvironment(app,"prod").withVersion("0.0.1",10);var allocation=new MobileVersionAllocation(1L,assigned.applicationId(),"prod","0.0.1","0.0.1",10,91L,"main",SHA,"admin");
    when(versions.reserve(same(app),any(),eq("main"),eq(SHA),eq(91L),eq("admin"))).thenReturn(new MobileVersionService.Reservation(allocation,assigned));
    String requestId=request();ReleaseTask first=executor.createBuild(1L,"main","",false,"admin",SHA,requestId,"prod",91L);
    assertThat(executor.createBuild(1L,"main","",false,"admin",SHA,requestId,"prod",91L)).isSameAs(first);assertThat(FlutterBuildConfiguration.taskPreBuildTaskId(first)).isEqualTo(91L);
    assertThatThrownBy(()->executor.createBuild(1L,"main","",false,"admin",SHA,requestId,"prod",92L)).hasMessageContaining("请求标识");
    app.setConfigJson(mobileConfig(""));assertThatThrownBy(()->executor.createBuild(1L,"main","",false,"admin",SHA,request(),"prod",91L)).hasMessageContaining("仅自动");
    verify(versions,times(1)).reserve(any(),any(),anyString(),anyString(),nullable(Long.class),anyString());assertThat(taskRows).hasSize(1);
  }

  @ParameterizedTest
  @CsvSource({"FRONTEND,构建 Node 前端", "BACKEND,构建 Spring Boot JAR"})
  void newBuildLogsUseServiceLabelsWhileDispatchKeepsStoredStepType(ServiceType serviceType,String buildLabel)throws Exception{
    AppService service=new AppService();ReflectionTestUtils.setField(service,"id",1L);
    service.setServiceType(serviceType);service.setBuildCommand(null);service.setJarPath(serviceType==ServiceType.FRONTEND?"dist":"target/*.jar");
    when(services.findById(1L)).thenReturn(Optional.of(service));
    ReleaseTask task=build(1L,request());executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));
    List<ReleaseStep> recorded=stepRows.get(task.getId());
    assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(recorded).extracting(ReleaseStep::getStepType).containsExactly(StepType.GIT_PULL,StepType.BUILD_JAR,StepType.BUILD_IMAGE,StepType.PUSH_IMAGE);
    assertThat(recorded).extracting(step->step.getLogText().lines().findFirst().orElse(""))
      .containsExactly("开始：拉取代码","开始："+buildLabel,"开始：构建 Docker 镜像","开始：推送镜像仓库");
    assertThat(recorded).allSatisfy(step->assertThat(step.getLogText()).doesNotContain("开始："+step.getStepType().name()));
    verify(runner).execute(eq(StepType.BUILD_JAR),same(service),same(task),isNull(),isNull(),eq(List.of()),any());
  }

  @Test void legacyImageIsRegeneratedOnceOnRetryWhileSourceBuildIsPreserved(){
    ReleaseTask task=legacyBuild(TaskStatus.FAILED);
    executor.retryBuild(task.getId(),request(),"admin");
    assertThat(stepRows.get(task.getId()).stream().map(ReleaseStep::getStatus)).containsExactly(StepStatus.SUCCESS,StepStatus.SUCCESS,StepStatus.WAITING,StepStatus.WAITING);
    assertThat(task.isImageTagUpgraded()).isTrue();
    executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));
    task.setStatus(TaskStatus.FAILED);stepRows.get(task.getId()).getLast().setStatus(StepStatus.FAILED);
    executor.retryBuild(task.getId(),request(),"admin");
    assertThat(stepRows.get(task.getId()).get(2).getStatus()).isEqualTo(StepStatus.SUCCESS);
  }

  @Test void recoveryUpgradesLegacyImageButLeavesSuccessfulHistoryUntouched(){
    ReleaseTask pending=legacyBuild(TaskStatus.PENDING),completed=legacyBuild(TaskStatus.SUCCESS);
    executor.recover();
    assertThat(stepRows.get(pending.getId()).get(2).getStatus()).isEqualTo(StepStatus.WAITING);
    assertThat(stepRows.get(completed.getId()).get(2).getStatus()).isEqualTo(StepStatus.SUCCESS);
    assertThat(completed.isImageTagUpgraded()).isFalse();
  }

  private ReleaseTask legacyBuild(TaskStatus status){
    ReleaseTask task=new ReleaseTask(1L,TaskKind.BUILD,null,"build","main","",false);task.setCommitId(SHA);task.setStatus(status);tasks.save(task);
    for(StepType type:List.of(StepType.GIT_PULL,StepType.BUILD_JAR,StepType.BUILD_IMAGE,StepType.PUSH_IMAGE)){
      ReleaseStep step=new ReleaseStep(task.getId(),type);step.setStatus(type==StepType.PUSH_IMAGE?StepStatus.FAILED:StepStatus.SUCCESS);steps.save(step);
    }
    return task;
  }
}
