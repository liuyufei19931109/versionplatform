package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.*;
import com.local.release.security.MenuPermissions;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Observes existing queues and records. It never schedules, retries, or changes a task. */
@Service
public class TaskActivityService {
  public record Activity(String key,Long id,String eventKey,String taskKind,Long serviceId,String serviceName,
    ServiceType serviceType,String createdBy,boolean mine,boolean hidden,String status,String currentStep,
    String currentStepLabel,Instant createdAt,Instant startedAt,Instant finishedAt,String environment,
    String branch,String detailPath,boolean canCancel,String cancelPath,Integer queuePosition,
    String errorSummary,Instant lastOutputAt,String executionMode,String waitingReason) {
    public Activity(String key,Long id,String eventKey,String taskKind,Long serviceId,String serviceName,
        ServiceType serviceType,String createdBy,boolean mine,boolean hidden,String status,String currentStep,
        String currentStepLabel,Instant createdAt,Instant startedAt,Instant finishedAt,String environment,
        String branch,String detailPath,boolean canCancel,String cancelPath,Integer queuePosition,
        String errorSummary,Instant lastOutputAt){
      this(key,id,eventKey,taskKind,serviceId,serviceName,serviceType,createdBy,mine,hidden,status,currentStep,
        currentStepLabel,createdAt,startedAt,finishedAt,environment,branch,detailPath,canCancel,cancelPath,queuePosition,
        errorSummary,lastOutputAt,null,null);
    }
  }
  public record Snapshot(Instant serverTime,Activity running,List<Activity> pending,List<Activity> runtimePending,
    int pendingCount,int pendingRuntimeCount,List<Activity> recentCompletions,int concurrency,List<Activity> runningTasks,int cloudRunningCount,int cloudConcurrency) {
    public Snapshot(Instant time,Activity running,List<Activity> pending,List<Activity> runtime,int count,int runtimeCount,List<Activity> recent,int concurrency){this(time,running,pending,runtime,count,runtimeCount,recent,concurrency,running==null?List.of():List.of(running),0,0);}
  }

  private static final List<TaskStatus> TERMINAL=List.of(TaskStatus.SUCCESS,TaskStatus.FAILED,TaskStatus.CANCELLED);
  private final ReleaseExecutor executor;
  private final ReleaseTaskRepository tasks;
  private final RuntimeOperationRepository operations;
  private final ProductionMergeRepository merges;
  private final AppServiceRepository services;
  private final AdminUserRepository users;
  private com.local.release.miniprogram.MiniProgramActivitySource miniPrograms;
  @org.springframework.beans.factory.annotation.Autowired
  void setMiniPrograms(com.local.release.miniprogram.MiniProgramActivitySource value){miniPrograms=value;}
  Clock clock=Clock.systemDefaultZone();

  public TaskActivityService(ReleaseExecutor executor,ReleaseTaskRepository tasks,RuntimeOperationRepository operations,
      ProductionMergeRepository merges,AppServiceRepository services,AdminUserRepository users){
    this.executor=executor;this.tasks=tasks;this.operations=operations;this.merges=merges;this.services=services;this.users=users;
  }

