package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductionGateTest {
  ReleaseTaskRepository tasks=mock(ReleaseTaskRepository.class);
  ArtifactRepository artifacts=mock(ArtifactRepository.class);
  AppServiceRepository services=mock(AppServiceRepository.class);
  ServiceEnvironmentRepository environments=mock(ServiceEnvironmentRepository.class);
  DeploymentServerRepository servers=mock(DeploymentServerRepository.class);
  ReleaseExecutor executor;

  @BeforeEach void setup(){
    AppService service=new AppService();ReflectionTestUtils.setField(service,"id",1L);service.setName("catalog");service.setServiceKey("catalog");
    when(services.findById(1L)).thenReturn(Optional.of(service));
    ReleaseTask build=new ReleaseTask(1L,TaskKind.BUILD,null,"build","main",null,false);ReflectionTestUtils.setField(build,"id",5L);build.setStatus(TaskStatus.SUCCESS);
    when(tasks.findById(5L)).thenReturn(Optional.of(build));
    Artifact artifact=new Artifact(1L,5L,"main-123456-b5","main","a".repeat(40),"registry/catalog:main-123456-b5");ReflectionTestUtils.setField(artifact,"id",10L);
    when(artifacts.findById(10L)).thenReturn(Optional.of(artifact));
    for(String name:List.of("test","prod")){
      ServiceEnvironment environment=new ServiceEnvironment();environment.setServiceId(1L);environment.setEnvironmentName(name);environment.setServerIds(List.of(9L));
      when(environments.findByServiceIdAndEnvironmentName(1L,name)).thenReturn(Optional.of(environment));
    }
    when(servers.findById(9L)).thenReturn(Optional.of(new DeploymentServer()));
    executor=new ReleaseExecutor(tasks,mock(ReleaseStepRepository.class),services,artifacts,environments,servers,mock(CommandRunner.class),
      mock(ApplicationEventPublisher.class),mock(PlatformTransactionManager.class),mock(BuildStorageService.class),mock(BranchPolicyService.class),
      mock(FlutterBuildService.class),mock(ProductionMergeService.class));
  }

  private void verifiedIn(boolean value){
    when(tasks.existsByArtifactIdAndTaskKindAndStatusAndSimulateFalseAndEnvironmentNameIn(10L,TaskKind.DEPLOY,TaskStatus.SUCCESS,List.of("test","prod"))).thenReturn(value);
  }

  @Test void productionRejectsArtifactThatNeverPassedPreProduction(){
    verifiedIn(false);
    assertThatThrownBy(()->executor.precheckDeployment(1L,10L,"prod",List.of(9L)))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("预生产发布成功");
  }

  @Test void productionAcceptsArtifactWithSuccessfulPreProductionOrProductionDeployment(){
    verifiedIn(true);
    assertThatCode(()->executor.precheckDeployment(1L,10L,"prod",List.of(9L))).doesNotThrowAnyException();
    assertThatCode(()->executor.precheckDeployment(1L,10L,"PROD",List.of(9L))).doesNotThrowAnyException();
  }

  @Test void preProductionIsNotGated(){
    verifiedIn(false);
    assertThatCode(()->executor.precheckDeployment(1L,10L,"test",List.of(9L))).doesNotThrowAnyException();
    verify(tasks,never()).existsByArtifactIdAndTaskKindAndStatusAndSimulateFalseAndEnvironmentNameIn(any(),any(),any(),anyCollection());
  }

  @Test void productionRetryOfUnverifiedArtifactIsRejected(){
    verifiedIn(false);
    ReleaseTask failed=new ReleaseTask(1L,TaskKind.DEPLOY,10L,"prod","main",null,false);ReflectionTestUtils.setField(failed,"id",20L);
    failed.setStatus(TaskStatus.FAILED);failed.setTargetServerIds(List.of(9L));
    when(tasks.findById(20L)).thenReturn(Optional.of(failed));
    assertThatThrownBy(()->executor.precheckDeploymentRetry(20L)).hasMessageContaining("预生产发布成功");
  }
}
