package com.local.release.miniprogram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.service.PolicyGitRepository;
import com.local.release.service.ReleaseExecutor;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static com.local.release.miniprogram.MiniProgramJob.*;

@Service
public class MiniProgramService {
  private final MiniProgramSettings settings;
  private final MiniProgramProjectRepository projects;
  private final MiniProgramJobRepository jobs;
  private final MiniProgramAccess access;
  private final MiniProgramGit git;
  private final PolicyGitRepository policy;
  private final MiniProgramRunner runner;
  private final ReleaseExecutor serial;
  private final ObjectMapper mapper;
  private final MiniProgramReadiness readiness;
  private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20),
      Thread.ofPlatform().daemon().name("miniprogram-worker-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
  private final Map<Long,Execution> executions=new HashMap<>();
  private boolean stopping;
  private static final class Execution { Thread owner; boolean cancelled,finished; }
  public record Request(Long projectId,Action action,String branch,String commitId,String version,String note,String requestId){}
  public record Reconciliation(Resolution resolution,String note){}
  public record History(List<JobSummary> items,long total,int page,int size){}
  public static class Conflict extends IllegalArgumentException { public Conflict(String message){super(message);} }
  public record JobSummary(Long id,Long projectId,String projectName,String appId,Action action,String branch,String commitId,
      String version,String createdBy,Status status,String stage,java.time.LocalDateTime createdAt,java.time.LocalDateTime startedAt,
      java.time.LocalDateTime finishedAt,String errorMessage,Resolution resolution,String resolvedBy,java.time.LocalDateTime resolvedAt,String resolutionNote) {
    public static JobSummary of(MiniProgramJob j){return new JobSummary(j.getId(),j.getProjectId(),j.getProjectName(),j.getAppId(),j.getAction(),j.getBranch(),j.getCommitId(),j.getVersion(),j.getCreatedBy(),j.getStatus(),j.getStage(),j.getCreatedAt(),j.getStartedAt(),j.getFinishedAt(),j.getErrorMessage(),j.getResolution(),j.getResolvedBy(),j.getResolvedAt(),j.getResolutionNote());}
  }
  public MiniProgramService(MiniProgramSettings settings,MiniProgramProjectRepository projects,MiniProgramJobRepository jobs,
      MiniProgramAccess access,MiniProgramGit git,PolicyGitRepository policy,MiniProgramRunner runner,ReleaseExecutor serial,ObjectMapper mapper,MiniProgramReadiness readiness){
    this.settings=settings;this.projects=projects;this.jobs=jobs;this.access=access;this.git=git;this.policy=policy;this.runner=runner;this.serial=serial;this.mapper=mapper;this.readiness=readiness;
  }
  private void allowed(String actor){settings.requireEnabled();access.require(actor,false);}
  public List<MiniProgramProject> projects(String actor){allowed(actor);return projects.findAllByOrderByCreatedAtDescIdDesc();}
  public synchronized MiniProgramProject save(Long id,MiniProgramConfiguration config,String actor){
    allowed(actor);access.require(actor,true);
    MiniProgramProject project=id==null?new MiniProgramProject():project(id);
    if(id!=null&&!project.getAppId().equals(config.appId()))throw new IllegalArgumentException("已登记项目的 AppID 不可修改，请登记新项目");
    project.apply(config);return projects.saveAndFlush(project);
  }
  public List<String> branches(Long id,String actor)throws Exception {allowed(actor);return git.branches(project(id).configuration());}
  public MiniProgramGit.Commit latest(Long id,String branch,String actor)throws Exception {allowed(actor);return git.latest(project(id),branch);}
  public List<JobSummary> list(Long id,String actor){allowed(actor);project(id);return jobs.findByProjectIdOrderByCreatedAtDescIdDesc(id,PageRequest.of(0,50)).stream().map(JobSummary::of).toList();}
  public MiniProgramReadiness.Report readiness(Long id,String actor)throws InterruptedException {
    allowed(actor);var report=readiness.inspect(project(id).configuration());
    var previous=jobs.findFirstByProjectIdAndActionAndStatusAndResolutionIsNullOrderByCreatedAtAscIdAsc(id,Action.UPLOAD,Status.UNKNOWN);
    if(previous.isEmpty())return report;
    var checks=new ArrayList<>(report.checks());checks.add(new MiniProgramReadiness.Check("upload-history","上次上传结果","INFO","上传任务 #"+previous.get().getId()+" 尚未核对。请管理员核对微信后台并登记结果；当前仍可生成预览码"));
    return new MiniProgramReadiness.Report(report.localReady(),report.previewReady(),false,report.checkedAt(),List.copyOf(checks));
  }
  public History history(Long id,Action action,Status status,boolean unresolved,int page,int size,String actor){
    allowed(actor);project(id);
    if(page<1||page>100000||size<1||size>50)throw new IllegalArgumentException("分页参数无效，每页最多 50 条");
    Specification<MiniProgramJob> filter=(root,query,cb)->{
      var clauses=new ArrayList<jakarta.persistence.criteria.Predicate>();clauses.add(cb.equal(root.get("projectId"),id));
      if(action!=null)clauses.add(cb.equal(root.get("action"),action));
      if(status!=null)clauses.add(cb.equal(root.get("status"),status));
      if(unresolved){clauses.add(cb.equal(root.get("status"),Status.UNKNOWN));clauses.add(cb.equal(root.get("action"),Action.UPLOAD));clauses.add(cb.isNull(root.get("resolution")));}
      return cb.and(clauses.toArray(jakarta.persistence.criteria.Predicate[]::new));
    };
    var result=jobs.findAll(filter,PageRequest.of(page-1,size,Sort.by(Sort.Direction.DESC,"createdAt","id")));
    return new History(result.getContent().stream().map(JobSummary::of).toList(),result.getTotalElements(),page,size);
  }
  public synchronized MiniProgramJob reconcile(Long id,Reconciliation input,String actor){
    allowed(actor);access.require(actor,true);
    if(input==null||input.resolution()==null)throw new IllegalArgumentException("请选择核对结果");
    String note=Objects.toString(input.note(),"").trim();
    if(note.isBlank()||note.length()>500||note.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw new IllegalArgumentException("请填写 500 字以内的核对说明");
    MiniProgramJob job=job(id);
    if(job.getStatus()!=Status.UNKNOWN||job.getAction()!=Action.UPLOAD)throw new Conflict("只有结果待核对的上传任务可以登记核对结果");
    if(job.getResolution()!=null){
      if(job.getResolution()==input.resolution()&&Objects.equals(job.getResolutionNote(),note)&&Objects.equals(job.getResolvedBy(),actor))return job;
      throw new Conflict("此任务已经登记核对结果，请刷新查看；原核对记录不能覆盖");
    }
    job.reconcile(input.resolution(),actor,note);
    job.log("管理员 "+actor+" 已登记微信核对结果；原执行状态保持不变，本次没有重新上传");
    return jobs.saveAndFlush(job);
  }
  public MiniProgramJob detail(Long id,String actor){allowed(actor);return job(id);}
  public byte[] preview(Long id,String actor)throws Exception {
    MiniProgramJob job=detail(id,actor);
    if(job.getAction()!=Action.PREVIEW||job.getStatus()!=Status.SUCCESS)throw new IllegalArgumentException("此任务尚未生成可用预览码");
    return runner.preview(id);
  }
  public synchronized MiniProgramJob create(Request input,String actor)throws Exception {
    allowed(actor);if(stopping)throw new IllegalArgumentException("平台正在停止，请稍后重试");
    if(input.projectId()==null||input.action()==null)throw new IllegalArgumentException("请选择小程序和操作类型");
    String branch=MiniProgramConfiguration.branch(input.branch()),sha=MiniProgramConfiguration.commit(input.commitId()),version=MiniProgramConfiguration.version(input.version());
    String note=Objects.toString(input.note(),"").trim();if(note.length()>500||note.indexOf('\0')>=0)throw new IllegalArgumentException("版本说明不能超过 500 字");
    if(input.requestId()==null||!input.requestId().matches("[A-Za-z0-9_-]{16,80}"))throw new IllegalArgumentException("请求标识不正确，请重新打开构建窗口");
    String key=actor+":"+input.requestId();
    String fingerprint=hash(mapper.writeValueAsString(List.of(input.projectId(),input.action(),branch,sha,version,note)));
    MiniProgramJob previous=jobs.findByRequestKey(key).orElse(null);
    if(previous!=null){if(!fingerprint.equals(previous.getFingerprint()))throw new IllegalArgumentException("请求标识已用于其他构建配置");return previous;}
    MiniProgramProject project=project(input.projectId());MiniProgramConfiguration config=project.configuration();
    if(!config.enabled())throw new IllegalArgumentException("此小程序尚未启用，请先完成配置");
    requireResolvedUploads(project.getId(),input.action());
    runner.preflight(config,input.action());
    if(executions.size()>=20)throw new IllegalArgumentException("小程序排队任务已满，请等待现有任务完成");
    MiniProgramJob created=jobs.saveAndFlush(new MiniProgramJob(project,input.action(),branch,sha,version,note,actor,
      mapper.writeValueAsString(config),key,fingerprint));
    created.log("已保存代码与配置快照，等待公共执行器；关闭页面也会继续执行");jobs.saveAndFlush(created);
    executions.put(created.getId(),new Execution());
    try{worker.execute(()->execute(created));}
    catch(RejectedExecutionException error){executions.remove(created.getId());created.finish(Status.FAILED,"执行队列暂不可用，请稍后新建任务");jobs.saveAndFlush(created);}
    return created;
  }
  private void execute(MiniProgramJob job){
    try{
      synchronized(this){Execution e=executions.get(job.getId());if(e==null||e.finished)return;e.owner=Thread.currentThread();checkpoint(job);}
      serial.executeCancellableRuntimeOperation("小程序 "+job.getProjectName()+" · "+job.getCreatedBy(),()->{
        try{
          synchronized(this){checkpoint(job);job.start();jobs.saveAndFlush(job);}
          allowed(job.getCreatedBy());
          if(!project(job.getProjectId()).isEnabled())throw new IllegalArgumentException("小程序已停用，本次操作已中止");
          requireResolvedUploads(job.getProjectId(),job.getAction());
          MiniProgramConfiguration snapshot=mapper.readValue(job.getConfigurationSnapshot(),MiniProgramConfiguration.class);
          runner.preflight(snapshot,job.getAction());
          stage(job,"BASELINE_CHECK","校验基准分支 master：必须包含远程最新 Commit");
          var baseline=policy.check(snapshot.gitRepo(),job.getCommitId());
          synchronized(this){checkpoint(job);job.baseline(baseline.baselineSha());jobs.saveAndFlush(job);}
          if(!baseline.containsBaseline())throw new IllegalArgumentException("当前提交未包含最新 master，请先同步基准分支后重新构建");
          runner.execute(job,snapshot,(s,message)->stage(job,s,message));
          synchronized(this){checkpoint(job);job.finish(Status.SUCCESS,null);jobs.saveAndFlush(job);executions.get(job.getId()).finished=true;}
        }catch(Exception error){finishFailure(job,error);}
      });
    }catch(Exception error){finishFailure(job,error);}
    finally{synchronized(this){Execution e=executions.remove(job.getId());if(e!=null&&e.owner==Thread.currentThread())Thread.interrupted();}}
  }
  private synchronized void stage(MiniProgramJob job,String stage,String message){checkpoint(job);job.stage(stage);job.log(message);jobs.saveAndFlush(job);}
  private void checkpoint(MiniProgramJob job){Execution e=executions.get(job.getId());if(stopping||e==null||e.cancelled||Thread.currentThread().isInterrupted())throw new CancellationException("操作已取消");}
  private synchronized void finishFailure(MiniProgramJob job,Exception error){
    Execution e=executions.get(job.getId());if(e==null||e.finished)return;
    boolean uncertain=(job.getStage().equals("WECHAT_TRANSFER")||job.getStage().equals("COMPLETE"))&&!(error instanceof MiniProgramRunner.RemoteRejected);
    boolean cancelled=e.cancelled||stopping||error instanceof CancellationException||error instanceof InterruptedException;
    String message=uncertain?(job.getAction()==Action.UPLOAD?"微信操作结果待核对；可能已上传，请先查看微信后台，本任务不会自动重传":"预览结果未能确认；本任务不会自动重试，可以重新创建预览任务")
      :cancelled?"任务已取消":error instanceof IllegalArgumentException||error instanceof PolicyGitRepository.GitPolicyException?error.getMessage():"任务执行失败，请检查构建机配置与网络后新建任务";
    if(uncertain&&error instanceof IllegalArgumentException)message+="；"+error.getMessage();
    job.finish(uncertain?Status.UNKNOWN:cancelled?Status.CANCELLED:Status.FAILED,message);job.log(message);jobs.saveAndFlush(job);e.finished=true;
  }
  public synchronized MiniProgramJob cancel(Long id,String actor){
    allowed(actor);MiniProgramJob stored=job(id);
    if(!stored.getCreatedBy().equals(actor)&&!access.require(actor,false).isAdmin())throw new AccessDeniedException("仅发起人或管理员可以取消任务");
    if(stored.getStatus()!=Status.PENDING&&stored.getStatus()!=Status.RUNNING)return stored;
    Execution e=executions.get(id);
    if(e==null)throw new IllegalArgumentException("任务不在当前执行器中，请刷新后核对状态");
    e.cancelled=true;
    if(e.owner!=null)e.owner.interrupt();
    else {e.finished=true;stored.finish(Status.CANCELLED,"任务在开始前已取消");jobs.saveAndFlush(stored);}
    return stored;
  }
  @EventListener(ApplicationReadyEvent.class)
  public synchronized void recover(){
    // Never repeat a WeChat write after restart, even when the menu is disabled.
    for(MiniProgramJob job:jobs.findByStatusIn(List.of(Status.PENDING,Status.RUNNING))){
      boolean uncertain=Set.of("WECHAT_TRANSFER","COMPLETE").contains(job.getStage());
      job.finish(uncertain?Status.UNKNOWN:Status.FAILED,uncertain?(job.getAction()==Action.UPLOAD?"平台曾中断，微信结果待核对；请先查看微信后台，不会自动重传":"平台曾中断，预览结果未能确认；可以重新创建预览任务，不会自动重试"):"平台曾中断，请确认代码后重新创建任务");
      jobs.saveAndFlush(job);
    }
  }
  @PreDestroy public synchronized void stop(){stopping=true;executions.values().forEach(e->{e.cancelled=true;if(e.owner!=null)e.owner.interrupt();});worker.shutdownNow();}
  private MiniProgramProject project(Long id){if(id==null)throw new IllegalArgumentException("请选择小程序");return projects.findById(id).orElseThrow(()->new IllegalArgumentException("小程序不存在"));}
  private void requireResolvedUploads(Long projectId,Action action){
    if(action==Action.UPLOAD)jobs.findFirstByProjectIdAndActionAndStatusAndResolutionIsNullOrderByCreatedAtAscIdAsc(projectId,Action.UPLOAD,Status.UNKNOWN)
      .ifPresent(previous->{throw new Conflict("此小程序的上传任务 #"+previous.getId()+" 结果待核对，请管理员先核对微信后台并登记结果，再上传新版本");});
  }
  private MiniProgramJob job(Long id){return jobs.findById(id).orElseThrow(()->new IllegalArgumentException("小程序任务不存在"));}
  private static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
}
