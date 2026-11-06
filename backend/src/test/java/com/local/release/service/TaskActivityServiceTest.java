package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.security.MenuPermissions;
import com.local.release.miniprogram.MiniProgramActivitySource;
import com.local.release.miniprogram.MiniProgramJob;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskActivityServiceTest {
  final ReleaseExecutor executor=mock(ReleaseExecutor.class);
  final ReleaseTaskRepository tasks=mock(ReleaseTaskRepository.class);
  final RuntimeOperationRepository operations=mock(RuntimeOperationRepository.class);
  final ProductionMergeRepository merges=mock(ProductionMergeRepository.class);
  final AppServiceRepository services=mock(AppServiceRepository.class);
  final AdminUserRepository users=mock(AdminUserRepository.class);
  final Map<Long,ReleaseTask> records=new HashMap<>();
  final Map<Long,AppService> catalog=new HashMap<>();
  final Instant now=Instant.parse("2026-10-18T02:00:00Z");
  TaskActivityService service;

  @BeforeEach void setup(){
    service=new TaskActivityService(executor,tasks,operations,merges,services,users);
    service.clock=Clock.fixed(now,ZoneId.of("Asia/Shanghai"));
    user("admin");user("builder","builds");user("mobile","apps");user("history","releases");user("viewer");
    app(1L,ServiceType.BACKEND);app(2L,ServiceType.FLUTTER_ANDROID);app(3L,ServiceType.FRONTEND);
    when(services.findAll()).thenAnswer(call->new ArrayList<>(catalog.values()));
    when(tasks.findAllById(any())).thenAnswer(call->{List<ReleaseTask> result=new ArrayList<>();for(Long id:call.<Iterable<Long>>getArgument(0))if(records.containsKey(id))result.add(records.get(id));return result;});
    when(tasks.findById(anyLong())).thenAnswer(call->Optional.ofNullable(records.get(call.getArgument(0))));
    queue(null,List.of(),null,0);
  }

  @Test void actualQueueOrderIsPreservedAndRunningStepHasRealTimesAndActor(){
    var running=task(30L,1L,TaskKind.BUILD,"builder",TaskStatus.RUNNING);running.setCurrentStep(StepType.PUSH_IMAGE);running.setStartedAt(local(now.minusSeconds(40)));
    task(22L,1L,TaskKind.BUILD,"builder",TaskStatus.PENDING);
    task(9L,3L,TaskKind.BUILD,"other",TaskStatus.PENDING);
    queue(running.getId(),List.of(22L,9L),null,0);
    var feed=service.snapshot("builder");
    assertThat(feed.running().createdBy()).isEqualTo("builder");assertThat(feed.running().mine()).isTrue();
    assertThat(feed.running().currentStepLabel()).isEqualTo("推送镜像");assertThat(feed.running().startedAt()).isEqualTo(now.minusSeconds(40));
    assertThat(feed.running().lastOutputAt()).isNull();assertThat(feed.running().canCancel()).isFalse();
    assertThat(feed.running().executionMode()).isEqualTo("LOCAL");assertThat(feed.running().waitingReason()).isNull();
    assertThat(feed.pending()).extracting(TaskActivityService.Activity::id).containsExactly(22L,9L);
    assertThat(feed.pending()).extracting(TaskActivityService.Activity::queuePosition).containsExactly(1,2);
    assertThat(feed.pending().getFirst().cancelPath()).isEqualTo("/api/builds/22/cancel");
    assertThat(feed.pending().getLast().canCancel()).isTrue();assertThat(feed.pendingCount()).isEqualTo(2);
    assertThat(feed.pending()).extracting(TaskActivityService.Activity::waitingReason).containsOnly("等待本机执行");
    assertThat(feed.serverTime()).isEqualTo(now);assertThat(feed.concurrency()).isEqualTo(1);
    verify(tasks,never()).save(any());verify(operations,never()).save(any());
  }

  @Test void parallelCloudModesUseSavedEnginesAndSlotsIncludeLocalPostProcessing(){
    var local=task(10L,1L,TaskKind.BUILD,"builder",TaskStatus.RUNNING);
    var backend=task(11L,1L,TaskKind.BUILD,"builder",TaskStatus.RUNNING);backend.setBackendBuildEngine("GITHUB");backend.setCurrentStep(StepType.PUSH_IMAGE);
    var frontend=task(12L,3L,TaskKind.BUILD,"builder",TaskStatus.RUNNING);frontend.setFrontendBuildEngine("GITHUB");frontend.setCurrentStep(StepType.BUILD_JAR);
    var mobile=task(13L,2L,TaskKind.BUILD,"mobile",TaskStatus.RUNNING);mobile.setMobileBuildEngine("GITHUB");mobile.setCurrentStep(StepType.ARCHIVE_ARTIFACT);
    var cloudWaiter=task(14L,1L,TaskKind.BUILD,"builder",TaskStatus.PENDING);cloudWaiter.setBackendBuildEngine("GITHUB");
    task(15L,1L,TaskKind.DEPLOY,"admin",TaskStatus.PENDING);
    queue(local.getId(),List.of(14L,15L),null,0,List.of(11L,12L,13L),3);

    var feed=service.snapshot("admin");
    assertThat(feed.concurrency()).isEqualTo(4);assertThat(feed.cloudRunningCount()).isEqualTo(3);assertThat(feed.cloudConcurrency()).isEqualTo(3);
    assertThat(feed.running().id()).isEqualTo(10L);
    assertThat(feed.runningTasks()).extracting(TaskActivityService.Activity::id).containsExactly(10L,11L,12L,13L);
    assertThat(feed.runningTasks()).extracting(TaskActivityService.Activity::executionMode).containsExactly("LOCAL","CLOUD","CLOUD","CLOUD");
    assertThat(feed.runningTasks()).allMatch(item->item.waitingReason()==null);
    assertThat(feed.runningTasks().get(1).currentStepLabel()).isEqualTo("推送镜像");
    assertThat(feed.runningTasks().get(3).currentStepLabel()).isEqualTo("保存安装包");
    assertThat(feed.pending()).extracting(TaskActivityService.Activity::executionMode).containsExactly("CLOUD","LOCAL");
    assertThat(feed.pending()).extracting(TaskActivityService.Activity::waitingReason).containsExactly("等待云构建名额","等待本机执行");
    assertThat(feed.pending()).extracting(TaskActivityService.Activity::queuePosition).containsExactly(1,2);
    verify(tasks,never()).save(any());verify(operations,never()).save(any());
  }

  @Test void cloudWaitReasonDoesNotClaimFullSlotsWhenCapacityIsFreeOrParallelismIsDisabled(){
    var pending=task(20L,1L,TaskKind.BUILD,"builder",TaskStatus.PENDING);pending.setBackendBuildEngine("GITHUB");
    catalog.remove(1L); // The task's saved engine remains authoritative after service changes or deletion.
    queue(null,List.of(20L),null,0,List.of(),3);
    var parallel=service.snapshot("builder").pending().getFirst();
    assertThat(parallel.executionMode()).isEqualTo("CLOUD");assertThat(parallel.waitingReason()).isEqualTo("等待云构建调度");
    queue(null,List.of(20L),null,0);
    var serial=service.snapshot("builder").pending().getFirst();
    assertThat(serial.executionMode()).isEqualTo("CLOUD");assertThat(serial.waitingReason()).isEqualTo("等待本机执行");
  }

  @Test void hiddenCloudTasksExposeNeitherModesNorReasonsOrModeInTheirAnonymousKeys()throws Exception{
    var running=task(900123L,1L,TaskKind.BUILD,"secret-operator",TaskStatus.RUNNING);running.setBackendBuildEngine("GITHUB");
    var pending=task(900124L,2L,TaskKind.BUILD,"secret-mobile-user",TaskStatus.PENDING);pending.setMobileBuildEngine("GITHUB");
    queue(null,List.of(900124L),null,0,List.of(900123L),1);
    var feed=service.snapshot("viewer");
    assertThat(feed.running().hidden()).isTrue();assertThat(feed.running().key()).isEqualTo("hidden:running-0");
    assertThat(feed.running().executionMode()).isNull();assertThat(feed.running().waitingReason()).isNull();
    assertThat(feed.pending().getFirst().executionMode()).isNull();assertThat(feed.pending().getFirst().waitingReason()).isNull();
    String json=new ObjectMapper().findAndRegisterModules().writeValueAsString(feed);
    assertThat(json).doesNotContain("900123","900124","secret-operator","secret-mobile-user","service-1","service-2","CLOUD","GITHUB","cloud-running","等待云构建");
  }

  @Test void noMenuUserGetsOnlyAnonymousOccupancyWithoutIdsBranchesServiceOrActors()throws Exception{
    task(900123L,1L,TaskKind.BUILD,"secret-operator",TaskStatus.RUNNING);
    task(900124L,2L,TaskKind.BUILD,"secret-mobile-user",TaskStatus.PENDING);
    queue(900123L,List.of(900124L),"secret executor label",1);
    var feed=service.snapshot("viewer");
    assertThat(feed.running().hidden()).isTrue();assertThat(feed.running().key()).isEqualTo("hidden:running");
    assertThat(feed.pending().getFirst().queuePosition()).isEqualTo(1);assertThat(feed.pendingCount()).isEqualTo(1);
    assertThat(feed.runtimePending()).hasSize(1);assertThat(feed.pendingRuntimeCount()).isEqualTo(1);
    String json=new ObjectMapper().findAndRegisterModules().writeValueAsString(feed);
    assertThat(json).doesNotContain("900123","900124","secret-operator","secret-mobile-user","service-1","service-2","feature/private","secret executor label");
    assertThat(feed.running().id()).isNull();assertThat(feed.running().serviceId()).isNull();assertThat(feed.running().taskKind()).isNull();
    assertThat(feed.running().createdBy()).isNull();assertThat(feed.running().mine()).isFalse();assertThat(feed.running().canCancel()).isFalse();
  }

  @Test void taskFamilyPermissionsFilterRegularBuildsDeploymentsAndAppsIndependently(){
    task(10L,1L,TaskKind.BUILD,"builder",TaskStatus.PENDING);
    task(11L,1L,TaskKind.DEPLOY,"builder",TaskStatus.PENDING);
    task(12L,2L,TaskKind.BUILD,"mobile",TaskStatus.PENDING);
    task(13L,2L,TaskKind.DEPLOY,"mobile",TaskStatus.PENDING);
    queue(null,List.of(10L,11L,12L,13L),null,0);
    assertThat(service.snapshot("builder").pending()).extracting(TaskActivityService.Activity::hidden).containsExactly(false,true,true,true);
    var mobile=service.snapshot("mobile").pending();
    assertThat(mobile).extracting(TaskActivityService.Activity::hidden).containsExactly(true,true,false,false);
    assertThat(mobile.get(2).detailPath()).isEqualTo("/apps/builds/12");assertThat(mobile.get(2).cancelPath()).isEqualTo("/api/apps/builds/12/cancel");
    assertThat(mobile.get(3).detailPath()).isEqualTo("/apps/releases/13");assertThat(mobile.get(3).canCancel()).isFalse();
    var history=service.snapshot("history").pending();assertThat(history).extracting(TaskActivityService.Activity::hidden).containsExactly(true,false,true,true);
    assertThat(history.get(1).canCancel()).isFalse();
    assertThat(service.snapshot("admin").pending()).noneMatch(TaskActivityService.Activity::hidden);
  }

  @Test void restartWaitersRemainSeparateAndUnknownRuntimeWaitersDoNotExposeFreeText(){
    user("operator","runtime","release");
    var active=restart(101L,"operator",TaskStatus.RUNNING);active.setCurrentStage("HEALTH_CHECK");
    var waiting=restart(102L,"operator",TaskStatus.PENDING);
    when(operations.findByStatusIn(any())).thenReturn(List.of(waiting,active));
    task(20L,1L,TaskKind.BUILD,"builder",TaskStatus.PENDING);
    queue(null,List.of(20L),"private server label and address",2);
    var feed=service.snapshot("operator");
    assertThat(feed.running().taskKind()).isEqualTo("RESTART");assertThat(feed.running().currentStepLabel()).isEqualTo("等待健康检查");
    assertThat(feed.running().detailPath()).isEqualTo("/runtime?operationId=101&serviceId=1");
    assertThat(feed.pendingCount()).isEqualTo(1);assertThat(feed.pendingRuntimeCount()).isEqualTo(2);
    assertThat(feed.runtimePending()).hasSize(2);assertThat(feed.runtimePending()).allMatch(item->item.queuePosition()==null);
    assertThat(feed.runtimePending().getFirst().id()).isEqualTo(102L);assertThat(feed.runtimePending().getFirst().canCancel()).isTrue();
    assertThat(feed.running().executionMode()).isEqualTo("LOCAL");assertThat(feed.running().waitingReason()).isNull();
    assertThat(feed.runtimePending().getFirst().executionMode()).isEqualTo("LOCAL");assertThat(feed.runtimePending().getFirst().waitingReason()).isEqualTo("等待本机执行");
    assertThat(feed.runtimePending().getLast().hidden()).isTrue();
    assertThat(feed.runtimePending().getLast().executionMode()).isNull();assertThat(feed.runtimePending().getLast().waitingReason()).isNull();
    var restricted=service.snapshot("builder");assertThat(restricted.running().hidden()).isTrue();assertThat(restricted.runtimePending()).allMatch(TaskActivityService.Activity::hidden);
  }

  @Test void productionMergeShowsItsTriggeringReleaseOnlyToUsersWhoCanReadThatFamily(){
    var deployment=task(20L,1L,TaskKind.DEPLOY,"history",TaskStatus.SUCCESS);
    var merge=new ProductionMerge(deployment.getId(),1L,"a".repeat(40));ReflectionTestUtils.setField(merge,"id",201L);merge.start();
    when(merges.findByStatus(ProductionMerge.Status.RUNNING)).thenReturn(List.of(merge));
    queue(null,List.of(),"生产代码合回 #201",0);
    var visible=service.snapshot("history").running();assertThat(visible.taskKind()).isEqualTo("MERGE");assertThat(visible.createdBy()).isEqualTo("history");
    assertThat(visible.detailPath()).isEqualTo("/releases/20");assertThat(visible.canCancel()).isFalse();
    assertThat(visible.executionMode()).isEqualTo("LOCAL");assertThat(visible.waitingReason()).isNull();
    assertThat(service.snapshot("builder").running().hidden()).isTrue();
  }

  @Test void ownRecentCompletionsCatchShortJobsAndRetriedTaskIdsHaveDistinctEventKeys(){
    var complete=task(40L,1L,TaskKind.BUILD,"builder",TaskStatus.SUCCESS);complete.setFinishedAt(local(now.minusSeconds(5)));
    var privateApp=task(41L,2L,TaskKind.BUILD,"builder",TaskStatus.SUCCESS);privateApp.setFinishedAt(local(now.minusSeconds(2)));
    when(tasks.findTop50ByCreatedByAndStatusInAndFinishedAtAfterOrderByFinishedAtDescIdDesc(eq("builder"),any(),any())).thenReturn(List.of(privateApp,complete));
    var first=service.snapshot("builder");assertThat(first.running()).isNull();assertThat(first.recentCompletions()).hasSize(1);
    assertThat(first.recentCompletions().getFirst().waitingReason()).isNull();
    String event=first.recentCompletions().getFirst().eventKey();assertThat(event).contains("task:40:");
    // Existing retry behavior reuses the task ID. Completion identity must also include finishedAt.
    complete.setFinishedAt(local(now.minusSeconds(1)));complete.setStatus(TaskStatus.FAILED);complete.setCurrentStep(StepType.BUILD_IMAGE);
    complete.setErrorMessage("password=DO_NOT_RETURN token=DO_NOT_RETURN https://private.example.invalid");
    var next=service.snapshot("builder").recentCompletions().getFirst();
    assertThat(next.eventKey()).isNotEqualTo(event);assertThat(next.errorSummary()).contains("构建镜像阶段未完成").doesNotContain("DO_NOT_RETURN","private.example.invalid");
    verify(tasks,times(2)).findTop50ByCreatedByAndStatusInAndFinishedAtAfterOrderByFinishedAtDescIdDesc(eq("builder"),eq(List.of(TaskStatus.SUCCESS,TaskStatus.FAILED,TaskStatus.CANCELLED)),eq(local(now.minus(Duration.ofHours(24)))));
  }

  @Test void restartCompletionsAndBuildCompletionsSortTogetherByFinishedTimeAndRespectPermissions(){
    user("operator","builds","runtime");
    var build=task(40L,1L,TaskKind.BUILD,"operator",TaskStatus.SUCCESS);build.setFinishedAt(local(now.minusSeconds(50)));
    var restart=restart(50L,"operator",TaskStatus.FAILED);restart.setCurrentStage("HEALTH_CHECK");restart.setFinishedAt(local(now.minusSeconds(10)));
    when(tasks.findTop50ByCreatedByAndStatusInAndFinishedAtAfterOrderByFinishedAtDescIdDesc(eq("operator"),any(),any())).thenReturn(List.of(build));
    when(operations.findTop50ByCreatedByAndStatusInAndFinishedAtAfterOrderByFinishedAtDescIdDesc(eq("operator"),any(),any())).thenReturn(List.of(restart));
    var recent=service.snapshot("operator").recentCompletions();assertThat(recent).extracting(TaskActivityService.Activity::key).containsExactly("restart:50","task:40");
    assertThat(recent.getFirst().errorSummary()).contains("请先核对目标服务状态");
  }

  @Test void deletedAppServiceRetainsMobilePermissionBoundaryAndDisabledUsersAreRejected(){
    var app=task(40L,2L,TaskKind.BUILD,"mobile",TaskStatus.RUNNING);catalog.remove(2L);
    queue(app.getId(),List.of(),null,0);
    assertThat(service.snapshot("builder").running().hidden()).isTrue();
    assertThat(service.snapshot("mobile").running().serviceType()).isEqualTo(ServiceType.FLUTTER_ANDROID);
    AdminUser disabled=new AdminUser("disabled","hash");disabled.setEnabled(false);when(users.findByUsername("disabled")).thenReturn(Optional.of(disabled));
    assertThatThrownBy(()->service.snapshot("disabled")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(()->service.snapshot("missing")).isInstanceOf(AccessDeniedException.class);
  }

  @Test void miniProgramActivityUsesItsOwnPermissionAndNeverLeaksIntoAppAccess(){
    user("mini","miniprograms");
    var source=mock(MiniProgramActivitySource.class);service.setMiniPrograms(source);
    var job=mock(MiniProgramJob.class);
    when(job.getId()).thenReturn(501L);when(job.getStatus()).thenReturn(MiniProgramJob.Status.RUNNING);
    when(job.getStage()).thenReturn("WECHAT_TRANSFER");when(job.getProjectName()).thenReturn("示例小程序");when(job.getCreatedBy()).thenReturn("mini");
    when(source.active()).thenReturn(List.of(job));queue(null,List.of(),"小程序 #501",0);
    var active=service.snapshot("mini").running();assertThat(active.taskKind()).isEqualTo("MINIPROGRAM");
    assertThat(active.detailPath()).isEqualTo("/miniprograms?jobId=501");assertThat(active.mine()).isTrue();
    assertThat(active.executionMode()).isEqualTo("LOCAL");assertThat(active.waitingReason()).isNull();
    assertThat(service.snapshot("mobile").running().hidden()).isTrue();
    assertThat(service.snapshot("mobile").running().createdBy()).isNull();
    when(job.getStatus()).thenReturn(MiniProgramJob.Status.UNKNOWN);when(job.getFinishedAt()).thenReturn(local(now.minusSeconds(5)));
    when(source.active()).thenReturn(List.of());when(source.recent("mini")).thenReturn(List.of(job));queue(null,List.of(),null,0);
    assertThat(service.snapshot("mini").recentCompletions()).extracting(TaskActivityService.Activity::status).containsExactly("UNKNOWN");
    when(job.getResolution()).thenReturn(MiniProgramJob.Resolution.CONFIRMED_UPLOADED);
    var reconciled=service.snapshot("mini").recentCompletions().getFirst();
    assertThat(reconciled.status()).isEqualTo("RECONCILED");assertThat(reconciled.currentStepLabel()).contains("微信已收到");
    assertThat(job.getStatus()).isEqualTo(MiniProgramJob.Status.UNKNOWN);
  }

  private void user(String name,String... menus){AdminUser user=new AdminUser(name,"hash");user.setMenuPermissions(MenuPermissions.forStorage(List.of(menus)));when(users.findByUsername(name)).thenReturn(Optional.of(user));}
  private void app(Long id,ServiceType type){AppService app=new AppService();ReflectionTestUtils.setField(app,"id",id);app.setName("service-"+id);app.setServiceKey("service-"+id);app.setServiceType(type);catalog.put(id,app);}
  private ReleaseTask task(Long id,Long serviceId,TaskKind kind,String creator,TaskStatus status){
    ReleaseTask task=new ReleaseTask(serviceId,kind,null,kind==TaskKind.BUILD?"build":"test","feature/private","",false);
    ReflectionTestUtils.setField(task,"id",id);ReflectionTestUtils.setField(task,"createdAt",local(now.minusSeconds(100)));
    task.setCreatedBy(creator);task.setStatus(status);if(serviceId.equals(2L))task.setDeliveryType("MOBILE");records.put(id,task);return task;
  }
  private RuntimeOperation restart(Long id,String creator,TaskStatus status){
    RuntimeOperation operation=new RuntimeOperation(2L,1L,3L,"service-1","service-1","test","private-node","192.0.2.1",creator,"key-"+id,"fingerprint");
    ReflectionTestUtils.setField(operation,"id",id);ReflectionTestUtils.setField(operation,"createdAt",local(now.minusSeconds(100)));
    operation.setStatus(status);return operation;
  }
  private void queue(Long running,List<Long> pending,String runtime,int runtimeCount){
    queue(running,pending,runtime,runtimeCount,List.of(),0);
  }
  private void queue(Long running,List<Long> pending,String runtime,int runtimeCount,List<Long> cloud,int cloudConcurrency){
    List<ReleaseExecutor.QueuedTask> queued=new ArrayList<>();for(Long id:pending){var task=records.get(id);queued.add(new ReleaseExecutor.QueuedTask(id,task.getTaskKind(),task.getServiceId(),queued.size()+1));}
    List<ReleaseExecutor.QueuedTask> cloudRunning=cloud.stream().map(id->{var task=records.get(id);return new ReleaseExecutor.QueuedTask(id,task.getTaskKind(),task.getServiceId(),0);}).toList();
    var active=records.get(running);
    when(executor.queue()).thenReturn(new ReleaseExecutor.QueueSnapshot(running,active==null?null:active.getTaskKind(),active==null?null:active.getServiceId(),pending.size(),queued,100,runtime,runtimeCount,cloudRunning,cloudConcurrency));
  }
  private LocalDateTime local(Instant value){return LocalDateTime.ofInstant(value,service.clock.getZone());}
}
