package com.local.release.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.local.release.domain.*;
import com.local.release.repo.FrontendCloudBuildRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Offloads Node compilation; the existing image, registry and deployment pipeline follows verified output. */
@Service
public class GitHubFrontendBuildService {
  private static final ObjectMapper JSON=new ObjectMapper();
  static final long MAX_ARTIFACT_BYTES=1024L*1024*1024;
  static final int RUN_METADATA_GRACE_SECONDS=180;
  private final FrontendCloudSettings settings;
  private final GitHubActionsClient api;
  private final FrontendCloudBuildRepository journal;
  private LocalExecutionGate localGate=new LocalExecutionGate();
  @org.springframework.beans.factory.annotation.Autowired void setLocalGate(LocalExecutionGate value){localGate=value;}
  private volatile boolean detaching;
  @org.springframework.beans.factory.annotation.Autowired
  public GitHubFrontendBuildService(FrontendCloudSettings settings,FrontendCloudBuildRepository journal){this(settings,new GitHubActionsClient(settings.tokenFile),journal);}
  GitHubFrontendBuildService(FrontendCloudSettings settings,GitHubActionsClient api,FrontendCloudBuildRepository journal){this.settings=settings;this.api=api;this.journal=journal;}
  public String engineForNewTask(AppService service){return settings.engineForNewTask(service);}
  public static boolean usesGitHub(ReleaseTask task){return task.getTaskKind()==com.local.release.domain.ModelEnums.TaskKind.BUILD&&"GITHUB".equals(task.getFrontendBuildEngine());}
  public void detach(){detaching=true;}
  @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
  public void closing(){detach();}
  public boolean isDetaching(){return detaching;}
  public boolean continuesOriginalRun(ReleaseTask task){return journal.findById(task.getId()).map(e->!"READY".equals(e.getState())).orElse(false);}

  /** Called inside task creation's transaction, together with version allocation. */
  public void prepare(ReleaseTask task,AppService service){
    api.checkCredential();var config=FrontendBuildConfiguration.from(service,settings);
    journal.save(new FrontendCloudBuild(task.getId(),settings.repository,settings.workflow,settings.ref,config.sourceRepository(),UUID.randomUUID().toString(),config.json()));
  }
  public void validateTask(ReleaseTask task,AppService service){FrontendBuildConfiguration.parse(get(task.getId()).getBuildConfigSnapshot()).verifyService(service);}
  private FrontendCloudBuild get(Long taskId){return journal.findById(taskId).orElseThrow(()->new IllegalStateException("GitHub 构建任务记录不存在，请新建任务"));}
  private synchronized FrontendCloudBuild update(Long id,Consumer<FrontendCloudBuild> change){var entry=get(id);change.accept(entry);return journal.saveAndFlush(entry);}
  private String workflowPath(FrontendCloudBuild entry){return "/repos/"+entry.getRepository()+"/actions/workflows/"+entry.getWorkflow();}
  private String runPath(FrontendCloudBuild entry){return "/repos/"+entry.getRepository()+"/actions/runs/"+entry.getRunId();}
  public String runUrl(Long taskId){var entry=journal.findById(taskId).orElse(null);return entry==null||entry.getRunId()==null?null:"https://github.com/"+entry.getRepository()+"/actions/runs/"+entry.getRunId();}

