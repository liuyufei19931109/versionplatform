package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import org.junit.jupiter.api.*;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real commits matter here: repository mocks cannot prove rollback or the task/plan linking gap. */
@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false","spring.datasource.name=build-auto-publish-persistence"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class BuildAutoPublishPersistenceTest {
  @Autowired ReleaseTaskRepository tasks;
  @Autowired ReleaseStepRepository steps;
  @Autowired ArtifactRepository artifacts;
  @Autowired AppServiceRepository services;
  @Autowired ServiceEnvironmentRepository environments;
  @Autowired DeploymentServerRepository servers;
  @Autowired BuildAutoPublishPlanRepository plans;
  @Autowired AdminUserRepository users;
  @Autowired PlatformTransactionManager transactions;
  static final String SHA="a".repeat(40);
  ApplicationEventPublisher publisher;
  CommandRunner runner;
  AppService service;
  DeploymentServer server;
  BuildAutoPublishService auto;
  ReleaseExecutor executor;

  @BeforeEach void setup()throws Exception{
    plans.deleteAll();steps.deleteAll();artifacts.deleteAll();tasks.deleteAll();environments.deleteAll();services.deleteAll();servers.deleteAll();users.deleteAll();
    AdminUser user=new AdminUser("operator","hash");user.setMenuPermissions(List.of("builds","release"));users.saveAndFlush(user);
    service=new AppService();service.setName("Catalog");service.setServiceKey("catalog");service.setImageRepository("registry.example.invalid/catalog");service=services.saveAndFlush(service);
    server=new DeploymentServer();server.setName("PRE");server.setHost("192.0.2.1");server=servers.saveAndFlush(server);
    ServiceEnvironment environment=new ServiceEnvironment();environment.setServiceId(service.getId());environment.setEnvironmentName("test");environment.setServerIds(List.of(server.getId()));environments.saveAndFlush(environment);
    publisher=mock(ApplicationEventPublisher.class);runner=mock(CommandRunner.class);
    when(runner.execute(any(),any(),any(),any(),any(),anyList(),any())).thenReturn("ok");
    when(runner.imageVersion(any())).thenAnswer(call->"v"+call.<ReleaseTask>getArgument(0).getId());
    when(runner.imageTag(any(),any())).thenReturn("registry.example.invalid/catalog:v1");
    auto=new BuildAutoPublishService(plans,users);executor=newExecutor(auto);
  }

  @Test void buildStepsAndExplicitPlanCommitTogetherBeforeEnqueueEvent(){
    doAnswer(call->{
      ReleaseExecutor.ReleaseQueued queued=call.getArgument(0);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(tasks.findById(queued.taskId())).isPresent();
      assertThat(plans.findByBuildTaskId(queued.taskId())).isPresent();
      assertThat(steps.findByReleaseTaskIdOrderById(queued.taskId())).hasSize(4);
      return null;
    }).when(publisher).publishEvent(any(ReleaseExecutor.ReleaseQueued.class));
    ReleaseTask build=createBuild();
    assertThat(tasks.count()).isEqualTo(1);assertThat(plans.count()).isEqualTo(1);
    assertThat(plans.findByBuildTaskId(build.getId()).orElseThrow().serverIdList()).containsExactly(server.getId());
    verify(publisher,times(1)).publishEvent(any(ReleaseExecutor.ReleaseQueued.class));
  }

  @Test void failingPlanWriteRollsBackNewBuildEvenIfPlanWasAlreadyFlushed(){
    BuildAutoPublishPlanRepository faulty=mock(BuildAutoPublishPlanRepository.class,AdditionalAnswers.delegatesTo(plans));
    doAnswer(call->{plans.saveAndFlush(call.getArgument(0));throw new IllegalStateException("fixture failed after plan flush");}).when(faulty).save(any());
    executor=newExecutor(new BuildAutoPublishService(faulty,users));
    assertThatThrownBy(this::createBuild).hasMessageContaining("fixture failed");
    assertThat(tasks.count()).isZero();assertThat(plans.count()).isZero();assertThat(steps.count()).isZero();
    assertThat(executor.queue().pendingCount()).isZero();verifyNoInteractions(publisher);
  }

  @Test void committedSuccessRecoveredAcrossExecutorInstancesCreatesOneExactChild(){
    ReleaseTask build=createBuild();Artifact artifact=finishBuild(build);
    executor=newExecutor(auto);executor.recover();
    ReleaseTask deployment=deployments().getFirst();
    executor=newExecutor(auto);executor.recover();executor.recoverAutomaticPublishing();
    assertThat(deployments()).hasSize(1);assertThat(plans.findByBuildTaskId(build.getId()).orElseThrow().getDeploymentTaskId()).isEqualTo(deployment.getId());
    assertThat(deployment.getArtifactId()).isEqualTo(artifact.getId());assertThat(deployment.getCommitId()).isEqualTo(SHA);
    assertThat(deployment.targetServerIdList()).containsExactly(server.getId());assertThat(deployment.getEnvironmentName()).isEqualTo("test");
    assertThat(tasks.findById(build.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.SUCCESS);
  }

  @Test void failedPlanLinkWriteReconcilesCommittedChildWithoutPublishingAnother(){
    ReleaseTask build=createBuild();finishBuild(build);
    BuildAutoPublishPlanRepository faulty=mock(BuildAutoPublishPlanRepository.class,AdditionalAnswers.delegatesTo(plans));
    AtomicBoolean failOnce=new AtomicBoolean(true);
    doAnswer(call->{BuildAutoPublishPlan value=call.getArgument(0);if(value.getDeploymentTaskId()!=null&&failOnce.compareAndSet(true,false))throw new org.springframework.dao.DataAccessResourceFailureException("fixture link unavailable");return plans.save(value);}).when(faulty).save(any());
    executor=newExecutor(new BuildAutoPublishService(faulty,users));executor.recover();
    assertThat(deployments()).hasSize(1);Long child=deployments().getFirst().getId();
    assertThat(plans.findByBuildTaskId(build.getId()).orElseThrow().getDeploymentTaskId()).isNull();
    assertThat(plans.findByBuildTaskId(build.getId()).orElseThrow().getStatus()).isEqualTo("WAITING_BUILD");
    executor=newExecutor(auto);executor.recover();executor.recoverAutomaticPublishing();
    assertThat(deployments()).hasSize(1);assertThat(plans.findByBuildTaskId(build.getId()).orElseThrow().getDeploymentTaskId()).isEqualTo(child);
    assertThat(executor.queue().pendingTasks()).extracting(ReleaseExecutor.QueuedTask::taskId).containsExactly(child);
  }

  @Test void unplannedHistoricalSuccessIsNeverPickedUpByRecovery(){
    ReleaseTask build=executor.createBuild(service.getId(),"release20261018","",false,"operator",SHA,UUID.randomUUID().toString());finishBuild(build);
    executor=newExecutor(auto);executor.recover();executor.recoverAutomaticPublishing();
    assertThat(plans.count()).isZero();assertThat(deployments()).isEmpty();
  }

  private ReleaseTask createBuild(){return executor.createBuild(service.getId(),"release20261018","",false,"operator",SHA,UUID.randomUUID().toString(),null,null,new BuildAutoPublishService.Target("test",List.of(server.getId())));}
  private Artifact finishBuild(ReleaseTask build){
    build.setStatus(TaskStatus.SUCCESS);build.setFinishedAt(LocalDateTime.now());tasks.saveAndFlush(build);
    return artifacts.saveAndFlush(new Artifact(service.getId(),build.getId(),"v"+build.getId(),build.getBranch(),SHA,"registry.example.invalid/catalog:v1"));
  }
  private List<ReleaseTask> deployments(){return tasks.findByTaskKindOrderByCreatedAtDesc(TaskKind.DEPLOY);}
  private ReleaseExecutor newExecutor(BuildAutoPublishService selected){
    ReleaseExecutor result=new ReleaseExecutor(tasks,steps,services,artifacts,environments,servers,runner,publisher,transactions,
      mock(BuildStorageService.class),mock(BranchPolicyService.class),mock(FlutterBuildService.class),mock(ProductionMergeService.class));
    result.setAutoPublish(selected);return result;
  }
}
