package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.security.MenuPermissions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeRestartServiceTest {
  final AppServiceRepository services=mock(AppServiceRepository.class);
  final ServiceEnvironmentRepository environments=mock(ServiceEnvironmentRepository.class);
  final DeploymentServerRepository servers=mock(DeploymentServerRepository.class);
  final AdminUserRepository users=mock(AdminUserRepository.class);
  final ReleaseTaskRepository releases=mock(ReleaseTaskRepository.class);
  final RuntimeOperationRepository operations=mock(RuntimeOperationRepository.class);
  final RuntimeProbe probe=mock(RuntimeProbe.class);
  final RuntimeRestartRunner runner=mock(RuntimeRestartRunner.class);
  final RuntimeOverviewService overview=mock(RuntimeOverviewService.class);
  final ReleaseExecutor executor=mock(ReleaseExecutor.class);
  final Map<Long,RuntimeOperation> records=new ConcurrentHashMap<>();
  final CountDownLatch completed=new CountDownLatch(1);
  final AtomicLong sequence=new AtomicLong();
  AppService app;ServiceEnvironment env;DeploymentServer node;RuntimeRestartService service;
  static final String CID="a".repeat(64), NEW_CID="c".repeat(64), IMAGE="sha256:"+"b".repeat(64);
  @BeforeEach void setup()throws Exception {
    app=new AppService();app.setName("前端");app.setServiceKey("ui");app.setServiceType(ServiceType.FRONTEND);ReflectionTestUtils.setField(app,"id",1L);
    env=new ServiceEnvironment();env.setServiceId(1L);env.setEnvironmentName("test");env.setServerIds(List.of(3L));env.setComposeService("ui");env.setContainerPort(80);ReflectionTestUtils.setField(env,"id",2L);
    node=new DeploymentServer();node.setName("node");node.setHost("example.org");node.setComposeDirectory("/opt/application");ReflectionTestUtils.setField(node,"id",3L);
    when(services.findById(1L)).thenReturn(Optional.of(app));when(environments.findById(2L)).thenReturn(Optional.of(env));when(servers.findById(3L)).thenReturn(Optional.of(node));
    when(users.findByUsername("admin")).thenReturn(Optional.of(new AdminUser("admin","hash")));
    when(probe.inspect(any(),any(),any())).thenReturn(observed(CID));
    when(runner.restart(any(),any(),any(),any())).thenReturn(Map.of(NEW_CID,IMAGE));
    when(operations.findByRequestKey(anyString())).thenAnswer(call->records.values().stream().filter(r->r.getRequestKey().equals(call.getArgument(0))).findFirst());
    when(operations.save(any())).thenAnswer(call->{RuntimeOperation value=call.getArgument(0);if(value.getId()==null)ReflectionTestUtils.setField(value,"id",sequence.incrementAndGet());records.put(value.getId(),value);if(Set.of(TaskStatus.SUCCESS,TaskStatus.FAILED,TaskStatus.CANCELLED).contains(value.getStatus()))completed.countDown();return value;});
    when(operations.findById(anyLong())).thenAnswer(call->Optional.ofNullable(records.get(call.getArgument(0))));
    doAnswer(call->{((Runnable)call.getArgument(1)).run();return null;}).when(executor).executeCancellableRuntimeOperation(anyString(),any());
    service=new RuntimeRestartService(services,environments,servers,users,releases,operations,probe,runner,overview,executor);
  }
  @AfterEach void close(){service.close();}
  RuntimeProbe.Result observed(String id){return new RuntimeProbe.Result("OK","ok",List.of(new RuntimeProbe.ContainerInfo(id,"ui","running","healthy","registry/ui:old",IMAGE,List.of(),List.of(),0L,0L,List.of(),"")));}
  @Test void successIsRecordedSeparatelyAndRepeatedRequestReturnsSameOperation()throws Exception {
    var preview=service.preview(2L,3L,"admin");var operation=service.create(preview.token(),"request","admin");
    assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue();assertThat(operation.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(service.create(preview.token(),"request","admin")).isSameAs(operation);
    verify(runner,times(1)).restart(same(app),same(env),same(node),eq(Map.of(CID,IMAGE)));verify(runner,times(1)).health(same(app),same(env),same(node),eq(Map.of(NEW_CID,IMAGE)));verify(releases,never()).save(any());
    assertThat(operation.getLogText()).contains("registry/ui:old","健康检查通过",CID);
  }
  @Test void changedContainerWhileWaitingAbortsBeforeRestart()throws Exception {
    var preview=service.preview(2L,3L,"admin");when(probe.inspect(any(),any(),any())).thenReturn(observed("c".repeat(64)));
    var operation=service.create(preview.token(),"request","admin");assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue();
    assertThat(operation.getStatus()).isEqualTo(TaskStatus.FAILED);assertThat(operation.getErrorMessage()).contains("镜像已变化");verify(runner,never()).restart(any(),any(),any(),any());
  }
  @Test void healthFailureIsFailureAndDoesNotAutomaticallyRestartAgain()throws Exception {
    doThrow(new IllegalArgumentException("健康检查未通过")).when(runner).health(any(),any(),any(),any());
    var preview=service.preview(2L,3L,"admin");var operation=service.create(preview.token(),"request","admin");assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue();
    assertThat(operation.getStatus()).isEqualTo(TaskStatus.FAILED);verify(runner,times(1)).restart(any(),any(),any(),any());assertThat(records).hasSize(1);
  }
  @Test void readOnlyUserAndUnboundServerCannotReachRemoteRestart() {
    AdminUser reader=new AdminUser("reader","hash");reader.setMenuPermissions(MenuPermissions.forStorage(List.of("runtime")));when(users.findByUsername("reader")).thenReturn(Optional.of(reader));
    assertThatThrownBy(()->service.preview(2L,3L,"reader")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(()->service.preview(2L,4L,"admin")).hasMessageContaining("不属于");verifyNoInteractions(probe,runner);
  }
  @Test void editedConfigurationAndForeignConfirmationCannotBeSubmitted()throws Exception {
    var preview=service.preview(2L,3L,"admin");node.setComposeDirectory("/different/project");
    assertThatThrownBy(()->service.create(preview.token(),"request","admin")).hasMessageContaining("配置已变化");assertThat(records).isEmpty();
    node.setComposeDirectory("/opt/application");AdminUser other=new AdminUser("other","hash");other.setMenuPermissions(List.of("runtime","release"));when(users.findByUsername("other")).thenReturn(Optional.of(other));
    assertThatThrownBy(()->service.create(preview.token(),"request","other")).hasMessageContaining("确认已过期");assertThat(records).isEmpty();
  }
  @Test void pendingDeploymentBlocksPreviewAndStartupDoesNotReplayOldRestart() {
    ReleaseTask deploy=new ReleaseTask(1L,TaskKind.DEPLOY,9L,"test","main","",false);deploy.setTargetServerIds(List.of(3L));
    when(releases.findByTaskKindAndStatusIn(eq(TaskKind.DEPLOY),anyList())).thenReturn(List.of(deploy));
    assertThatThrownBy(()->service.preview(2L,3L,"admin")).hasMessageContaining("发版任务");verifyNoInteractions(probe);
    RuntimeOperation interrupted=new RuntimeOperation(2L,1L,3L,"ui","ui","test","node","host","admin","key","fingerprint");interrupted.setStatus(TaskStatus.RUNNING);
    when(operations.findByStatusIn(any())).thenReturn(List.of(interrupted));service.recover();assertThat(interrupted.getStatus()).isEqualTo(TaskStatus.FAILED);assertThat(interrupted.getErrorMessage()).contains("不会自动重复重启");verifyNoInteractions(runner,executor);
  }

  @ParameterizedTest @ValueSource(strings={"CHECK_TARGET","RELOAD_CONFIGURATION","HEALTH_CHECK"})
  void cancellationWaitsForCurrentRemoteCommandAndPreventsRemainingStages(String phase)throws Exception{
    var preview=service.preview(2L,3L,"admin");
    CountDownLatch entered=new CountDownLatch(1),cleaning=new CountDownLatch(1),finishCleanup=new CountDownLatch(1);
    org.mockito.stubbing.Answer<Object> remote=call->{
      entered.countDown();
      try{new CountDownLatch(1).await(10,TimeUnit.SECONDS);throw new AssertionError("Expected interrupt");}
      catch(InterruptedException expected){
        cleaning.countDown();assertThat(finishCleanup.await(5,TimeUnit.SECONDS)).isTrue();Thread.currentThread().interrupt();throw expected;
      }
    };
    if(phase.equals("CHECK_TARGET"))doAnswer(remote).when(probe).inspect(any(),any(),any());
    if(phase.equals("RELOAD_CONFIGURATION"))doAnswer(remote).when(runner).restart(any(),any(),any(),any());
    if(phase.equals("HEALTH_CHECK"))doAnswer(remote).when(runner).health(any(),any(),any(),any());
    var operation=service.create(preview.token(),"request","admin");
    try{
      assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(operation.getCurrentStage()).isEqualTo(phase);
      assertThat(service.cancel(operation.getId(),"admin")).contains("等待当前远程命令退出");
      assertThat(cleaning.await(5,TimeUnit.SECONDS)).isTrue();service.cancel(operation.getId(),"admin");
      assertThat(operation.getStatus()).isEqualTo(TaskStatus.RUNNING);assertThat(completed.getCount()).isEqualTo(1);
      finishCleanup.countDown();assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue();
      assertThat(operation.getStatus()).isEqualTo(TaskStatus.CANCELLED);assertThat(operation.getCurrentStage()).isEqualTo("CANCELLED");
      assertThat(operation.getLogText()).contains("远端状态需核对","不会自动回滚").doesNotContain("全部目标容器运行正常");
      if(phase.equals("CHECK_TARGET"))verify(runner,never()).restart(any(),any(),any(),any());
      if(!phase.equals("HEALTH_CHECK"))verify(runner,never()).health(any(),any(),any(),any());
      verify(releases,never()).save(any());
    }finally{finishCleanup.countDown();}
  }

  @Test void swallowedRestartInterruptStillStopsBeforeHealthCheck()throws Exception{
    CountDownLatch entered=new CountDownLatch(1);
    doAnswer(call->{entered.countDown();try{new CountDownLatch(1).await(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return null;}).when(runner).restart(any(),any(),any(),any());
    var preview=service.preview(2L,3L,"admin");var operation=service.create(preview.token(),"request","admin");
    assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();service.cancel(operation.getId(),"admin");
    assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue();assertThat(operation.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    verify(runner,never()).health(any(),any(),any(),any());
  }

  @Test void queuedCancellationDoesNotExecuteRemoteActionWhenSerialSlotArrives()throws Exception{
    CountDownLatch waiting=new CountDownLatch(1),slotAvailable=new CountDownLatch(1),callbackReturned=new CountDownLatch(1);
    doAnswer(call->{waiting.countDown();try{assertThat(slotAvailable.await(5,TimeUnit.SECONDS)).isTrue();((Runnable)call.getArgument(1)).run();return null;}finally{callbackReturned.countDown();}}).when(executor).executeCancellableRuntimeOperation(anyString(),any());
    var preview=service.preview(2L,3L,"admin");clearInvocations(probe);
    var operation=service.create(preview.token(),"request","admin");
    try{
      assertThat(waiting.await(5,TimeUnit.SECONDS)).isTrue();assertThat(service.cancel(operation.getId(),"admin")).contains("排队任务已取消");
      assertThat(operation.getStatus()).isEqualTo(TaskStatus.CANCELLED);assertThat(callbackReturned.await(2,TimeUnit.SECONDS)).isTrue();assertThat(slotAvailable.getCount()).isEqualTo(1);
      verifyNoInteractions(probe);verify(runner,never()).restart(any(),any(),any(),any());verify(runner,never()).health(any(),any(),any(),any());assertThat(operation.getStartedAt()).isNull();
    }finally{slotAvailable.countDown();}
  }

  @Test void cancelledUndispatchedCallbackCannotRequeueOrClearNewSameNodeOperation()throws Exception{
    ExecutorService original=(ExecutorService)ReflectionTestUtils.getField(service,"worker");original.shutdownNow();
    ExecutorService held=mock(ExecutorService.class);List<Runnable> callbacks=new ArrayList<>();
    when(held.submit(any(Runnable.class))).thenAnswer(call->{callbacks.add(call.getArgument(0));return CompletableFuture.completedFuture(null);});
    ReflectionTestUtils.setField(service,"worker",held);
    var firstPreview=service.preview(2L,3L,"admin");var first=service.create(firstPreview.token(),"first","admin");
    assertThat(service.cancel(first.getId(),"admin")).contains("排队任务已取消");
    var secondPreview=service.preview(2L,3L,"admin");var second=service.create(secondPreview.token(),"second","admin");
    clearInvocations(probe,executor,runner);
    callbacks.getFirst().run();
    verifyNoInteractions(probe,executor,runner);assertThat(first.getStatus()).isEqualTo(TaskStatus.CANCELLED);assertThat(second.getStatus()).isEqualTo(TaskStatus.PENDING);
    assertThatThrownBy(()->service.preview(2L,3L,"admin")).hasMessageContaining("已有重启任务");
    callbacks.get(1).run();assertThat(second.getStatus()).isEqualTo(TaskStatus.SUCCESS);verify(runner,times(1)).restart(any(),any(),any(),any());
  }

  @Test void waitingCancellationReleasesNodeBeforeOldWorkerFinishesAndOldFinallyCannotClearReplacement()throws Exception{
    CountDownLatch firstWaiting=new CountDownLatch(1),firstInterrupted=new CountDownLatch(1),releaseOldWorker=new CountDownLatch(1),firstExited=new CountDownLatch(1),secondWaiting=new CountDownLatch(1),releaseSecond=new CountDownLatch(1);
    CountDownLatch firstInvalidated=new CountDownLatch(1),bothInvalidated=new CountDownLatch(2);
    doAnswer(call->{firstInvalidated.countDown();bothInvalidated.countDown();return null;}).when(overview).invalidate(2L,3L);
    AtomicLong calls=new AtomicLong();
    doAnswer(call->{
      if(calls.incrementAndGet()==1){
        firstWaiting.countDown();
        try{new CountDownLatch(1).await(10,TimeUnit.SECONDS);throw new AssertionError("Expected queued cancellation");}
        catch(InterruptedException expected){firstInterrupted.countDown();assertThat(releaseOldWorker.await(5,TimeUnit.SECONDS)).isTrue();throw expected;}
        finally{firstExited.countDown();}
      }
      secondWaiting.countDown();assertThat(releaseSecond.await(5,TimeUnit.SECONDS)).isTrue();((Runnable)call.getArgument(1)).run();return null;
    }).when(executor).executeCancellableRuntimeOperation(anyString(),any());
    var preview=service.preview(2L,3L,"admin");var first=service.create(preview.token(),"first","admin");
    try{
      assertThat(firstWaiting.await(5,TimeUnit.SECONDS)).isTrue();assertThat(service.cancel(first.getId(),"admin")).contains("排队任务已取消");assertThat(firstInterrupted.await(5,TimeUnit.SECONDS)).isTrue();
      var replacement=service.preview(2L,3L,"admin");var second=service.create(replacement.token(),"second","admin");assertThat(secondWaiting.await(5,TimeUnit.SECONDS)).isTrue();
      releaseOldWorker.countDown();assertThat(firstExited.await(5,TimeUnit.SECONDS)).isTrue();
      assertThat(firstInvalidated.await(5,TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(()->service.preview(2L,3L,"admin")).hasMessageContaining("已有重启任务");
      assertThat(first.getStatus()).isEqualTo(TaskStatus.CANCELLED);assertThat(second.getStatus()).isEqualTo(TaskStatus.PENDING);verify(runner,never()).restart(any(),any(),any(),any());verify(runner,never()).health(any(),any(),any(),any());
      releaseSecond.countDown();
      assertThat(bothInvalidated.await(5,TimeUnit.SECONDS)).isTrue();
      verify(overview,times(2)).invalidate(2L,3L);
      assertThat(second.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    }finally{releaseOldWorker.countDown();releaseSecond.countDown();}
  }

  @Test void cancellationRequiresBothPermissionsAndOwnerOrAdministrator()throws Exception{
    for(String name:List.of("owner","other")){AdminUser user=new AdminUser(name,"hash");user.setMenuPermissions(List.of("runtime","release"));when(users.findByUsername(name)).thenReturn(Optional.of(user));}
    AdminUser reader=new AdminUser("reader","hash");reader.setMenuPermissions(List.of("runtime"));when(users.findByUsername("reader")).thenReturn(Optional.of(reader));
    CountDownLatch entered=new CountDownLatch(1);
    doAnswer(call->{entered.countDown();new CountDownLatch(1).await(10,TimeUnit.SECONDS);return null;}).when(runner).restart(any(),any(),any(),any());
    var preview=service.preview(2L,3L,"owner");var operation=service.create(preview.token(),"request","owner");
    assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(()->service.cancel(operation.getId(),"reader")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(()->service.cancel(operation.getId(),"other")).isInstanceOf(AccessDeniedException.class).hasMessageContaining("自己");
    assertThat(operation.getStatus()).isEqualTo(TaskStatus.RUNNING);service.cancel(operation.getId(),"owner");
    assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue();assertThat(service.cancel(operation.getId(),"admin")).contains("已取消");
  }

  @Test void committedSuccessfulRestartCannotBeOverwrittenByLateCancellation()throws Exception{
    CountDownLatch committing=new CountDownLatch(1),finishCommit=new CountDownLatch(1),cancelCalled=new CountDownLatch(1);
    doAnswer(call->{RuntimeOperation value=call.getArgument(0);if(value.getId()==null)ReflectionTestUtils.setField(value,"id",sequence.incrementAndGet());records.put(value.getId(),value);
      if(value.getStatus()==TaskStatus.SUCCESS){committing.countDown();assertThat(finishCommit.await(5,TimeUnit.SECONDS)).isTrue();completed.countDown();}return value;
    }).when(operations).save(any());
    var preview=service.preview(2L,3L,"admin");var operation=service.create(preview.token(),"request","admin");
    ExecutorService cancelPool=Executors.newSingleThreadExecutor();
    try{
      assertThat(committing.await(5,TimeUnit.SECONDS)).isTrue();Future<?> cancel=cancelPool.submit(()->{cancelCalled.countDown();assertThatThrownBy(()->service.cancel(operation.getId(),"admin")).isInstanceOf(IllegalArgumentException.class);});
      assertThat(cancelCalled.await(5,TimeUnit.SECONDS)).isTrue();assertThat(cancel.isDone()).isFalse();finishCommit.countDown();cancel.get(5,TimeUnit.SECONDS);
      assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue();assertThat(operation.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    }finally{finishCommit.countDown();cancelPool.shutdownNow();assertThat(cancelPool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
  }
}
