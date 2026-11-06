package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.security.CredentialSecretCrypto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;
import static com.local.release.domain.ProductionMerge.Status.*;

@Service
public class ProductionMergeService {
  private final ProductionMergeRepository merges;private final BranchCheckRepository checks;
  private final ArtifactRepository artifacts;private final ReleaseTaskRepository tasks;
  private final AppServiceRepository services;private final ServiceEnvironmentRepository environments;
  private final PolicyGitRepository git;private final CredentialSecretCrypto crypto;
  public ProductionMergeService(ProductionMergeRepository merges,BranchCheckRepository checks,ArtifactRepository artifacts,ReleaseTaskRepository tasks,AppServiceRepository services,ServiceEnvironmentRepository environments,PolicyGitRepository git,CredentialSecretCrypto crypto){this.merges=merges;this.checks=checks;this.artifacts=artifacts;this.tasks=tasks;this.services=services;this.environments=environments;this.git=git;this.crypto=crypto;}

  /** No network here. The success flag and durable queue are reconciled without changing deployment result. */
  @Transactional
  public synchronized void ensureForSuccessfulDeployment(ReleaseTask supplied){
    if(supplied==null||supplied.getId()==null)return;
    ReleaseTask task=tasks.findById(supplied.getId()).orElse(supplied);
    if(!eligible(task))return;
    if(merges.findByReleaseTaskId(task.getId()).isEmpty()){
      ProductionMerge merge=new ProductionMerge(task.getId(),task.getServiceId(),task.getCommitId());
      Artifact artifact=task.getArtifactId()==null?null:artifacts.findById(task.getArtifactId()).orElse(null);
      if(artifact==null||!Objects.equals(artifact.getServiceId(),task.getServiceId())||!Objects.equals(artifact.getCommitId(),task.getCommitId()))hold(merge,"ARTIFACT_MISMATCH","生产发布成功，但制品与发布 Commit 不一致，不能自动合回；请人工核对");
      else{
        BranchCheck proof=checks.findFirstByTaskIdOrderByIdDesc(artifact.getBuildTaskId()).orElse(null);
        if(proof==null||proof.getStatus()!=BranchCheck.Status.PASSED||!Objects.equals(proof.getServiceId(),task.getServiceId())||!Objects.equals(proof.getBuildSha(),task.getCommitId())||proof.getRepositoryEncrypted()==null)hold(merge,"NO_BUILD_PROOF","生产发布成功；此制品没有新版分支校验记录，请人工核对仓库并合回，或重新打包发布");
        else{
          merge.proof(proof);
          String incomplete=productionCoverage(task);
          if(incomplete!=null)hold(merge,"PARTIAL_PRODUCTION",incomplete);
          else if(sharedRepository(task.getServiceId(),proof.getRepositoryIdentity()))hold(merge,"SHARED_REPOSITORY","生产发布成功；此仓库由多个服务共用，不能代表全部服务已上线，请按仓库整体核对后人工合回");
        }
      }
      merges.saveAndFlush(merge);
    }
    if(task.isMergeEnqueuePending()){task.setMergeEnqueuePending(false);tasks.saveAndFlush(task);}
  }
  public List<ReleaseTask> pendingSuccesses(){return tasks.findTop50ByMergeEnqueuePendingTrueOrderByIdAsc();}
  public synchronized void recoverInterrupted(){
    // A push may have succeeded before shutdown. Re-fetch and ancestor detection make replay harmless.
    for(ProductionMerge merge:merges.findByStatus(RUNNING)){merge.update(QUEUED,"RECOVERED","平台重启，等待核对远程结果后继续合回");merges.save(merge);}
  }
  public synchronized Long nextId(){return merges.findFirstByStatusOrderByIdAsc(QUEUED).map(ProductionMerge::getId).orElse(null);}
  public void execute(Long id){
    ProductionMerge merge=merges.findById(id).orElse(null);if(merge==null||merge.getStatus()!=QUEUED)return;
    ReleaseTask task=tasks.findById(merge.getReleaseTaskId()).orElse(null);
    if(task==null||!eligible(task)||!Objects.equals(task.getCommitId(),merge.getCommitId())){hold(merge,"RELEASE_CHANGED","发布记录已变化，停止自动合回，请人工核对");merges.saveAndFlush(merge);return;}
    String incomplete=productionCoverage(task);
    if(incomplete!=null){hold(merge,"PARTIAL_PRODUCTION",incomplete);merges.saveAndFlush(merge);return;}
    if(sharedRepository(merge.getServiceId(),merge.getRepositoryIdentity())){hold(merge,"SHARED_REPOSITORY","此仓库由多个服务共用，请核对全部服务的生产版本后人工合回");merges.saveAndFlush(merge);return;}
    merge.start();merges.saveAndFlush(merge);
    try{
      String repository=crypto.decrypt(merge.getRepositoryEncrypted());
      if(!Objects.equals(PolicyGitRepository.identity(repository),merge.getRepositoryIdentity()))throw new IllegalStateException("provenance mismatch");
      var result=git.mergeFixedCommit(repository,merge.getCommitId());merge.baselines(result.before(),result.after());
      merge.update("MERGED".equals(result.outcome())?MERGED:ALREADY_MERGED,result.outcome(),"MERGED".equals(result.outcome())?"已将实际发布的 Commit 合回 master，并核对远程基线":"master 已包含此版本，无需重复合并或回退 master");
    }catch(Exception error){
      if(error instanceof InterruptedException)Thread.currentThread().interrupt();
      String reason=error instanceof PolicyGitRepository.GitPolicyException known?known.code():"GIT_FAILED";
      String message=error instanceof PolicyGitRepository.GitPolicyException?error.getMessage():"生产发布成功，代码合回未完成；请检查仓库授权和网络后重试合回";
      hold(merge,reason,message);
    }
    merges.saveAndFlush(merge);
  }
  public synchronized MergeView retry(Long taskId){
    ProductionMerge merge=merges.findByReleaseTaskId(taskId).orElseThrow(()->new IllegalArgumentException("尚无代码合回记录"));
    if(merge.getStatus()==QUEUED||merge.getStatus()==RUNNING||merge.getStatus()==MERGED||merge.getStatus()==ALREADY_MERGED)return view(merge);
    if(!retryAllowed(merge))throw new IllegalArgumentException("此记录需要人工核对，不能自动重试："+merge.getMessage());
    merge.update(QUEUED,"QUEUED","等待重新核对远程 master 并重试合回；不会重新发布服务");merges.saveAndFlush(merge);return view(merge);
  }
  public MergeView get(Long taskId){
    ReleaseTask task=tasks.findById(taskId).orElseThrow(()->new IllegalArgumentException("发布记录不存在"));
    return merges.findByReleaseTaskId(taskId).map(this::view).orElseGet(()->new MergeView(null,taskId,task.getCommitId(),"prod".equals(task.getEnvironmentName())?"NOT_CREATED":"NOT_APPLICABLE",null,"prod".equals(task.getEnvironmentName())?"生产发布成功后自动合回；打包成功或下载完成不会触发":"此环境只记录发布结果，不合回 master",null,null,0,false,null,null));
  }
  public record MergeView(Long id,Long releaseTaskId,String commitId,String status,String reason,String message,String baselineBefore,String baselineAfter,int attempts,boolean retryAllowed,LocalDateTime startedAt,LocalDateTime finishedAt){}
  private MergeView view(ProductionMerge m){return new MergeView(m.getId(),m.getReleaseTaskId(),m.getCommitId(),m.getStatus().name(),m.getReason(),m.getMessage(),m.getBaselineBefore(),m.getBaselineAfter(),m.getAttempts(),retryAllowed(m),m.getStartedAt(),m.getFinishedAt());}
  private boolean retryAllowed(ProductionMerge m){return m.getStatus()==NEEDS_ATTENTION&&!Set.of("ARTIFACT_MISMATCH","NO_BUILD_PROOF","PARTIAL_PRODUCTION","SHARED_REPOSITORY","RELEASE_CHANGED").contains(m.getReason());}
  private boolean eligible(ReleaseTask task){return task.getTaskKind()==TaskKind.DEPLOY&&task.getStatus()==TaskStatus.SUCCESS&&!task.isSimulate()&&"prod".equals(task.getEnvironmentName());}
  private String productionCoverage(ReleaseTask task){
    if("MOBILE".equals(task.getDeliveryType()))return null;
    if(!Boolean.TRUE.equals(task.getProductionScopeComplete()))return "生产发布成功，但提交时未覆盖完整生产集群；请完成全部生产节点发布后合回";
    ServiceEnvironment environment=environments.findByServiceIdAndEnvironmentName(task.getServiceId(),"prod").orElse(null);
    if(environment==null||!environment.isEnabled()||environment.getServerIds().isEmpty())return "生产发布成功，但无法确认完整生产集群范围；请人工核对后合回";
    Set<Long> configured=new HashSet<>(environment.getServerIds()),deployed=new HashSet<>(task.targetServerIdList());
    return configured.equals(deployed)?null:"生产发布成功，但本次只覆盖部分生产节点；为避免提前更新 master，请完成全部生产节点发布后合回";
  }
  private boolean sharedRepository(Long owner,String identity){
    if(identity==null)return true;
    for(AppService other:services.findAll())if(other.isEnabled()&&!Objects.equals(other.getId(),owner)&&other.getGitRepo()!=null&&!other.getGitRepo().isBlank()){
      try{if(identity.equals(PolicyGitRepository.identity(other.getGitRepo())))return true;}catch(IllegalArgumentException ignored){}
    }
    return false;
  }
  private void hold(ProductionMerge merge,String reason,String message){merge.update(NEEDS_ATTENTION,reason,message);}
}