  public String build(ReleaseTask task,Path source,Consumer<String> log)throws Exception{
    var entry=get(task.getId());
    var config=FrontendBuildConfiguration.parse(entry.getBuildConfigSnapshot());
    config.nginxSourcePath(); // Reject output/source overlap before fetching an artifact for a persisted task.
    Path project=source.resolve(config.workDirectory()).toAbsolutePath().normalize();
    FlutterBuildService.ensureContained(source,project);FlutterBuildService.rejectSymlinks(source,project);
    if(!Files.isDirectory(project))throw new IllegalStateException("前端工作目录不存在");
    Consumer<String> output=log==null?line->{}:log;
    try{
      if("READY".equals(entry.getState())){
        ObjectNode payload=JSON.createObjectNode();payload.put("schema",1);payload.put("request_id",entry.getRequestId());payload.put("task_id",task.getId());payload.put("service_id",task.getServiceId());
        payload.put("source_repository",entry.getSourceRepository());payload.put("commit_id",task.getCommitId());payload.put("config_sha256",config.hash());payload.set("config",JSON.valueToTree(config));
        if(payload.toString().length()>20000)throw new IllegalArgumentException("GitHub 构建配置过大");
        // Commit intent BEFORE network I/O. A response loss or crash must never trigger another dispatch.
        entry=update(task.getId(),e->e.dispatching(Instant.now(),settings.totalTimeoutSeconds));
        output.accept("已提交 GitHub 前端云构建；Node "+config.nodeVersion()+" · Commit "+task.getCommitId());
        try{
          var response=api.request("POST",workflowPath(entry)+"/dispatches",Map.of("ref",entry.getWorkflowRef(),"inputs",Map.of("request_id",entry.getRequestId(),"payload",payload.toString())));
          long runId=response.body().path("workflow_run_id").asLong(0);
          if(runId>0){final long id=runId;entry=update(task.getId(),e->e.attach(id));}
        }catch(IOException uncertain){
          output.accept("提交响应未确认，正在按任务标识查找原 GitHub 运行；不会重复提交。");
          if(uncertain instanceof GitHubActionsClient.ApiException known&&Set.of(401,403,404,422).contains(known.status)){update(task.getId(),e->e.completed("dispatch_rejected"));throw uncertain;}
        }
      }
      String lastStatus="";long lastReport=0;int failures=0;
      while(true){
        if(detaching)throw new DetachedException();
        entry=get(task.getId());
        if(Set.of("CANCEL_REQUESTED","CANCELLED").contains(entry.getState()))throw new CancellationException("已请求取消 GitHub 构建");
        if("REMOTE_FAILED".equals(entry.getState()))throw new IllegalStateException("GitHub 云构建未成功（"+entry.getConclusion()+"），可查看云端日志并重试");
        if("SUCCEEDED".equals(entry.getState()))break;
        if(entry.getDeadline()==null||Instant.now().isAfter(entry.getDeadline())){requestCancel(task.getId());throw new IllegalStateException("GitHub 云构建等待超时，已请求取消云端任务；不会切回本机打包");}
        try{
          if(entry.getRunId()==null){
            Long discovered=discover(entry);
            if(discovered!=null)entry=update(task.getId(),e->e.attach(discovered));
            else if(Instant.now().isAfter(entry.getDispatchedAt().plusSeconds(180)))throw new IllegalStateException("暂未找到已提交的 GitHub 运行，请检查 Actions；本任务不会自动重复提交");
          }
          if(entry.getRunId()!=null){
            JsonNode run=api.request("GET",runPath(entry),null).body();verifyRun(entry,run);
            String status=run.path("status").asText();
            if(!status.equals(lastStatus)||System.currentTimeMillis()-lastReport>60000){output.accept("GitHub "+statusLabel(status)+" · "+runUrl(task.getId()));lastStatus=status;lastReport=System.currentTimeMillis();}
            if("completed".equals(status)){String conclusion=run.path("conclusion").asText();entry=update(task.getId(),e->e.completed(conclusion));continue;}
          }
          failures=0;
        }catch(RunMetadataPendingException pending){
          if(++failures==1||failures%6==0)output.accept(pending.getMessage());
        }catch(IOException network){if(network instanceof GitHubActionsClient.ApiException known&&Set.of(401,403,404).contains(known.status))throw network;if(++failures==1||failures%6==0)output.accept("GitHub 状态查询暂不可用，将继续查询同一次运行："+network.getMessage());}
        Thread.sleep(settings.pollSeconds*1000L);
      }
      output.accept("云端编译成功，正在取回前端产物；校验 Commit 和文件摘要后继续构建镜像。");
      var completed=entry;
      output.accept("等待本机空闲后取回并校验产物，云端编译已完成。");
      localGate.call(()->{fetchArtifact(completed,task,config,project,output);return null;});
      return "GitHub 云端构建完成，前端产物已取回；"+runUrl(task.getId());
    }catch(InterruptedException interrupted){
      if(detaching)throw new DetachedException();
      Thread.interrupted();try{requestCancel(task.getId());}finally{Thread.currentThread().interrupt();}throw interrupted;
    }
  }
  private static String statusLabel(String value){return switch(value){case "queued","waiting","pending","requested"->"等待构建机";case "in_progress"->"正在构建";case "completed"->"运行结束";default->value;};}
  static void verifyRun(FrontendCloudBuild entry,JsonNode run)throws RunMetadataPendingException{
    // A dispatch can return a run ID before GitHub evaluates the input-based run-name.
    // Verify the immutable routing fields first; never accept output on a placeholder title.
    List<String> mismatches=new ArrayList<>();
    if(entry.getRunId()==null||run.path("id").asLong()!=entry.getRunId())mismatches.add("运行编号");
    if(!run.path("event").asText().equals("workflow_dispatch"))mismatches.add("触发方式");
    if(!run.path("repository").path("full_name").asText().equals(entry.getRepository()))mismatches.add("构建仓库");
    if(!run.path("path").asText().equals(".github/workflows/"+entry.getWorkflow()))mismatches.add("工作流路径");
    if(run.path("run_attempt").asInt(1)!=1)mismatches.add("运行尝试");
    if(!mismatches.isEmpty())throw identityMismatch(mismatches);
    String title=run.path("display_title").asText();
    if(title.equals("Release-Frontend-"+entry.getRequestId()))return;
    boolean placeholder=title.isBlank()||title.equals(run.path("name").asText());
    boolean starting=Set.of("queued","waiting","pending","requested","in_progress").contains(run.path("status").asText());
    if(placeholder&&starting){
      if(entry.getDispatchedAt()!=null&&Instant.now().isBefore(entry.getDispatchedAt().plusSeconds(RUN_METADATA_GRACE_SECONDS)))throw new RunMetadataPendingException();
      throw new IllegalStateException("GitHub 任务专属标识超过 3 分钟仍未就绪，已停止等待并拒绝读取产物；可查看云端任务后重试");
    }
    throw identityMismatch(List.of("任务专属标识"));
  }
  private static IllegalStateException identityMismatch(List<String> fields){return new IllegalStateException("GitHub 运行身份与当前任务不一致（"+String.join("、",fields)+"），已拒绝读取产物");}
  static final class RunMetadataPendingException extends IOException{
    RunMetadataPendingException(){super("GitHub 正在初始化任务信息，等待专属标识就绪；将继续查询同一次运行。");}
  }
  private Long discover(FrontendCloudBuild entry)throws Exception{
    String since=URLEncoder.encode(">="+entry.getDispatchedAt().minusSeconds(60),StandardCharsets.UTF_8);Long found=null;
    for(int page=1;page<=5;page++){
      JsonNode runs=api.request("GET",workflowPath(entry)+"/runs?event=workflow_dispatch&created="+since+"&per_page=100&page="+page,null).body().path("workflow_runs");
      for(JsonNode run:runs)if(run.path("display_title").asText().equals("Release-Frontend-"+entry.getRequestId())){long id=run.path("id").asLong();if(found!=null&&found!=id)throw new IllegalStateException("GitHub 出现多个相同标识的运行，请人工核对，平台已停止自动处理");found=id;}
      if(runs.size()<100)return found;
    }
    throw new IllegalStateException("GitHub 运行记录过多，无法唯一确认任务，请人工核对");
  }
  public void prepareRetry(ReleaseTask task)throws Exception{
    var entry=get(task.getId());
    if(entry.getRunId()!=null&&!Set.of("REMOTE_FAILED","SUCCEEDED","CANCELLED").contains(entry.getState())){
      var run=api.request("GET",runPath(entry),null).body();verifyRun(entry,run);
      if(!"completed".equals(run.path("status").asText()))throw new IllegalArgumentException("原 GitHub 构建仍在运行或取消中，请结束后再重试");
      String conclusion=run.path("conclusion").asText();entry=update(task.getId(),e->e.completed(conclusion));
    }
    if(Set.of("REMOTE_FAILED","CANCELLED").contains(entry.getState()))update(task.getId(),e->e.retry(UUID.randomUUID().toString()));
    else if(!Set.of("READY","SUCCEEDED").contains(entry.getState()))throw new IllegalArgumentException("原 GitHub 提交尚未确认，请核对 Actions，避免重复打包");
    // A completed cloud build is reused: retry only downloads/validates/archives its original output.
  }
  public void requestCancel(Long taskId){
    if(journal.findById(taskId).isEmpty())return;
    update(taskId,FrontendCloudBuild::requestCancel); // Reconciler sends/retries cancellation independently of task threads.
  }
  @Scheduled(fixedDelayString="${release.frontend.github.cancel-retry-ms:30000}")
  public void reconcileCancellations(){if(detaching)return;for(var entry:journal.findTop20ByStateOrderByDispatchedAtAsc("CANCEL_REQUESTED"))try{cancelRemote(entry);}catch(Exception ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("GitHub cancellation pending for frontend task #{}",entry.getTaskId());}}
  private void cancelRemote(FrontendCloudBuild entry)throws Exception{
    if(entry.getRunId()==null){Long run=discover(entry);if(run==null)return;entry=update(entry.getTaskId(),e->e.attach(run));}
    JsonNode run=api.request("GET",runPath(entry),null).body();verifyRun(entry,run);
    if("completed".equals(run.path("status").asText())){update(entry.getTaskId(),FrontendCloudBuild::cancelled);return;}
    api.request("POST",runPath(entry)+"/cancel",null);
  }
  static final class DetachedException extends RuntimeException{DetachedException(){super("平台停止，保留原 GitHub 运行，启动后继续查询");}}

