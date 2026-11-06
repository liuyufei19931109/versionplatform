package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.security.CredentialSecretCrypto;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;
import static com.local.release.domain.ProductionMerge.Status.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionMergeServiceTest {
  ProductionMergeRepository merges=mock(ProductionMergeRepository.class);BranchCheckRepository checks=mock(BranchCheckRepository.class);ArtifactRepository artifacts=mock(ArtifactRepository.class);ReleaseTaskRepository tasks=mock(ReleaseTaskRepository.class);AppServiceRepository services=mock(AppServiceRepository.class);ServiceEnvironmentRepository environments=mock(ServiceEnvironmentRepository.class);PolicyGitRepository git=mock(PolicyGitRepository.class);CredentialSecretCrypto crypto=mock(CredentialSecretCrypto.class);
  ProductionMergeService service=new ProductionMergeService(merges,checks,artifacts,tasks,services,environments,git,crypto);
  Map<Long,ProductionMerge> records=new LinkedHashMap<>();ReleaseTask release;AppService app;ServiceEnvironment env;BranchCheck proof;
  final String SHA="a".repeat(40),BASE="b".repeat(40),REPO="git@example.invalid:team/app.git";
  @BeforeEach void setup()throws Exception{
    release=new ReleaseTask(1L,TaskKind.DEPLOY,50L,"prod","release",null,false);id(release,20L);release.setCommitId(SHA);release.setStatus(TaskStatus.SUCCESS);release.setTargetServerIds(List.of(3L,4L));release.setProductionScopeComplete(true);release.setMergeEnqueuePending(true);
    Artifact artifact=new Artifact(1L,10L,"b10","release",SHA,"repo/image:b10");id(artifact,50L);when(artifacts.findById(50L)).thenReturn(Optional.of(artifact));
    proof=new BranchCheck(10L,1L,SHA);proof.complete(BASE,BranchCheck.Status.PASSED,"ok","frozen-encrypted",PolicyGitRepository.identity(REPO));id(proof,6L);when(checks.findFirstByTaskIdOrderByIdDesc(10L)).thenReturn(Optional.of(proof));
    app=new AppService();id(app,1L);app.setGitRepo(REPO);when(services.findAll()).thenReturn(List.of(app));
    env=new ServiceEnvironment();env.setServiceId(1L);env.setEnvironmentName("prod");env.setServerIds(List.of(3L,4L));when(environments.findByServiceIdAndEnvironmentName(1L,"prod")).thenReturn(Optional.of(env));
    when(tasks.findById(20L)).thenReturn(Optional.of(release));when(tasks.saveAndFlush(any())).thenAnswer(call->call.getArgument(0));
    when(merges.findByReleaseTaskId(anyLong())).thenAnswer(call->records.values().stream().filter(m->m.getReleaseTaskId().equals(call.getArgument(0))).findFirst());
    when(merges.findById(anyLong())).thenAnswer(call->Optional.ofNullable(records.get(call.<Long>getArgument(0))));
    when(merges.saveAndFlush(any())).thenAnswer(call->{ProductionMerge m=call.getArgument(0);if(m.getId()==null)id(m,(long)records.size()+1);records.put(m.getId(),m);return m;});
    when(merges.findByStatus(any())).thenAnswer(call->records.values().stream().filter(m->m.getStatus()==call.getArgument(0)).toList());
    when(crypto.decrypt("frozen-encrypted")).thenReturn(REPO);when(git.mergeFixedCommit(REPO,SHA)).thenReturn(new PolicyGitRepository.MergeResult("MERGED",BASE,SHA));
  }
  @Test void queueIsDurableIdempotentAndOnlyPushesFrozenDeployedCommit()throws Exception{
    service.ensureForSuccessfulDeployment(release);service.ensureForSuccessfulDeployment(release);assertThat(records).hasSize(1);assertThat(release.isMergeEnqueuePending()).isFalse();verifyNoInteractions(git);
    app.setGitRepo("git@example.invalid:other/new.git");service.execute(1L);ProductionMerge merge=records.get(1L);assertThat(merge.getStatus()).isEqualTo(MERGED);assertThat(merge.getBaselineAfter()).isEqualTo(SHA);verify(git).mergeFixedCommit(REPO,SHA);assertThat(release.getStatus()).isEqualTo(TaskStatus.SUCCESS);
  }
  @Test void nonProductionFailedAndBuildTasksNeverQueue(){
    for(ReleaseTask task:List.of(new ReleaseTask(1L,TaskKind.DEPLOY,50L,"test","release",null,false),new ReleaseTask(1L,TaskKind.BUILD,null,"build","release",null,false),new ReleaseTask(1L,TaskKind.DEPLOY,50L,"dev","release",null,false))){id(task,99L);task.setStatus(TaskStatus.SUCCESS);service.ensureForSuccessfulDeployment(task);}
    release.setStatus(TaskStatus.FAILED);service.ensureForSuccessfulDeployment(release);assertThat(records).isEmpty();verifyNoInteractions(git);
  }
  @Test void productionAppConfirmationNeedsNoServerScope(){
    release.setDeliveryType("MOBILE");release.setProductionScopeComplete(null);release.setTargetServerIds(List.of());when(environments.findByServiceIdAndEnvironmentName(1L,"prod")).thenReturn(Optional.empty());
    service.ensureForSuccessfulDeployment(release);assertThat(records.get(1L).getStatus()).isEqualTo(QUEUED);
  }
  @Test void partialOriginalScopeCannotBecomeFullByLaterConfigurationShrink(){
    release.setProductionScopeComplete(false);release.setTargetServerIds(List.of(3L));env.setServerIds(List.of(3L));service.ensureForSuccessfulDeployment(release);assertThat(records.get(1L).getReason()).isEqualTo("PARTIAL_PRODUCTION");assertThat(service.get(20L).retryAllowed()).isFalse();verifyNoInteractions(git);
  }
  @Test void changedCurrentScopeAlsoHolds(){env.setServerIds(List.of(3L,4L,5L));service.ensureForSuccessfulDeployment(release);assertThat(records.get(1L).getStatus()).isEqualTo(NEEDS_ATTENTION);}
  @Test void scopeChangedWhileQueuedIsRecheckedBeforePush(){service.ensureForSuccessfulDeployment(release);env.setServerIds(List.of(3L,4L,5L));service.execute(1L);assertThat(records.get(1L).getReason()).isEqualTo("PARTIAL_PRODUCTION");verifyNoInteractions(git);}
  @Test void legacyArtifactsWithoutProofAreNotSilentlyMerged(){when(checks.findFirstByTaskIdOrderByIdDesc(10L)).thenReturn(Optional.empty());service.ensureForSuccessfulDeployment(release);assertThat(records.get(1L).getReason()).isEqualTo("NO_BUILD_PROOF");assertThatThrownBy(()->service.retry(20L)).hasMessageContaining("人工核对");verifyNoInteractions(git);}
  @Test void sharedRepositoryIsHeldEvenWithDifferentUrlTransport(){
    AppService other=new AppService();id(other,2L);other.setGitRepo("https://example.invalid/team/app.git");when(services.findAll()).thenReturn(List.of(app,other));service.ensureForSuccessfulDeployment(release);assertThat(records.get(1L).getReason()).isEqualTo("SHARED_REPOSITORY");
  }
  @Test void gitFailureDoesNotChangeDeploymentAndRetryOnlyRetriesMerge()throws Exception{
    when(git.mergeFixedCommit(REPO,SHA)).thenThrow(new PolicyGitRepository.GitPolicyException("PUSH_REJECTED","请检查分支保护"));service.ensureForSuccessfulDeployment(release);service.execute(1L);assertThat(release.getStatus()).isEqualTo(TaskStatus.SUCCESS);assertThat(service.get(20L).retryAllowed()).isTrue();
    assertThat(service.retry(20L).status()).isEqualTo("QUEUED");doReturn(new PolicyGitRepository.MergeResult("ALREADY_MERGED",SHA,SHA)).when(git).mergeFixedCommit(REPO,SHA);service.execute(1L);assertThat(records.get(1L).getStatus()).isEqualTo(ALREADY_MERGED);assertThat(records.get(1L).getAttempts()).isEqualTo(2);
  }
  @Test void interruptedPushCanBeSafelyRecoveredAndRechecked(){service.ensureForSuccessfulDeployment(release);records.get(1L).start();service.recoverInterrupted();assertThat(records.get(1L).getStatus()).isEqualTo(QUEUED);verifyNoInteractions(git);}
  @Test void enqueueFailureLeavesSuccessFlagForRecovery(){
    doThrow(new IllegalStateException("db temporarily down")).when(merges).saveAndFlush(any());assertThatThrownBy(()->service.ensureForSuccessfulDeployment(release)).isInstanceOf(IllegalStateException.class);assertThat(release.isMergeEnqueuePending()).isTrue();assertThat(release.getStatus()).isEqualTo(TaskStatus.SUCCESS);
  }
  private static void id(Object object,Long id){ReflectionTestUtils.setField(object,"id",id);}
}
