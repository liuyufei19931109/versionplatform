package com.local.release.service;

import com.local.release.domain.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CloudBuildConcurrencyTest {
  ReleaseExecutorTest fixture;ReleaseExecutor executor;GitHubBackendBuildService cloud;
  ExecutorService workers;List<Future<?>> futures=new CopyOnWriteArrayList<>();
  @BeforeEach void setup()throws Exception{
    fixture=new ReleaseExecutorTest();fixture.setup();executor=fixture.executor;
    executor.cloudBuildConcurrency=2;cloud=mock(GitHubBackendBuildService.class);executor.setBackendCloud(cloud);
    when(cloud.engineForNewTask(any())).thenReturn("GITHUB");workers=Executors.newFixedThreadPool(5);
    doAnswer(a->{ReleaseExecutor.CloudBuildQueued event=a.getArgument(0);futures.add(workers.submit(()->executor.executeCloud(event)));return null;}).when(fixture.publisher).publishEvent(any(ReleaseExecutor.CloudBuildQueued.class));
  }
  @AfterEach void cleanup()throws Exception{workers.shutdownNow();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
  @Test void twoCloudCompilesOverlapThirdWaitsAndLocalWorkRemainsSerial()throws Exception{
    CountDownLatch twoCompiling=new CountDownLatch(2),release=new CountDownLatch(1),done=new CountDownLatch(3),thirdStarted=new CountDownLatch(1);
    AtomicInteger remote=new AtomicInteger(),remoteMax=new AtomicInteger(),local=new AtomicInteger(),localMax=new AtomicInteger();
    when(cloud.build(any(),any(),any())).thenAnswer(a->{ReleaseTask t=a.getArgument(0);if(t.getId()==3)thirdStarted.countDown();int n=remote.incrementAndGet();remoteMax.accumulateAndGet(n,Math::max);twoCompiling.countDown();try{assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();return "cloud done";}finally{remote.decrementAndGet();}});
    when(fixture.runner.execute(any(),any(),any(),any(),any(),anyList(),any())).thenAnswer(a->{int n=local.incrementAndGet();localMax.accumulateAndGet(n,Math::max);try{Thread.sleep(15);return "ok";}finally{local.decrementAndGet();}});
    doAnswer(a->{done.countDown();return null;}).when(fixture.storage).afterSuccessfulBuild(anyLong());
    ReleaseTask first=fixture.build(1L,fixture.request()),second=fixture.build(2L,fixture.request()),third=fixture.build(3L,fixture.request());
    assertThat(twoCompiling.await(5,TimeUnit.SECONDS)).isTrue();assertThat(executor.queue().cloudRunningTasks()).hasSize(2);assertThat(third.getStatus()).isEqualTo(TaskStatus.PENDING);assertThat(thirdStarted.getCount()).isEqualTo(1);
    assertThat(executor.queue().pendingTasks().getFirst().waitingReason()).isEqualTo("等待云构建名额（2/2 已占用）");
    // Server runtime actions need not wait for GitHub compilation; they still use the local gate.
    Future<?> runtime=workers.submit(()->executor.executeRuntimeOperation("test",()->{int n=local.incrementAndGet();localMax.accumulateAndGet(n,Math::max);local.decrementAndGet();}));runtime.get(3,TimeUnit.SECONDS);
    release.countDown();assertThat(done.await(8,TimeUnit.SECONDS)).isTrue();for(Future<?> future:futures)future.get(5,TimeUnit.SECONDS);
    assertThat(remoteMax.get()).isEqualTo(2);assertThat(localMax.get()).isEqualTo(1);assertThat(List.of(first,second,third)).allMatch(t->t.getStatus()==TaskStatus.SUCCESS);assertThat(executor.queue().cloudRunningTasks()).isEmpty();
  }
  @Test void cancellingOneCloudTaskDoesNotInterruptTheOtherOrReuseItsContext()throws Exception{
    CountDownLatch two=new CountDownLatch(2),release=new CountDownLatch(1);AtomicBoolean otherInterrupted=new AtomicBoolean();
    when(cloud.build(any(),any(),any())).thenAnswer(a->{ReleaseTask t=a.getArgument(0);two.countDown();try{assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();return "ok";}catch(InterruptedException e){if(t.getId()!=1)otherInterrupted.set(true);throw e;}});
    ReleaseTask first=fixture.build(1L,fixture.request()),second=fixture.build(2L,fixture.request());assertThat(two.await(5,TimeUnit.SECONDS)).isTrue();
    executor.cancelBuild(first.getId());futures.getFirst().get(5,TimeUnit.SECONDS);assertThat(first.getStatus()).isEqualTo(TaskStatus.CANCELLED);assertThat(second.getStatus()).isEqualTo(TaskStatus.RUNNING);
    executor.cancelBuild(first.getId());release.countDown();futures.get(1).get(5,TimeUnit.SECONDS);assertThat(second.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(otherInterrupted).isFalse();verify(cloud).requestCancel(first.getId());verify(cloud,never()).requestCancel(second.getId());
  }
  @Test void zeroLimitPreservesSerialDispatcherAndLocalSwitchAffectsOnlyNewTasks(){
    executor.cloudBuildConcurrency=0;ReleaseTask cloudTask=fixture.build(1L,fixture.request());
    when(cloud.engineForNewTask(any())).thenReturn("LOCAL");ReleaseTask localTask=fixture.build(2L,fixture.request());
    assertThat(cloudTask.getBackendBuildEngine()).isEqualTo("GITHUB");assertThat(localTask.getBackendBuildEngine()).isEqualTo("LOCAL");assertThat(executor.queue().pendingCount()).isEqualTo(2);assertThat(executor.queue().cloudRunningTasks()).isEmpty();verify(fixture.publisher,never()).publishEvent(any(ReleaseExecutor.CloudBuildQueued.class));
    assertThat(executor.queue().pendingTasks()).extracting(ReleaseExecutor.QueuedTask::waitingReason)
      .containsExactly("等待本机执行器", "等待本机执行器");
  }
}