  private void fetchArtifact(FrontendCloudBuild entry,ReleaseTask task,FrontendBuildConfiguration config,Path project,Consumer<String> log)throws Exception{
    JsonNode finished=api.request("GET",runPath(entry),null).body();verifyRun(entry,finished);
    if(!"completed".equals(finished.path("status").asText())||!"success".equals(finished.path("conclusion").asText()))throw new IllegalStateException("GitHub 构建结果已变化，请新建任务");
    JsonNode list=api.request("GET",runPath(entry)+"/artifacts?per_page=100",null).body().path("artifacts");
    List<JsonNode> matches=new ArrayList<>();for(JsonNode item:list)if(item.path("name").asText().equals("release-frontend-"+entry.getRequestId()))matches.add(item);
    if(matches.size()!=1||matches.getFirst().path("expired").asBoolean(true))throw new IllegalStateException("GitHub 前端产物不存在、已过期或不唯一，请检查云端产物");
    var artifact=matches.getFirst();long id=artifact.path("id").asLong();if(id<1||artifact.path("size_in_bytes").asLong()>MAX_ARTIFACT_BYTES)throw new IllegalStateException("GitHub 前端产物大小或编号无效");
    Path zip=project.resolve(".release-cloud-"+UUID.randomUUID()+".zip");long[] reported={0};
    try{
      api.download("/repos/"+entry.getRepository()+"/actions/artifacts/"+id+"/zip",zip,MAX_ARTIFACT_BYTES,Instant.now().plusSeconds(900),bytes->{if(bytes-reported[0]>=32*1024*1024){log.accept("正在取回前端产物："+(bytes/1024/1024)+" MB");reported[0]=bytes;}});
      String digest=artifact.path("digest").asText();if(!digest.isBlank()&&!digest.equals("sha256:"+FlutterBuildService.sha256(zip)))throw new IllegalStateException("GitHub 前端产物 ZIP 摘要不匹配");
      FrontendArtifactExtractor.extract(zip,project,entry,task,config);
      installNginxConfig(config,project);
    }finally{Files.deleteIfExists(zip);}
  }
  static void installNginxConfig(FrontendBuildConfiguration config,Path project)throws IOException{
    String relative=config.nginxSourcePath();if(relative==null)return;
    Path root=project.toAbsolutePath().normalize(),source=root.resolve(relative),target=root.resolve("nginx/default.conf");
    FlutterBuildService.ensureContained(root,source);FlutterBuildService.rejectSymlinks(root,source);
    FlutterBuildService.ensureContained(root,target);FlutterBuildService.rejectSymlinks(root,target);
    if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||Files.size(source)>1024*1024)throw new IOException("源码中的 Nginx 配置不存在或过大，请核对 "+relative);
    Files.createDirectories(target.getParent());
    // Source is the platform's locked checkout, never a file supplied by the cloud artifact.
    Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING);
  }
}