  public Snapshot snapshot(String operator){
    AdminUser user=users.findByUsername(operator).filter(AdminUser::isEnabled)
      .orElseThrow(()->new AccessDeniedException("请使用有效的平台登录账号查看任务状态"));
    Set<String> permissions=MenuPermissions.effective(user.menuPermissionSet());
    ReleaseExecutor.QueueSnapshot queue=executor.queue();
    Map<Long,AppService> catalog=services.findAll().stream().collect(Collectors.toMap(AppService::getId,Function.identity()));
    Set<Long> ids=queue.pendingTasks().stream().map(ReleaseExecutor.QueuedTask::taskId).collect(Collectors.toSet());
    if(queue.runningTaskId()!=null)ids.add(queue.runningTaskId());
    queue.cloudRunningTasks().forEach(t->ids.add(t.taskId()));
    Map<Long,ReleaseTask> records=tasks.findAllById(ids).stream().collect(Collectors.toMap(ReleaseTask::getId,Function.identity()));

    Activity running=null;
    if(queue.runningTaskId()!=null){
      ReleaseTask task=records.get(queue.runningTaskId());
      running=task==null?hidden("running","RUNNING",null):task(task,catalog,user,permissions,null,"running",queue);
    }
    List<Activity> pending=new ArrayList<>();
    for(var queued:queue.pendingTasks()){
      ReleaseTask task=records.get(queued.taskId());
      pending.add(task==null?hidden("pending-"+queued.position(),"PENDING",queued.position())
        :task(task,catalog,user,permissions,queued.position(),"pending-"+queued.position(),queue));
    }

    List<RuntimeOperation> runtime=operations.findByStatusIn(List.of(TaskStatus.PENDING,TaskStatus.RUNNING));
    List<com.local.release.miniprogram.MiniProgramJob> miniJobs=miniPrograms==null?List.of():miniPrograms.active();
    if(running==null&&queue.runningRuntimeOperation()!=null){
      RuntimeOperation active=runtime.stream().filter(o->o.getStatus()==TaskStatus.RUNNING).findFirst().orElse(null);
      if(active!=null)running=restart(active,catalog,user,permissions,"running");
      else {
        // Never return the executor's free-text label: it can include service and server names.
        ProductionMerge merge=merges.findByStatus(ProductionMerge.Status.RUNNING).stream().findFirst().orElse(null);
        var mini=miniJobs.stream().filter(j->j.getStatus()==com.local.release.miniprogram.MiniProgramJob.Status.RUNNING).findFirst().orElse(null);
        running=mini!=null?miniProgram(mini,user,permissions,"running"):merge==null?hidden("running","RUNNING",null):merge(merge,catalog,user,permissions);
      }
    }
    List<Activity> runningTasks=new ArrayList<>();if(running!=null)runningTasks.add(running);
    for(var cloud:queue.cloudRunningTasks()){
      ReleaseTask record=records.get(cloud.taskId());String anonymous="running-"+runningTasks.size();
      runningTasks.add(record==null?hidden(anonymous,"RUNNING",null):task(record,catalog,user,permissions,null,anonymous,queue));
    }
    if(running==null&&!runningTasks.isEmpty())running=runningTasks.getFirst();
    int runtimeCount=Math.max(0,queue.pendingRuntimeOperations());
    List<Activity> runtimePending=new ArrayList<>();
    // Runtime waiters use a separate lock queue; their relative place among builds cannot be promised.
    runtime.stream().filter(o->o.getStatus()==TaskStatus.PENDING)
      .sorted(Comparator.comparing(RuntimeOperation::getCreatedAt,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(RuntimeOperation::getId))
      .limit(runtimeCount).forEach(o->runtimePending.add(restart(o,catalog,user,permissions,"runtime-pending-"+runtimePending.size())));
    miniJobs.stream().filter(j->j.getStatus()==com.local.release.miniprogram.MiniProgramJob.Status.PENDING)
      .sorted(Comparator.comparing(com.local.release.miniprogram.MiniProgramJob::getId))
      .forEach(j->runtimePending.add(miniProgram(j,user,permissions,"runtime-pending-"+runtimePending.size())));
    runtimeCount=Math.max(runtimeCount,runtimePending.size());
    while(runtimePending.size()<runtimeCount)runtimePending.add(hidden("runtime-pending-"+runtimePending.size(),"PENDING",null));

    LocalDateTime since=LocalDateTime.ofInstant(clock.instant().minus(Duration.ofHours(24)),clock.getZone());
    List<Activity> recent=new ArrayList<>();
    for(ReleaseTask task:tasks.findTop50ByCreatedByAndStatusInAndFinishedAtAfterOrderByFinishedAtDescIdDesc(operator,TERMINAL,since)){
      Activity item=task(task,catalog,user,permissions,null,"completion",queue);
      if(!item.hidden()&&item.mine()&&item.finishedAt()!=null)recent.add(item);
    }
    for(RuntimeOperation operation:operations.findTop50ByCreatedByAndStatusInAndFinishedAtAfterOrderByFinishedAtDescIdDesc(operator,TERMINAL,since)){
      Activity item=restart(operation,catalog,user,permissions,"completion");
      if(!item.hidden()&&item.mine()&&item.finishedAt()!=null)recent.add(item);
    }
    if(miniPrograms!=null)for(var job:miniPrograms.recent(operator)){
      if(job.getFinishedAt()==null||job.getFinishedAt().isBefore(since))continue;
      Activity item=miniProgram(job,user,permissions,"completion");if(!item.hidden()&&item.mine())recent.add(item);
    }
    recent.sort(Comparator.comparing(Activity::finishedAt).reversed().thenComparing(Activity::key));
    return new Snapshot(clock.instant(),running,List.copyOf(pending),List.copyOf(runtimePending),pending.size(),runtimeCount,
      recent.stream().limit(50).toList(),1+queue.cloudConcurrency(),List.copyOf(runningTasks),queue.cloudRunningTasks().size(),queue.cloudConcurrency());
  }

  private Activity task(ReleaseTask task,Map<Long,AppService> catalog,AdminUser user,Set<String> permissions,Integer position,String anonymousKey,ReleaseExecutor.QueueSnapshot queue){
    AppService service=catalog.get(task.getServiceId());
    boolean app="MOBILE".equals(task.getDeliveryType())||service!=null&&service.getServiceType()==ServiceType.FLUTTER_ANDROID;
    boolean build=task.getTaskKind()==TaskKind.BUILD;
    boolean visible=user.isAdmin()||permissions.contains(app?"apps":build?"builds":"release")||!app&&!build&&permissions.contains("releases");
    if(!visible)return hidden(anonymousKey,task.getStatus().name(),position);
    boolean mine=Objects.equals(user.getUsername(),task.getCreatedBy());
    String prefix=app?"/apps":"";
    String route=prefix+(build?"/builds/":"/releases/")+task.getId();
    boolean cancel=task.getStatus()==TaskStatus.PENDING&&(build||mine||user.isAdmin())
      &&(user.isAdmin()||permissions.contains(app?"apps":build?"builds":"release"))&&(build||!app);
    String step=task.getCurrentStep()==null?null:task.getCurrentStep().name();
    String label=taskStepLabel(task,service);
    Instant finish=instant(task.getFinishedAt());
    String key="task:"+task.getId();
    String mode=executionMode(task);
    return new Activity(key,task.getId(),eventKey(key,finish),build?"BUILD":"DEPLOY",task.getServiceId(),service==null?"已删除服务":service.getName(),
      service==null?(app?ServiceType.FLUTTER_ANDROID:null):service.getServiceType(),task.getCreatedBy(),mine,false,task.getStatus().name(),step,label,
      instant(task.getCreatedAt()),instant(task.getStartedAt()),finish,task.getEnvironmentName(),task.getBranch(),route,cancel,
      cancel?"/api"+route+"/cancel":null,position,failure(task.getStatus(),label,false),null,mode,waitingReason(task,mode,queue));
  }

  private Activity restart(RuntimeOperation operation,Map<Long,AppService> catalog,AdminUser user,Set<String> permissions,String anonymousKey){
    if(!user.isAdmin()&&!permissions.contains("runtime"))return hidden(anonymousKey,operation.getStatus().name(),null);
    AppService service=catalog.get(operation.getServiceId());
    boolean mine=Objects.equals(user.getUsername(),operation.getCreatedBy());
    boolean cancel=operation.getStatus()==TaskStatus.PENDING&&(mine||user.isAdmin())&&(user.isAdmin()||permissions.contains("release"));
    String label=restartStageLabel(operation.getCurrentStage());
    String key="restart:"+operation.getId();Instant finish=instant(operation.getFinishedAt());
    return new Activity(key,operation.getId(),eventKey(key,finish),"RESTART",operation.getServiceId(),operation.getServiceName(),
      service==null?null:service.getServiceType(),operation.getCreatedBy(),mine,false,operation.getStatus().name(),operation.getCurrentStage(),label,
      instant(operation.getCreatedAt()),instant(operation.getStartedAt()),finish,operation.getEnvironmentName(),null,
      "/runtime?operationId="+operation.getId()+"&serviceId="+operation.getServiceId(),cancel,cancel?"/api/runtime/restarts/"+operation.getId()+"/cancel":null,null,
      failure(operation.getStatus(),label,true),null,"LOCAL",operation.getStatus()==TaskStatus.PENDING?"等待本机执行":null);
  }

  private Activity merge(ProductionMerge merge,Map<Long,AppService> catalog,AdminUser user,Set<String> permissions){
    ReleaseTask release=tasks.findById(merge.getReleaseTaskId()).orElse(null);
    AppService service=catalog.get(merge.getServiceId());
    boolean app=release!=null&&"MOBILE".equals(release.getDeliveryType())||service!=null&&service.getServiceType()==ServiceType.FLUTTER_ANDROID;
    if(!user.isAdmin()&&(app?!permissions.contains("apps"):!permissions.contains("release")&&!permissions.contains("releases")))return hidden("running","RUNNING",null);
    String creator=release==null?null:release.getCreatedBy();
    return new Activity("merge:"+merge.getId(),merge.getId(),null,"MERGE",merge.getServiceId(),service==null?"已删除服务":service.getName(),
      service==null?null:service.getServiceType(),creator,Objects.equals(creator,user.getUsername()),false,"RUNNING","MERGE","生产代码合回 master",
      instant(merge.getCreatedAt()),instant(merge.getStartedAt()),null,"prod",release==null?null:release.getBranch(),
      release==null?null:(app?"/apps":"")+"/releases/"+release.getId(),false,null,null,null,null,"LOCAL",null);
  }

  private Activity hidden(String anonymousKey,String status,Integer position){
    return new Activity("hidden:"+anonymousKey,null,null,null,null,null,null,null,false,true,status,null,null,null,null,null,null,null,null,false,null,position,null,null,null,null);
  }
  private Activity miniProgram(com.local.release.miniprogram.MiniProgramJob job,AdminUser user,Set<String> permissions,String anonymousKey){
    if(!user.isAdmin()&&!permissions.contains("miniprograms"))return hidden(anonymousKey,job.getStatus().name(),null);
    boolean mine=Objects.equals(user.getUsername(),job.getCreatedBy());
    boolean cancel=job.getStatus()==com.local.release.miniprogram.MiniProgramJob.Status.PENDING&&(mine||user.isAdmin());
    String label=switch(job.getStage()){
      case "BASELINE_CHECK"->"校验基准分支 master";case "GIT_PULL"->"拉取代码";case "CHECK"->"代码检查";
      case "WECHAT_TRANSFER"->"微信编译与传输";case "COMPLETE"->job.getAction()==com.local.release.miniprogram.MiniProgramJob.Action.UPLOAD?"已上传微信，待提审":"预览码已生成";default->"等待执行";
    };
    String key="miniprogram:"+job.getId();Instant finish=instant(job.getFinishedAt());
    boolean reconciled=job.getStatus()==com.local.release.miniprogram.MiniProgramJob.Status.UNKNOWN&&job.getResolution()!=null;
    if(reconciled)label=job.getResolution()==com.local.release.miniprogram.MiniProgramJob.Resolution.CONFIRMED_UPLOADED?"已核对，微信已收到":"已核对，微信未收到";
    return new Activity(key,job.getId(),eventKey(key,finish),"MINIPROGRAM",null,job.getProjectName(),null,job.getCreatedBy(),mine,false,
      reconciled?"RECONCILED":job.getStatus().name(),job.getStage(),label,instant(job.getCreatedAt()),instant(job.getStartedAt()),finish,null,job.getBranch(),
      "/miniprograms?jobId="+job.getId(),cancel,cancel?"/api/miniprograms/jobs/"+job.getId()+"/cancel":null,null,
      reconciled?"管理员已登记微信核对结果；原始执行状态与日志保留":job.getErrorMessage(),instant(job.getUpdatedAt()),"LOCAL",
      job.getStatus()==com.local.release.miniprogram.MiniProgramJob.Status.PENDING?"等待本机执行":null);
  }
  /** CLOUD describes the task's saved build path, including local preparation and post-processing. */
  private static String executionMode(ReleaseTask task){
    if(GitHubMobileBuildService.usesGitHub(task)||GitHubFrontendBuildService.usesGitHub(task)||GitHubBackendBuildService.usesGitHub(task))return "CLOUD";
    return task.getTaskKind()==null?"UNKNOWN":"LOCAL";
  }
  private static String waitingReason(ReleaseTask task,String mode,ReleaseExecutor.QueueSnapshot queue){
    if(task.getStatus()!=TaskStatus.PENDING)return null;
    for (var pending : queue.pendingTasks())
      if (Objects.equals(pending.taskId(), task.getId()) && pending.waitingReason() != null)
        return pending.waitingReason();
    if("CLOUD".equals(mode)&&queue.cloudConcurrency()>0){
      // Slots cover the whole cloud task; occupancy does not mean every task is compiling remotely.
      return queue.cloudRunningTasks().size()>=queue.cloudConcurrency()?"等待云构建名额":"等待云构建调度";
    }
    return "UNKNOWN".equals(mode)?"等待执行":"等待本机执行";
  }
  private Instant instant(LocalDateTime value){return value==null?null:value.atZone(clock.getZone()).toInstant();}
  private String eventKey(String key,Instant finished){return finished==null?null:key+":"+finished;}
  private static String taskStepLabel(ReleaseTask task,AppService service){
    if(task.getStatus()==TaskStatus.PENDING)return "等待执行";
    if(task.getStatus()==TaskStatus.SUCCESS)return "已完成";
    if(task.getStatus()==TaskStatus.CANCELLED)return "已取消";
    if(task.getCurrentStep()==null)return task.getStatus()==TaskStatus.WAITING_CONFIRMATION?"等待发布确认":"准备执行";
    return switch(task.getCurrentStep()){
      case GIT_PULL -> "拉取代码与校验分支";
      case BUILD_JAR -> service!=null&&service.getServiceType()==ServiceType.FRONTEND
        ?(BuildConfiguration.imageBuildMode(service)==ImageBuildMode.REPOSITORY_DOCKERFILE?"校验仓库 Dockerfile":"构建前端应用"):"构建 Java 应用";
      case BUILD_IMAGE -> "构建镜像";
      case PUSH_IMAGE -> "推送镜像";
      case SSH_CONNECT -> "连接目标服务器";
      case START_SERVICE -> "更新并启动服务";
      case HEALTH_CHECK -> "等待健康检查";
      case BUILD_AAB -> "构建 Android AAB";
      case VALIDATE_AAB -> "校验 Android AAB";
      case BUILD_APK -> "构建 Android APK";
      case VALIDATE_APK -> "校验 Android APK";
      case ARCHIVE_ARTIFACT -> "保存安装包";
    };
  }
  private static String restartStageLabel(String stage){
    if(stage==null)return "准备重启";
    return switch(stage){
      case "WAITING" -> "等待执行";
      case "CHECK_TARGET" -> "核对目标容器";
      case "RESTART_CONTAINER" -> "重启容器";
      case "RELOAD_CONFIGURATION" -> "加载配置并重建服务";
      case "HEALTH_CHECK" -> "等待健康检查";
      case "COMPLETE" -> "已完成";
      case "CANCELLED" -> "已取消";
      default -> "准备重启";
    };
  }
  private static String failure(TaskStatus status,String label,boolean restart){
    if(status!=TaskStatus.FAILED)return null;
    // Persisted error/log text may contain SSH commands, registry credentials or private endpoints.
    return label+"阶段未完成；"+(restart?"请先核对目标服务状态，再查看详情中的错误日志":"请查看详情中的错误日志后再决定是否重试");
  }
}
