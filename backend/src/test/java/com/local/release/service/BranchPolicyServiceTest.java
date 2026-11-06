package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.BranchCheckRepository;
import com.local.release.security.CredentialSecretCrypto;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BranchPolicyServiceTest {
  BranchCheckRepository checks=mock(BranchCheckRepository.class);PolicyGitRepository git=mock(PolicyGitRepository.class);CredentialSecretCrypto crypto=mock(CredentialSecretCrypto.class);
  BranchPolicyService policy=new BranchPolicyService(checks,git,crypto);AppService app;ReleaseTask task;List<BranchCheck> records=new ArrayList<>();
  final String SHA="a".repeat(40),MASTER="b".repeat(40);
  @BeforeEach void setup(){
    app=new AppService();app.setGitRepo("https://username:secret@example.invalid/team/app.git");ReflectionTestUtils.setField(app,"id",1L);
    task=new ReleaseTask(1L,TaskKind.BUILD,null,"build","release",null,false);task.setCommitId(SHA);ReflectionTestUtils.setField(task,"id",10L);
    when(crypto.encrypt(app.getGitRepo())).thenReturn("encrypted-repository");when(checks.saveAndFlush(any())).thenAnswer(call->{BranchCheck c=call.getArgument(0);records.add(c);return c;});
  }
  @Test void retryRechecksBaselineInsteadOfReusingPreviousPass()throws Exception{
    when(git.check(app.getGitRepo(),SHA)).thenReturn(new PolicyGitRepository.CheckResult(MASTER,true),new PolicyGitRepository.CheckResult("c".repeat(40),false));
    BranchCheck first=policy.check(app,task,null);assertThat(first.getStatus()).isEqualTo(BranchCheck.Status.PASSED);
    assertThatThrownBy(()->policy.check(app,task,null)).hasMessageContaining("同步 master");assertThat(records).hasSize(2);assertThat(records.get(1).getStatus()).isEqualTo(BranchCheck.Status.BLOCKED);verify(git,times(2)).check(app.getGitRepo(),SHA);
  }
  @Test void remoteFailureIsPersistedAndNeverLeaksRepositorySecrets()throws Exception{
    when(git.check(app.getGitRepo(),SHA)).thenThrow(new IllegalStateException("https://username:secret@example.invalid password=raw"));List<String> logs=new ArrayList<>();
    assertThatThrownBy(()->policy.check(app,task,logs::add)).hasMessageContaining("已阻止打包").hasMessageNotContaining("secret");
    assertThat(records).hasSize(1);assertThat(records.getFirst().getStatus()).isEqualTo(BranchCheck.Status.ERROR);assertThat(records.getFirst().getRepositoryEncrypted()).isEqualTo("encrypted-repository");assertThat(String.join("\n",logs)).doesNotContain("secret","username");
  }
  @Test void cannotCheckDifferentServiceOrDeployment(){
    ReflectionTestUtils.setField(app,"id",2L);assertThatThrownBy(()->policy.check(app,task,null)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(git,checks);
  }
  @Test void changingRepositoryDuringRetryCannotReplaceOriginalProvenance(){
    BranchCheck original=new BranchCheck(10L,1L,SHA);original.complete(MASTER,BranchCheck.Status.PASSED,"ok","first",PolicyGitRepository.identity("git@example.invalid:original/repo.git"));when(checks.findFirstByTaskIdAndStatusOrderByIdAsc(10L,BranchCheck.Status.PASSED)).thenReturn(Optional.of(original));
    assertThatThrownBy(()->policy.check(app,task,null)).hasMessageContaining("新建打包任务");verifyNoInteractions(git);assertThat(records.getFirst().getStatus()).isEqualTo(BranchCheck.Status.ERROR);
  }
}
