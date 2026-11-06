package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.BranchCheckRepository;
import com.local.release.security.CredentialSecretCrypto;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Consumer;

@Service
public class BranchPolicyService {
  private final BranchCheckRepository checks;private final PolicyGitRepository git;private final CredentialSecretCrypto crypto;
  public BranchPolicyService(BranchCheckRepository checks,PolicyGitRepository git,CredentialSecretCrypto crypto){this.checks=checks;this.git=git;this.crypto=crypto;}
  public BranchCheck check(AppService service,ReleaseTask task,Consumer<String> log){
    if(task.getTaskKind()!=ModelEnums.TaskKind.BUILD||!Objects.equals(service.getId(),task.getServiceId()))throw new IllegalArgumentException("分支校验只适用于对应服务的打包任务");
    PolicyGitRepository.validateSha(task.getCommitId());
    BranchCheck check=new BranchCheck(task.getId(),task.getServiceId(),task.getCommitId());
    String encrypted=null,identity=null;boolean passed=false;
    try{
      String repository=service.getGitRepo();identity=PolicyGitRepository.identity(repository);encrypted=crypto.encrypt(repository);
      BranchCheck firstPassed=checks.findFirstByTaskIdAndStatusOrderByIdAsc(task.getId(),BranchCheck.Status.PASSED).orElse(null);
      if(firstPassed!=null&&!Objects.equals(firstPassed.getRepositoryIdentity(),identity))throw new PolicyGitRepository.GitPolicyException("REPOSITORY_CHANGED","此打包任务的 Git 仓库配置已变化，请新建打包任务；不会复用旧构建步骤向新仓库合回代码");
      if(log!=null)log.accept("校验打包分支：读取远程 master，并检查锁定的 Commit 是否包含最新基线");
      var result=git.check(repository,task.getCommitId());passed=result.containsBaseline();
      check.complete(result.baselineSha(),passed?BranchCheck.Status.PASSED:BranchCheck.Status.BLOCKED,passed?"已包含远程 master 最新 Commit":"当前版本未包含最新 master，请先同步 master 后重新打包",encrypted,identity);
    }catch(Exception error){
      if(error instanceof InterruptedException)Thread.currentThread().interrupt();
      String message=error instanceof PolicyGitRepository.GitPolicyException?error.getMessage():"分支校验未完成，请检查 Git 仓库配置、网络和授权；本次已阻止打包";
      check.complete(null,BranchCheck.Status.ERROR,message,encrypted,identity);
    }
    check=checks.saveAndFlush(check);
    if(log!=null)log.accept(check.getMessage()+(check.getBaselineSha()==null?"":"；master "+check.getBaselineSha().substring(0,8)+"，构建 "+task.getCommitId().substring(0,8)));
    if(!passed)throw new IllegalStateException(check.getMessage());
    return check;
  }
  public List<BranchCheck> list(Long taskId){return checks.findByTaskIdOrderByIdDesc(taskId);}
}
