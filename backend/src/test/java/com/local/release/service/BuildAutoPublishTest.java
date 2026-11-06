package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuildAutoPublishTest {
  @Test void fasterLaterBuildWaitsForEarlierTargetAndDeploysInSubmissionOrder(){
    ReleaseTask first=build(),second=build();
    second.setStatus(TaskStatus.SUCCESS);artifacts.save(new Artifact(1L,second.getId(),"v2",second.getBranch(),SHA,"registry/catalog:v2"));
    executor.recoverAutomaticPublishing();assertThat(deploys()).isEmpty();assertThat(planRows.get(second.getId()).getMessage()).contains("较早");
    first.setStatus(TaskStatus.SUCCESS);artifacts.save(new Artifact(1L,first.getId(),"v1",first.getBranch(),SHA,"registry/catalog:v1"));
    executor.recoverAutomaticPublishing();assertThat(deploys()).hasSize(2);
    assertThat(deploys()).extracting(ReleaseTask::getArtifactId).containsExactly(artifactRows.get(first.getId()).getId(),artifactRows.get(second.getId()).getId());
  }
  @Test void failedEarlierBuildUnblocksLaterSuccessfulAutoPublish(){
    ReleaseTask first=build(),second=build();second.setStatus(TaskStatus.SUCCESS);artifacts.save(new Artifact(1L,second.getId(),"v2",second.getBranch(),SHA,"registry/catalog:v2"));
    executor.recoverAutomaticPublishing();assertThat(deploys()).isEmpty();first.setStatus(TaskStatus.FAILED);executor.recoverAutomaticPublishing();assertThat(deploys()).hasSize(1);assertThat(deploys().getFirst().getArtifactId()).isEqualTo(artifactRows.get(second.getId()).getId());
  }
  static final String SHA="a".repeat(40);
  final Map<Long,ReleaseTask> taskRows=new LinkedHashMap<>();
  final Map<Long,List<ReleaseStep>> stepRows=new LinkedHashMap<>();
  final Map<Long,Artifact> artifactRows=new LinkedHashMap<>();
  final Map<Long,BuildAutoPublishPlan> planRows=new LinkedHashMap<>();
  final AtomicLong ids=new AtomicLong();
  ReleaseTaskRepository tasks=mock(ReleaseTaskRepository.class);
  ReleaseStepRepository steps=mock(ReleaseStepRepository.class);
  AppServiceRepository services=mock(AppServiceRepository.class);
  ArtifactRepository artifacts=mock(ArtifactRepository.class);
  ServiceEnvironmentRepository environments=mock(ServiceEnvironmentRepository.class);
  DeploymentServerRepository servers=mock(DeploymentServerRepository.class);
  BuildAutoPublishPlanRepository plans=mock(BuildAutoPublishPlanRepository.class);
  AdminUserRepository users=mock(AdminUserRepository.class);
  CommandRunner runner=mock(CommandRunner.class);
  ApplicationEventPublisher publisher=mock(ApplicationEventPublisher.class);
  PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
  AdminUser user=new AdminUser("yuan","hash");
  AppService service=new AppService();
  ServiceEnvironment environment=new ServiceEnvironment();
  DeploymentServer server=new DeploymentServer();
  BuildAutoPublishService auto;
  ReleaseExecutor executor;

  @BeforeEach void setup()throws Exception{
    user.setMenuPermissions(List.of("builds","release"));when(users.findByUsername("yuan")).thenReturn(Optional.of(user));
    ReflectionTestUtils.setField(service,"id",1L);service.setName("catalog");service.setServiceKey("catalog");service.setImageRepository("registry/catalog");
    when(services.findById(1L)).thenReturn(Optional.of(service));
    environment.setServiceId(1L);environment.setEnvironmentName("test");environment.setServerIds(List.of(9L));
    when(environments.findByServiceIdAndEnvironmentName(1L,"test")).thenReturn(Optional.of(environment));when(servers.findById(9L)).thenReturn(Optional.of(server));
    when(tx.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
    when(tasks.save(any())).thenAnswer(call->{ReleaseTask task=call.getArgument(0);if(task.getId()==null)ReflectionTestUtils.setField(task,"id",ids.incrementAndGet());taskRows.put(task.getId(),task);return task;});
    when(tasks.findById(anyLong())).thenAnswer(call->Optional.ofNullable(taskRows.get(call.getArgument(0))));
    when(tasks.findByRequestKey(anyString())).thenAnswer(call->taskRows.values().stream().filter(t->Objects.equals(t.getRequestKey(),call.getArgument(0))).findFirst());
    when(tasks.findByStatusIn(anyList())).thenAnswer(call->taskRows.values().stream().filter(t->((List<?>)call.getArgument(0)).contains(t.getStatus())).toList());
    when(steps.save(any())).thenAnswer(call->{ReleaseStep step=call.getArgument(0);List<ReleaseStep> rows=stepRows.computeIfAbsent(step.getReleaseTaskId(),id->new ArrayList<>());if(!rows.contains(step))rows.add(step);return step;});
    when(steps.findByReleaseTaskIdOrderById(anyLong())).thenAnswer(call->stepRows.getOrDefault(call.getArgument(0),List.of()));
    when(artifacts.save(any())).thenAnswer(call->{Artifact artifact=call.getArgument(0);ReflectionTestUtils.setField(artifact,"id",1000+artifact.getBuildTaskId());artifactRows.put(artifact.getBuildTaskId(),artifact);return artifact;});
    when(artifacts.existsByBuildTaskId(anyLong())).thenAnswer(call->artifactRows.containsKey(call.getArgument(0)));
    when(artifacts.findByBuildTaskId(anyLong())).thenAnswer(call->Optional.ofNullable(artifactRows.get(call.getArgument(0))));
    when(artifacts.findById(anyLong())).thenAnswer(call->artifactRows.values().stream().filter(a->Objects.equals(a.getId(),call.getArgument(0))).findFirst());
    when(plans.save(any())).thenAnswer(call->{BuildAutoPublishPlan plan=call.getArgument(0);planRows.put(plan.getBuildTaskId(),plan);return plan;});
    when(plans.findByBuildTaskId(anyLong())).thenAnswer(call->Optional.ofNullable(planRows.get(call.getArgument(0))));
    when(plans.findByDeploymentTaskId(anyLong())).thenAnswer(call->planRows.values().stream().filter(p->Objects.equals(p.getDeploymentTaskId(),call.getArgument(0))).findFirst());
    when(plans.findByStatusInOrderByIdAsc(any())).thenAnswer(call->planRows.values().stream().filter(BuildAutoPublishPlan::pending).toList());
    when(runner.execute(any(),any(),any(),any(),any(),anyList(),any())).thenReturn("ok");
    when(runner.imageVersion(any())).thenReturn("v1");when(runner.imageTag(any(),any())).thenReturn("registry/catalog:v1");
    auto=new BuildAutoPublishService(plans,users);executor=newExecutor();
  }
  ReleaseExecutor newExecutor(){ReleaseExecutor result=new ReleaseExecutor(tasks,steps,services,artifacts,environments,servers,runner,publisher,tx,mock(BuildStorageService.class),mock(BranchPolicyService.class),mock(FlutterBuildService.class),mock(ProductionMergeService.class));result.setAutoPublish(auto);return result;}
  BuildAutoPublishService.Target target(){return new BuildAutoPublishService.Target("test",List.of(9L));}
  ReleaseTask build(String request,BuildAutoPublishService.Target target){return executor.createBuild(1L,"release20261018","",false,"yuan",SHA,request,null,null,target);}
  ReleaseTask build(){return build(UUID.randomUUID().toString(),target());}
  void run(ReleaseTask task){executor.execute(new ReleaseExecutor.ReleaseQueued(task.getId()));}
  List<ReleaseTask> deploys(){return taskRows.values().stream().filter(t->t.getTaskKind()==TaskKind.DEPLOY).toList();}

  @Test void choicePersistsBeforeTaskIsEnqueuedAndReplayCannotRetarget(){
    doAnswer(call->{ReleaseExecutor.ReleaseQueued event=call.getArgument(0);assertThat(planRows).containsKey(event.taskId());return null;}).when(publisher).publishEvent(any(ReleaseExecutor.ReleaseQueued.class));
    String request=UUID.randomUUID().toString();ReleaseTask task=build(request,target());
    assertThat(auto.view(task.getId()).status()).isEqualTo("WAITING_BUILD");assertThat(build(request,target())).isSameAs(task);
    assertThatThrownBy(()->build(request,null)).hasMessageContaining("请求标识");
    assertThatThrownBy(()->build(request,new BuildAutoPublishService.Target("test",List.of(10L)))).hasMessageContaining("请求标识");
    assertThat(taskRows).hasSize(1);assertThat(planRows).hasSize(1);
  }
  @Test void completedBuildCreatesExactlyOneDeploymentOfItsExactArtifactWithoutBrowser(){
    ReleaseTask task=build();run(task);executor.recover();executor.recover();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(deploys()).hasSize(1);
    ReleaseTask deploy=deploys().getFirst();assertThat(deploy.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(deploy.getArtifactId()).isEqualTo(artifactRows.get(task.getId()).getId());assertThat(deploy.getCommitId()).isEqualTo(SHA);
    assertThat(deploy.getCreatedBy()).isEqualTo("yuan");assertThat(deploy.targetServerIdList()).containsExactly(9L);
    assertThat(auto.view(task.getId()).deploymentTaskId()).isEqualTo(deploy.getId());
  }
  @Test void autoPlanReusesActiveManualDeploymentOfTheSameArtifactAndExactNodes(){
    ReleaseTask build=build();build.setStatus(TaskStatus.SUCCESS);
    var artifact=artifacts.save(new Artifact(1L,build.getId(),"v1",build.getBranch(),SHA,"registry:v1"));
    var now=java.time.LocalDateTime.now();
    var manual=new DeploymentEvent(123L,artifact.getId(),build.getId(),"test","9","other",TaskStatus.RUNNING,StepType.START_SERVICE,now,now,null);
    when(tasks.deploymentEvents(1L,TaskKind.DEPLOY)).thenReturn(List.of(manual));
    executor.recoverAutomaticPublishing();
    assertThat(deploys()).isEmpty();
    assertThat(planRows.get(build.getId()).getDeploymentTaskId()).isEqualTo(123L);
    assertThat(planRows.get(build.getId()).getStatus()).isEqualTo("LINKED_EXISTING");
    user.setEnabled(false);
    var manualTask=new ReleaseTask(1L,TaskKind.DEPLOY,artifact.getId(),"test","main","",false);
    ReflectionTestUtils.setField(manualTask,"id",123L);
    assertThatCode(()->auto.validateDeploymentActor(manualTask)).doesNotThrowAnyException();
  }

  @Test void failedDeploymentRetryRefersToAnotherActiveTaskWithOverlappingNodes(){
    var source=build();source.setStatus(TaskStatus.SUCCESS);
    var artifact=artifacts.save(new Artifact(1L,source.getId(),"v1",source.getBranch(),SHA,"registry:v1"));
    var failed=new ReleaseTask(1L,TaskKind.DEPLOY,artifact.getId(),"test","main","",false);
    failed.setStatus(TaskStatus.FAILED);failed.setTargetServerIds(List.of(9L));tasks.save(failed);
    var now=java.time.LocalDateTime.now();
    var queued=new DeploymentEvent(888L,artifact.getId(),source.getId(),"test","9,10","other",TaskStatus.PENDING,null,now,null,null);
    when(tasks.deploymentEvents(1L,TaskKind.DEPLOY)).thenReturn(List.of(queued));
    assertThatThrownBy(()->executor.precheckDeploymentRetry(failed.getId())).isInstanceOf(DeploymentReview.Conflict.class).hasMessageContaining("#888");
    assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);
  }
  @Test void buildOnlyAndHistoricalTasksNeverGainAutomaticPlans(){
    ReleaseTask task=build(UUID.randomUUID().toString(),null);run(task);executor.recover();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(planRows).isEmpty();assertThat(deploys()).isEmpty();
  }
  @Test void productionAndAppCannotBypassTheirSeparateConfirmationFlows(){
    assertThatThrownBy(()->build(UUID.randomUUID().toString(),new BuildAutoPublishService.Target("prod",List.of(9L)))).hasMessageContaining("动态码");
    service.setServiceType(ServiceType.FLUTTER_ANDROID);assertThatThrownBy(this::build).hasMessageContaining("App");assertThat(taskRows).isEmpty();
  }
  @Test void invalidTargetAndMissingReleasePermissionFailBeforeBuildSubmission(){
    for(List<Long> ids:List.of(List.<Long>of(),List.of(9L,9L),List.of(-1L)))assertThatThrownBy(()->build(UUID.randomUUID().toString(),new BuildAutoPublishService.Target("test",ids))).hasMessageContaining("服务器");
    assertThatThrownBy(()->build(UUID.randomUUID().toString(),new BuildAutoPublishService.Target("test",List.of(10L)))).hasMessageContaining("集群");
    user.setMenuPermissions(List.of("builds"));assertThatThrownBy(this::build).hasMessageContaining("发布权限");assertThat(taskRows).isEmpty();
  }
  @Test void revokingActorPermissionStopsFollowupWithoutFailingSuccessfulBuild(){
    ReleaseTask task=build();user.setMenuPermissions(List.of("builds"));run(task);
    assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(deploys()).isEmpty();assertThat(auto.view(task.getId()).status()).isEqualTo("BLOCKED");assertThat(auto.view(task.getId()).message()).contains("发布权限");
  }
  @Test void removedServerTargetStopsFollowupWithoutSubstitutingAnotherServer(){
    ReleaseTask task=build();environment.setServerIds(List.of(10L));when(servers.findById(10L)).thenReturn(Optional.of(new DeploymentServer()));run(task);
    assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(deploys()).isEmpty();assertThat(auto.view(task.getId()).status()).isEqualTo("BLOCKED");
  }
  @Test void failedBuildRetryNeverRearmsAutomaticPublishing()throws Exception{
    ReleaseTask task=build();when(runner.execute(eq(StepType.PUSH_IMAGE),any(),any(),any(),any(),anyList(),any())).thenThrow(new IllegalStateException("registry unavailable"));run(task);
    assertThat(auto.view(task.getId()).status()).isEqualTo("BLOCKED");assertThat(deploys()).isEmpty();
    when(runner.execute(eq(StepType.PUSH_IMAGE),any(),any(),any(),any(),anyList(),any())).thenReturn("ok");executor.retryBuild(task.getId(),UUID.randomUUID().toString(),"yuan");run(task);
    assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(deploys()).isEmpty();assertThat(auto.view(task.getId()).message()).contains("重试仅打包");
  }
  @Test void cancelBuildStopsFollowup(){ReleaseTask task=build();executor.cancelBuild(task.getId());executor.recover();assertThat(auto.view(task.getId()).status()).isEqualTo("CANCELLED");assertThat(deploys()).isEmpty();}
  @Test void failedDeploymentDoesNotCauseAutomaticRetryOrAnotherDeployment()throws Exception{
    ReleaseTask task=build();when(runner.execute(eq(StepType.HEALTH_CHECK),any(),any(),any(),any(),anyList(),any())).thenThrow(new IllegalStateException("health failed"));run(task);executor.recover();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(deploys()).hasSize(1);assertThat(deploys().getFirst().getStatus()).isEqualTo(TaskStatus.FAILED);
  }
  @Test void restartAfterCommittedBuildSuccessCreatesFollowupAndRestartAgainDoesNotDuplicate(){
    ReleaseTask task=build();task.setStatus(TaskStatus.SUCCESS);artifacts.save(new Artifact(1L,task.getId(),"v1",task.getBranch(),SHA,"registry/catalog:v1"));
    executor=newExecutor();executor.recover();assertThat(deploys()).hasSize(1);Long deploy=deploys().getFirst().getId();executor=newExecutor();executor.recover();
    assertThat(deploys()).hasSize(1);assertThat(auto.view(task.getId()).deploymentTaskId()).isEqualTo(deploy);
  }
  @Test void interruptedLinkWriteReconcilesByStableRequestAndNeverDuplicates(){
    ReleaseTask task=build();task.setStatus(TaskStatus.SUCCESS);Artifact artifact=artifacts.save(new Artifact(1L,task.getId(),"v1",task.getBranch(),SHA,"registry/catalog:v1"));
    ReleaseTask child=executor.createDeployment(1L,artifact.getId(),"test",List.of(9L),"构建 #"+task.getId()+" 成功后自动发布",false,"yuan","auto_build_"+task.getId()+"_followup");
    assertThat(auto.view(task.getId()).deploymentTaskId()).isNull();executor=newExecutor();executor.recover();
    assertThat(deploys()).hasSize(1);assertThat(auto.view(task.getId()).deploymentTaskId()).isEqualTo(child.getId());
  }
  @Test void fullQueueRetainsReadyPlanThenAppendsFollowupInFifoOrder()throws Exception{
    ReflectionTestUtils.setField(executor,"queueCapacity",1);ReleaseTask task=build();List<String> order=new ArrayList<>();
    when(runner.execute(any(),any(),any(),any(),any(),anyList(),any())).thenAnswer(call->{ReleaseTask current=call.getArgument(2);if(call.getArgument(0)==StepType.GIT_PULL){order.add("build"+current.getId());if(current.getId().equals(task.getId()))build(UUID.randomUUID().toString(),null);}if(call.getArgument(0)==StepType.SSH_CONNECT)order.add("deploy");return "ok";});
    run(task);assertThat(order).containsExactly("build1","build2","deploy");assertThat(deploys()).hasSize(1);
  }
  @Test void permissionIsRecheckedBeforeQueuedDeploymentRuns()throws Exception{
    ReleaseTask task=build();task.setStatus(TaskStatus.SUCCESS);artifacts.save(new Artifact(1L,task.getId(),"v1",task.getBranch(),SHA,"registry/catalog:v1"));executor=newExecutor();executor.recover();user.setEnabled(false);
    run(deploys().getFirst());assertThat(deploys().getFirst().getStatus()).isEqualTo(TaskStatus.FAILED);verify(runner,never()).execute(any(),any(),any(),any(),any(),anyList(),any());
  }
  @Test void idleRecoveryRetriesTransientDatabaseFailureThenStaysIdempotent(){
    ReleaseTask task=build();java.util.concurrent.atomic.AtomicBoolean failOnce=new java.util.concurrent.atomic.AtomicBoolean(true);
    when(plans.findByStatusInOrderByIdAsc(any())).thenAnswer(call->{
      if(task.getStatus()==TaskStatus.SUCCESS&&failOnce.compareAndSet(true,false))throw new org.springframework.dao.DataAccessResourceFailureException("temporary fixture outage");
      return planRows.values().stream().filter(BuildAutoPublishPlan::pending).toList();
    });
    run(task);assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(executor.queue().pendingCount()).isZero();assertThat(deploys()).isEmpty();
    BuildAutoPublishRecovery recovery=new BuildAutoPublishRecovery(executor,true);
    recovery.tick();recovery.tick();assertThat(deploys()).hasSize(1);assertThat(auto.view(task.getId()).deploymentTaskId()).isEqualTo(deploys().getFirst().getId());
    assertThat(executor.queue().pendingCount()).isEqualTo(1);recovery.stop();
  }
  @Test void disabledOrStoppedRecoveryDoesNotInspectOrSubmitPlans(){
    ReleaseExecutor stub=mock(ReleaseExecutor.class);
    new BuildAutoPublishRecovery(stub,false).tick();BuildAutoPublishRecovery stopped=new BuildAutoPublishRecovery(stub,true);stopped.stop();stopped.tick();verifyNoInteractions(stub);
  }
}
