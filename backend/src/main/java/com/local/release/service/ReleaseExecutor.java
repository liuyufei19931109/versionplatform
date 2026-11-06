package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import static com.local.release.domain.ModelEnums.*;

@Service
public class ReleaseExecutor {
  private final ReleaseTaskRepository tasks;
  private final ReleaseStepRepository steps;
  private final AppServiceRepository services;
  private final ArtifactRepository artifacts;
  private final ServiceEnvironmentRepository environments;
  private final DeploymentServerRepository servers;
  private final CommandRunner runner;
  private final BuildStorageService buildStorage;
  private final BranchPolicyService branchPolicy;
  private final FlutterBuildService flutterBuild;
  private final MobileVersionService mobileVersions;
  private final ProductionMergeService productionMerge;
  private final ApplicationEventPublisher publisher;
  private final TransactionTemplate transactions;
  private BuildAutoPublishService autoPublish;
  private GitHubMobileBuildService cloudBuild;
  private GitHubFrontendBuildService frontendCloud;
  private GitHubBackendBuildService backendCloud;
  @org.springframework.beans.factory.annotation.Autowired void setBackendCloud(GitHubBackendBuildService value){backendCloud=value;}
  private LocalExecutionGate localGate=new LocalExecutionGate();
  @org.springframework.beans.factory.annotation.Autowired void setLocalGate(LocalExecutionGate value){localGate=value;executionLock=value.lock;}
  private final LinkedHashMap<Long,ReleaseTask> cloudRunning=new LinkedHashMap<>();
  private final Map<Long,TaskExecution> cloudExecutions=new HashMap<>();
  @Value("${release.executor.cloud-build-concurrency:0}") int cloudBuildConcurrency=0;
  private int cloudLimit(){return Math.max(0,Math.min(8,cloudBuildConcurrency));}
  private boolean parallelCloud(ReleaseTask task){return cloudLimit()>0&&isCloud(task);}
  private boolean isCloud(ReleaseTask task){return GitHubMobileBuildService.usesGitHub(task)||GitHubFrontendBuildService.usesGitHub(task)||GitHubBackendBuildService.usesGitHub(task);}
  private boolean detaching(ReleaseTask task){return isCloud(task)&&(stopping||(cloudBuild!=null&&cloudBuild.isDetaching())||(frontendCloud!=null&&frontendCloud.isDetaching())||(backendCloud!=null&&backendCloud.isDetaching()));}
  private TaskExecution executionFor(ReleaseTask task){return cloudExecutions.containsKey(task.getId())?cloudExecutions.get(task.getId()):runningExecution!=null&&Objects.equals(runningExecution.taskId,task.getId())?runningExecution:null;}

  @org.springframework.beans.factory.annotation.Autowired void setFrontendCloud(GitHubFrontendBuildService value){frontendCloud=value;}
  @org.springframework.beans.factory.annotation.Autowired void setCloudBuild(GitHubMobileBuildService value){cloudBuild=value;}
  @org.springframework.beans.factory.annotation.Autowired
  void setAutoPublish(BuildAutoPublishService value){autoPublish=value;}
  private final LinkedHashMap<Long,ReleaseTask> pending=new LinkedHashMap<>();
  private ReentrantLock executionLock=localGate.lock;
  private String runningRuntimeOperation;
  private int pendingRuntimeOperations;
  private ReleaseTask runningTask;
  private TaskExecution runningExecution;
  private static final String CANCELLED_REMOTE_MESSAGE="已中止后续步骤，远端状态需核对；平台不会自动回滚";
  /** Guarded by this executor's monitor; never reuse a context for another queued task. */
  private static final class TaskExecution {
    final Long taskId; Thread owner;
    boolean cancelRequested, finalized;
    TaskExecution(Long taskId){this.taskId=taskId;this.owner=Thread.currentThread();}
  }
  private boolean dispatcherScheduled;
  private volatile boolean stopping;
  @Value("${release.executor.queue-capacity:100}") int queueCapacity=100;

  public ReleaseExecutor(ReleaseTaskRepository t,ReleaseStepRepository st,AppServiceRepository s,ArtifactRepository a,ServiceEnvironmentRepository e,DeploymentServerRepository d,CommandRunner r,ApplicationEventPublisher p,PlatformTransactionManager tx,BuildStorageService storage,BranchPolicyService policy,FlutterBuildService flutter,ProductionMergeService merge){
    this(t,st,s,a,e,d,r,p,tx,storage,policy,flutter,merge,null);
  }
  @org.springframework.beans.factory.annotation.Autowired
  public ReleaseExecutor(ReleaseTaskRepository t,ReleaseStepRepository st,AppServiceRepository s,ArtifactRepository a,ServiceEnvironmentRepository e,DeploymentServerRepository d,CommandRunner r,ApplicationEventPublisher p,PlatformTransactionManager tx,BuildStorageService storage,BranchPolicyService policy,FlutterBuildService flutter,ProductionMergeService merge,MobileVersionService mobileVersions){
    tasks=t;steps=st;services=s;artifacts=a;environments=e;servers=d;runner=r;publisher=p;transactions=new TransactionTemplate(tx);buildStorage=storage;branchPolicy=policy;flutterBuild=flutter;productionMerge=merge;this.mobileVersions=mobileVersions;
  }
  public record ReleaseQueued(Long taskId){}
  public record CloudBuildQueued(Long taskId){}
  public record QueuedTask(Long taskId, TaskKind taskKind, Long serviceId, int position, String waitingReason) {
    public QueuedTask(Long taskId, TaskKind taskKind, Long serviceId, int position) {
      this(taskId, taskKind, serviceId, position, null);
    }
  }
  public record QueueSnapshot(Long runningTaskId,TaskKind runningTaskKind,Long runningServiceId,int pendingCount,List<QueuedTask> pendingTasks,int capacity,String runningRuntimeOperation,int pendingRuntimeOperations,List<QueuedTask> cloudRunningTasks,int cloudConcurrency){
    public QueueSnapshot(Long id,TaskKind kind,Long service,int count,List<QueuedTask> pending,int capacity,String runtime,int runtimeCount){this(id,kind,service,count,pending,capacity,runtime,runtimeCount,List.of(),0);}
  }

  public synchronized <T> T withSubmissionLock(java.util.function.Supplier<T> submission){return submission.get();}

  public synchronized QueueSnapshot queue(){
    List<QueuedTask> result=new ArrayList<>();int position=1;
    for (ReleaseTask task : pending.values()) {
      String reason;
      if (parallelCloud(task)) {
        int limit = cloudLimit();
        reason = cloudRunning.size() >= limit
          ? "等待云构建名额（" + cloudRunning.size() + "/" + limit + " 已占用）" : "等待云构建调度";
      } else {
        reason = "等待本机执行器";
      }
      result.add(new QueuedTask(task.getId(), task.getTaskKind(), task.getServiceId(), position++, reason));
    }
    return new QueueSnapshot(runningTask==null?null:runningTask.getId(),runningTask==null?null:runningTask.getTaskKind(),runningTask==null?null:runningTask.getServiceId(),result.size(),List.copyOf(result),capacity(),runningRuntimeOperation,pendingRuntimeOperations,cloudRunning.values().stream().map(t->new QueuedTask(t.getId(),t.getTaskKind(),t.getServiceId(),0)).toList(),cloudLimit());
  }

  /** Runtime operations share the existing execution lock, without becoming build/deploy records. */
  public void executeRuntimeOperation(String label,Runnable action) {
    synchronized(this){if(stopping)throw new IllegalStateException("平台正在停止");pendingRuntimeOperations++;}
    executionLock.lock();
    try {
      synchronized(this){pendingRuntimeOperations--;if(stopping)throw new IllegalStateException("平台正在停止");runningRuntimeOperation=label;}
      action.run();
    } finally {
      synchronized(this){runningRuntimeOperation=null;}
      executionLock.unlock();
    }
  }

  /** Restarts can leave the serial queue on cancellation; merge operations retain their existing wait behavior. */
  public void executeCancellableRuntimeOperation(String label,Runnable action)throws InterruptedException {
    if(Thread.currentThread().isInterrupted())throw new InterruptedException("运行操作已取消");
    synchronized(this){if(stopping)throw new IllegalStateException("平台正在停止");pendingRuntimeOperations++;}
    boolean counted=true,acquired=false,displayed=false;
    try{
      executionLock.lockInterruptibly();acquired=true;
      synchronized(this){
        pendingRuntimeOperations--;counted=false;
        if(stopping)throw new IllegalStateException("平台正在停止");
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("运行操作已取消");
        runningRuntimeOperation=label;displayed=true;
      }
      action.run();
    }finally{
      synchronized(this){if(counted)pendingRuntimeOperations--;if(displayed)runningRuntimeOperation=null;}
      if(acquired)executionLock.unlock();
    }
  }

  public synchronized ReleaseTask createBuild(Long serviceId,String branch,String note,boolean simulate,String operator,String commitId,String requestId){
    return createBuild(serviceId,branch,note,simulate,operator,commitId,requestId,null);
  }
  public synchronized ReleaseTask createBuild(Long serviceId,String branch,String note,boolean simulate,String operator,String commitId,String requestId,String environmentName){
    return createBuild(serviceId,branch,note,simulate,operator,commitId,requestId,environmentName,null);
  }
  public synchronized ReleaseTask createBuild(Long serviceId,String branch,String note,boolean simulate,String operator,String commitId,String requestId,String environmentName,Long preBuildTaskId){
    return createBuild(serviceId,branch,note,simulate,operator,commitId,requestId,environmentName,preBuildTaskId,null);
  }
  public synchronized ReleaseTask createBuild(Long serviceId,String branch,String note,boolean simulate,String operator,String commitId,String requestId,String environmentName,Long preBuildTaskId,BuildAutoPublishService.Target requestedPublish){
    rejectSimulation(simulate);
    if(requestedPublish!=null&&autoPublish==null)throw new IllegalArgumentException("自动发布暂不可用，请选择仅打包");
    BuildAutoPublishService.Target publish=autoPublish==null?null:autoPublish.normalize(requestedPublish);
    if(preBuildTaskId!=null&&preBuildTaskId<=0)throw new IllegalArgumentException("PRE 构建编号无效");
    String sha=validCommit(commitId);
    String key=requestKey(TaskKind.BUILD,requestId,operator);
    ReleaseTask replay=tasks.findByRequestKey(key).orElse(null);
    if(replay!=null){
      String replayFingerprint="MOBILE".equals(replay.getDeliveryType())
        ?mobileFingerprint(serviceId,branch,sha,note,mobileBuildEnvironment(environmentName),preBuildTaskId):fingerprint(serviceId,branch,sha,note);
      if(publish!=null)replayFingerprint=autoPublishFingerprint(replayFingerprint,publish);
      if(!"MOBILE".equals(replay.getDeliveryType())&&preBuildTaskId!=null)throw new IllegalArgumentException("仅自动 App 生产构建可以选择 PRE 来源");
      if(!Objects.equals(replay.getRequestFingerprint(),replayFingerprint))throw new IllegalArgumentException("请求标识已用于其他配置，请刷新后重新提交");
      return replay;
    }
    AppService service=enabledService(serviceId);
    boolean mobile=service.getServiceType()==ServiceType.FLUTTER_ANDROID;
    if(publish!=null){
      if(mobile)throw new IllegalArgumentException("App 请使用安装包分发流程，不支持服务器自动发布");
      validateAutoPublishTarget(serviceId,publish,operator);
    }
    String environment=mobile?mobileBuildEnvironment(environmentName):"build";
    FlutterBuildConfiguration mobileConfig=mobile?FlutterBuildConfiguration.forEnvironment(service,environment):null;
    boolean automatic=mobile&&"AUTO".equals(FlutterBuildConfiguration.versionPolicy(service));
    if(preBuildTaskId!=null&&!automatic)throw new IllegalArgumentException("仅自动 App 生产构建可以选择 PRE 来源");
    if(automatic&&mobileVersions==null)throw new IllegalStateException("App 自动版本分配服务不可用");
    String lockedMobileConfiguration=mobile&&!automatic?FlutterBuildConfiguration.snapshot(service,environment):null;
    String baseFingerprint=mobile?mobileFingerprint(serviceId,branch,sha,note,environment,preBuildTaskId):fingerprint(serviceId,branch,sha,note);
    String fingerprint=publish==null?baseFingerprint:autoPublishFingerprint(baseFingerprint,publish);
    validateBuildConfiguration(service);
    String selectedBranch=branch==null||branch.isBlank()?service.getDefaultBranch():branch.trim();
    validateBranch(selectedBranch);
    ensureCapacity();
    ReleaseTask task=null;
    for(int attempt=0;attempt<4;attempt++)try{
      task=transactions.execute(status->{
      // Database request-key uniqueness also protects concurrent platform instances.
      if(automatic){ReleaseTask concurrent=existing(key,fingerprint);if(concurrent!=null)return concurrent;}
      MobileVersionService.Reservation reservation=automatic?mobileVersions.reserve(service,mobileConfig,selectedBranch,sha,preBuildTaskId,operator):null;
      ReleaseTask created=new ReleaseTask(serviceId,TaskKind.BUILD,null,environment,selectedBranch,note,false);
      created.setCommitId(sha);created.setCreatedBy(operator);created.setRequestKey(key);created.setRequestFingerprint(fingerprint);
      created.setImageTagUpgraded(true);
      if(mobile){created.setDeliveryType("MOBILE");created.setMobileBuildEngine(cloudBuild==null?"LOCAL":cloudBuild.engineForNewTask(service));created.setMobileBuildConfigSnapshot(reservation==null?lockedMobileConfiguration:FlutterBuildConfiguration.assignedSnapshot(service,reservation.config(),reservation.allocation().getBaseVersion(),preBuildTaskId));}
      if(service.getServiceType()==ServiceType.FRONTEND)created.setFrontendBuildEngine(frontendCloud==null?"LOCAL":frontendCloud.engineForNewTask(service));
      if(service.getServiceType()==ServiceType.BACKEND)created.setBackendBuildEngine(backendCloud==null?"LOCAL":backendCloud.engineForNewTask(service));
      created=tasks.save(created);
      if(GitHubMobileBuildService.usesGitHub(created))cloudBuild.prepare(created,service);
      if(GitHubFrontendBuildService.usesGitHub(created))frontendCloud.prepare(created,service);
      if(GitHubBackendBuildService.usesGitHub(created))backendCloud.prepare(created,service);
      if(publish!=null)autoPublish.create(created,publish);
      if(reservation!=null)mobileVersions.bind(reservation,created.getId());
      List<StepType> pipeline=service.getServiceType()==ServiceType.FLUTTER_ANDROID
        ?(FlutterBuildConfiguration.fromTask(created).isApk()?List.of(StepType.GIT_PULL,StepType.BUILD_APK,StepType.VALIDATE_APK,StepType.ARCHIVE_ARTIFACT):List.of(StepType.GIT_PULL,StepType.BUILD_AAB,StepType.VALIDATE_AAB,StepType.ARCHIVE_ARTIFACT))
        :List.of(StepType.GIT_PULL,StepType.BUILD_JAR,StepType.BUILD_IMAGE,StepType.PUSH_IMAGE);
      for(StepType type:pipeline)steps.save(new ReleaseStep(created.getId(),type));
      return created;
      });break;
    }catch(org.springframework.dao.DataIntegrityViolationException|org.springframework.dao.PessimisticLockingFailureException race){
      if(!automatic||attempt==3)throw race;
      // A first-use unique-sequence insert can race. Roll back everything, then retry allocation and task together.
    }
    enqueue(Objects.requireNonNull(task));return task;
  }

  public synchronized ReleaseTask existingDeployment(Long serviceId,Long artifactId,String env,List<Long> serverIds,String note,String requestId,String operator){
    return existing(requestKey(TaskKind.DEPLOY,requestId,operator),deploymentFingerprint(serviceId,artifactId,env,serverIds,note));
  }

  public List<DeploymentReview.History> deploymentHistory(Long serviceId) {
    var service=services.findById(serviceId).orElseThrow(()->new IllegalArgumentException("服务不存在"));
    if(service.getServiceType()==ServiceType.FLUTTER_ANDROID)
      throw new IllegalArgumentException("App 请使用安装包交付记录");
    return DeploymentReview.history(tasks.deploymentEvents(serviceId,TaskKind.DEPLOY));
  }

  public DeploymentReview.Result deploymentReview(Long serviceId,Long artifactId,String environment,List<Long> serverIds) {
    Artifact artifact=artifacts.findById(artifactId).orElseThrow(()->new IllegalArgumentException("制品包不存在"));
    if (!serviceId.equals(artifact.getServiceId())) throw new IllegalArgumentException("所选制品不属于当前服务");
    if (enabledService(serviceId).getServiceType()==ServiceType.FLUTTER_ANDROID)
      throw new IllegalArgumentException("App 请使用安装包分发流程");
    String env=normalizeEnvironment(environment);
    var targets=selectedServers(deploymentConfiguration(serviceId,env),serverIds);
    return DeploymentReview.evaluate(serviceId,artifactId,env,targets,tasks.deploymentEvents(serviceId,TaskKind.DEPLOY));
  }

  public void requireDeploymentReview(Long serviceId,Long artifactId,String environment,List<Long> serverIds,String confirmationToken) {
    deploymentReview(serviceId,artifactId,environment,serverIds).verify(confirmationToken);
  }

  public synchronized ReleaseTask createDeployment(Long serviceId,Long artifactId,String env,List<Long> requestedServerIds,String note,boolean simulate,String operator,String requestId){
    rejectSimulation(simulate);
    String key=requestKey(TaskKind.DEPLOY,requestId,operator),fingerprint=deploymentFingerprint(serviceId,artifactId,env,requestedServerIds,note);
    ReleaseTask existing=existing(key,fingerprint);if(existing!=null)return existing;
    precheckDeployment(serviceId,artifactId,env,requestedServerIds);
    Artifact artifact=artifacts.findById(artifactId).orElseThrow();
    String target=normalizeEnvironment(env);
    ServiceEnvironment configuration=deploymentConfiguration(serviceId,target);
    List<Long> selected=selectedServers(configuration,requestedServerIds);
    ReleaseTask task=transactions.execute(status->{
      ReleaseTask created=new ReleaseTask(serviceId,TaskKind.DEPLOY,artifactId,target,artifact.getBranch(),note,false);
      created.setCommitId(artifact.getCommitId());created.setTargetServerIds(selected);created.setCreatedBy(operator);
      if("prod".equals(target))created.setProductionScopeComplete(new HashSet<>(selected).equals(new HashSet<>(configuration.getServerIds())));
      created.setRequestKey(key);created.setRequestFingerprint(fingerprint);created=tasks.save(created);
      for(StepType type:List.of(StepType.SSH_CONNECT,StepType.START_SERVICE,StepType.HEALTH_CHECK))steps.save(new ReleaseStep(created.getId(),type));
      return created;
    });
    enqueue(Objects.requireNonNull(task));return task;
  }

  public synchronized void precheckDeployment(Long serviceId,Long artifactId,String environment,List<Long> requestedServerIds){
    if(enabledService(serviceId).getServiceType()==ServiceType.FLUTTER_ANDROID)throw new IllegalArgumentException("App 请使用安装包分发流程");
    Artifact artifact=artifacts.findById(artifactId).orElseThrow(()->new IllegalArgumentException("制品包不存在"));
    if(!artifact.getServiceId().equals(serviceId))throw new IllegalArgumentException("所选制品不属于当前服务");
    ReleaseTask build=tasks.findById(artifact.getBuildTaskId()).orElseThrow(()->new IllegalArgumentException("制品对应的打包任务不存在"));
    if(build.getTaskKind()!=TaskKind.BUILD||build.getStatus()!=TaskStatus.SUCCESS||build.isSimulate())throw new IllegalArgumentException("只能发布真实打包成功的制品");
    if("MOBILE".equals(build.getDeliveryType()))throw new IllegalArgumentException("App 制品不能通过服务器镜像发布流程执行");
    selectedServers(deploymentConfiguration(serviceId,environment),requestedServerIds);
    if("prod".equals(normalizeEnvironment(environment))&&!productionVerified(artifactId))
      throw new IllegalArgumentException("生产只能发布已在预生产发布成功的制品：请先将该制品发布到预生产并通过健康检查");
    ensureCapacity();
  }

  /** Same artifact passed pre-production, or already ran in production (rollback). Checked before any approval code is consumed. */
  public boolean productionVerified(Long artifactId){
    return artifactId!=null&&tasks.existsByArtifactIdAndTaskKindAndStatusAndSimulateFalseAndEnvironmentNameIn(artifactId,TaskKind.DEPLOY,TaskStatus.SUCCESS,PRODUCTION_VERIFIED_ENVIRONMENTS);
  }
  public static final List<String> PRODUCTION_VERIFIED_ENVIRONMENTS=List.of("test","prod");

  public synchronized void precheckDeploymentRetry(Long id){
    ReleaseTask task=retryable(id,TaskKind.DEPLOY);
    var review=deploymentReview(task.getServiceId(),task.getArtifactId(),task.getEnvironmentName(),task.targetServerIdList());
    if(review.action()==DeploymentReview.Action.ACTIVE) throw new DeploymentReview.Conflict(review);
    precheckDeployment(task.getServiceId(),task.getArtifactId(),task.getEnvironmentName(),task.targetServerIdList());
  }
  public synchronized boolean existingDeploymentRetry(Long id,String requestId,String operator){
    ReleaseTask task=typedTask(id,TaskKind.DEPLOY);
    return task.hasRetryRequestKey(retryKey(task,requestId,operator));
  }
  public synchronized void retryDeployment(Long id,String requestId,String operator){
    ReleaseTask task=typedTask(id,TaskKind.DEPLOY);String key=retryKey(task,requestId,operator);
    if(task.hasRetryRequestKey(key))return;
    precheckDeploymentRetry(id);retryQueued(task,key);
  }
  public synchronized void retryBuild(Long id,String requestId,String operator){
    ReleaseTask task=typedTask(id,TaskKind.BUILD);String key=retryKey(task,requestId,operator);
    if(task.hasRetryRequestKey(key))return;
    retryable(id,TaskKind.BUILD);validateTaskBuildConfiguration(enabledService(task.getServiceId()),task);validCommit(task.getCommitId());ensureCapacity();
    // A retry is a new build attempt, never a renewed automatic-deployment authorization.
    if(autoPublish!=null)autoPublish.stopAfterBuild(task);
    if(GitHubMobileBuildService.usesGitHub(task))try{cloudBuild.prepareRetry(task);}catch(Exception error){throw new IllegalArgumentException(error.getMessage());}
    if(GitHubFrontendBuildService.usesGitHub(task))try{frontendCloud.prepareRetry(task);}catch(Exception error){throw new IllegalArgumentException(error.getMessage());}
    if(GitHubBackendBuildService.usesGitHub(task))try{backendCloud.prepareRetry(task);}catch(Exception error){throw new IllegalArgumentException(error.getMessage());}
    retryQueued(task,key);
  }

  private ReleaseTask retryable(Long id,TaskKind kind){
    ReleaseTask task=typedTask(id,kind);
    if(kind==TaskKind.DEPLOY&&"MOBILE".equals(task.getDeliveryType()))throw new IllegalArgumentException("App 分发失败请重新创建对应环境的发布记录");
    if(task.getStatus()!=TaskStatus.FAILED)throw new IllegalArgumentException("仅失败任务可以重试；排队中或执行中的任务请勿重复提交");
    rejectSimulation(task.isSimulate());return task;
  }
  private void retryQueued(ReleaseTask task,String key){
    transactions.executeWithoutResult(status->{
      upgradeLegacyImageSteps(task);
      for(ReleaseStep step:steps.findByReleaseTaskIdOrderById(task.getId()))if(step.getStatus()!=StepStatus.SUCCESS){
        step.setStatus(StepStatus.WAITING);step.setStartedAt(null);step.setFinishedAt(null);steps.save(step);
      }
      task.setStatus(TaskStatus.PENDING);task.setCurrentStep(null);task.setErrorMessage(null);task.setStartedAt(null);task.setFinishedAt(null);task.setLastRetryRequestKey(key);tasks.save(task);
    });
    enqueue(task);
  }

  public synchronized void cancelBuild(Long id){cancel(id,TaskKind.BUILD);}
  public synchronized void cancelDeployment(Long id){cancel(id,TaskKind.DEPLOY);}
  private void cancel(Long id,TaskKind kind){
    ReleaseTask task=typedTask(id,kind);
    if(kind==TaskKind.DEPLOY&&"MOBILE".equals(task.getDeliveryType()))throw new IllegalArgumentException("App 分发不支持通过服务器发布接口取消");
    if(task.getStatus()==TaskStatus.CANCELLED)return;
    if((kind==TaskKind.DEPLOY||isCloud(task))&&task.getStatus()==TaskStatus.RUNNING){
      TaskExecution execution=executionFor(task);
      if(execution==null||!Objects.equals(execution.taskId,id)||execution.finalized)throw new IllegalArgumentException("任务已结束或不在当前执行器中，请刷新状态");
      if(!execution.cancelRequested){execution.cancelRequested=true;if(execution.owner!=null)execution.owner.interrupt();}
      return; // Keep RUNNING and the serial lock until the command has actually unwound.
    }
    if(task.getStatus()!=TaskStatus.PENDING||Objects.equals(runningTask==null?null:runningTask.getId(),id))throw new IllegalArgumentException("只能取消尚未执行的排队任务");
    if(GitHubMobileBuildService.usesGitHub(task))cloudBuild.requestCancel(id);
    if(GitHubFrontendBuildService.usesGitHub(task))frontendCloud.requestCancel(id);
    if(GitHubBackendBuildService.usesGitHub(task))backendCloud.requestCancel(id);
    transactions.executeWithoutResult(status->{
      task.setStatus(TaskStatus.CANCELLED);task.setCurrentStep(null);task.setFinishedAt(LocalDateTime.now());tasks.save(task);
      if(autoPublish!=null)autoPublish.stopAfterBuild(task);
      for(ReleaseStep step:steps.findByReleaseTaskIdOrderById(id))if(step.getStatus()==StepStatus.WAITING){
        step.setStatus(StepStatus.SKIPPED);step.appendLog("任务在开始前已取消");step.setFinishedAt(LocalDateTime.now());steps.save(step);
      }
    });
    pending.remove(id);drainAutoPublish();
  }

  private void enqueue(ReleaseTask task){
    pending.putIfAbsent(task.getId(),task);schedule();
  }
  /** Caller holds this monitor. Reserve slots before publishing, so submissions cannot over-admit. */
  private void schedule(){
    if(stopping)return;
    while(cloudRunning.size()<cloudLimit()){
      ReleaseTask task=pending.values().stream().filter(this::parallelCloud).findFirst().orElse(null);
      if(task==null)break;pending.remove(task.getId());
      var current=tasks.findById(task.getId()).orElse(null);if(current==null||current.getStatus()!=TaskStatus.PENDING)continue;
      task=current;task.setStatus(TaskStatus.RUNNING);task.setStartedAt(LocalDateTime.now());task.setErrorMessage(null);tasks.save(task);
      var context=new TaskExecution(task.getId());context.owner=null;
      cloudRunning.put(task.getId(),task);cloudExecutions.put(task.getId(),context);
      try{publisher.publishEvent(new CloudBuildQueued(task.getId()));}
      catch(RuntimeException error){cloudRunning.remove(task.getId());cloudExecutions.remove(task.getId());failTask(task,new IllegalStateException("云构建执行器暂不可用，请稍后重试",error));}
    }
    if(dispatcherScheduled||pending.values().stream().noneMatch(t->!parallelCloud(t)))return;
    dispatcherScheduled=true;
    try{publisher.publishEvent(new ReleaseQueued(pending.keySet().iterator().next()));}
    catch(RuntimeException error){dispatcherScheduled=false;throw new IllegalArgumentException("执行队列暂时不可用，请稍后重试",error);}
  }

  @Async("cloudBuildExecutor") @EventListener
  public void executeCloud(CloudBuildQueued event){
    ReleaseTask task;TaskExecution context;
    synchronized(this){task=cloudRunning.get(event.taskId());context=cloudExecutions.get(event.taskId());if(task==null||context==null||context.owner!=null||stopping)return;context.owner=Thread.currentThread();}
    try{runTask(task);}catch(Exception error){finishExecutionFailure(task,error);}
    finally{synchronized(this){if(context.cancelRequested)Thread.interrupted();cloudRunning.remove(task.getId());cloudExecutions.remove(task.getId());if(task.getStatus()!=TaskStatus.SUCCESS)drainAutoPublish();schedule();}}
  }

  @Async("taskExecutor") @EventListener
  public void execute(ReleaseQueued ignored){
    while(true){
      // Release between tasks, allowing cloud post-processing and runtime operations a fair turn.
      executionLock.lock();
      try{
        ReleaseTask task;
        synchronized(this){
          if(stopping){dispatcherScheduled=false;return;}
          task=pending.values().stream().filter(t->!parallelCloud(t)).findFirst().orElse(null);
          if(task==null){dispatcherScheduled=false;return;}
          pending.remove(task.getId());task=tasks.findById(task.getId()).orElse(null);
          if(task==null||task.getStatus()!=TaskStatus.PENDING)continue;
          drainAutoPublish();
          task.setStatus(TaskStatus.RUNNING);task.setStartedAt(LocalDateTime.now());task.setErrorMessage(null);tasks.save(task);runningTask=task;runningExecution=new TaskExecution(task.getId());
        }
        try{runTask(task);}catch(Exception error){finishExecutionFailure(task,error);}
        finally{synchronized(this){if(runningExecution!=null&&runningExecution.cancelRequested)Thread.interrupted();runningExecution=null;runningTask=null;if(task.getStatus()!=TaskStatus.SUCCESS)drainAutoPublish();schedule();}}
      }finally{executionLock.unlock();}
    }
  }

  private void runTask(ReleaseTask task)throws Exception{
    checkCancellation(task);
    rejectSimulation(task.isSimulate());
    AppService service=enabledService(task.getServiceId());
    Artifact selectedArtifact=task.getArtifactId()==null?null:artifacts.findById(task.getArtifactId()).orElseThrow(()->new IllegalArgumentException("制品包不存在"));
    ServiceEnvironment environment=null;List<DeploymentServer> deploymentServers=List.of();
    if(task.getTaskKind()==TaskKind.DEPLOY){
      if(autoPublish!=null)autoPublish.validateDeploymentActor(task);
      environment=deploymentConfiguration(task.getServiceId(),task.getEnvironmentName());
      List<Long> selected=selectedServers(environment,task.targetServerIdList());
      deploymentServers=selected.stream().map(id->servers.findById(id).orElseThrow(()->new IllegalArgumentException("服务器不存在："+id))).toList();
    }else{
      validCommit(task.getCommitId());validateTaskBuildConfiguration(service,task);
      // Every attempt, including retries that skip successful Git/build steps, must check a fresh master.
      // A recovered cloud run already passed the baseline check before dispatch; do not change its locked input mid-build.
      if(!(GitHubMobileBuildService.usesGitHub(task)&&cloudBuild!=null&&cloudBuild.continuesOriginalRun(task))&&!(GitHubFrontendBuildService.usesGitHub(task)&&frontendCloud!=null&&frontendCloud.continuesOriginalRun(task))&&!(GitHubBackendBuildService.usesGitHub(task)&&backendCloud!=null&&backendCloud.continuesOriginalRun(task)))localGate.call(()->{checkCancellation(task);branchPolicy.check(service,task,line->{});return null;});
    }
    for(ReleaseStep step:steps.findByReleaseTaskIdOrderById(task.getId())){
      checkCancellation(task);
      if(step.getStatus()==StepStatus.SUCCESS)continue;
      String baseLog=step.getLogText()==null?"":step.getLogText();StringBuilder liveOutput=new StringBuilder();
      try{
        synchronized(this){checkCancellation(task);task.setCurrentStep(step.getStepType());tasks.save(task);step.setStatus(StepStatus.RUNNING);step.setStartedAt(LocalDateTime.now());step.appendLog("开始："+stepLabel(step.getStepType(),service));steps.save(step);}
        baseLog=step.getLogText();String savedBase=baseLog;long[] lastSaved={System.nanoTime()};
        Consumer<String> liveLog=line->{
          BoundedProcessRunner.appendTail(liveOutput,line+System.lineSeparator());long now=System.nanoTime();
          synchronized(this){if(!cancellationRequested(task)&&now-lastSaved[0]>=500_000_000L){step.setLogText(tail(savedBase+liveOutput));steps.save(step);lastSaved[0]=now;}}
        };
        checkCancellation(task);
        final var targetEnvironment=environment;final var targetServers=deploymentServers;
        boolean remoteCompile=isCloud(task)&&Set.of(StepType.BUILD_JAR,StepType.BUILD_APK,StepType.BUILD_AAB).contains(step.getStepType());
        LocalExecutionGate.Action<String> action=()->{
          checkCancellation(task);
          return switch(step.getStepType()){
            case BUILD_JAR -> GitHubBackendBuildService.usesGitHub(task)?backendCloud.build(task,runner.sourcePath(task),liveLog):GitHubFrontendBuildService.usesGitHub(task)?frontendCloud.build(task,runner.sourcePath(task),liveLog):runner.execute(step.getStepType(),service,task,selectedArtifact,targetEnvironment,targetServers,liveLog);
            case BUILD_AAB, VALIDATE_AAB, BUILD_APK, VALIDATE_APK, ARCHIVE_ARTIFACT -> flutterBuild.execute(step.getStepType(),service,task,runner.sourcePath(task),liveLog);
            default -> runner.execute(step.getStepType(),service,task,selectedArtifact,targetEnvironment,targetServers,liveLog);
          };
        };
        if(!remoteCompile&&parallelCloud(task)&&executionLock.isLocked())liveLog.accept("等待本机执行资源；云端构建可以继续并行。");
        String output=remoteCompile?action.run():localGate.call(action);
        synchronized(this){
          checkCancellation(task);
          if(step.getStepType()==StepType.GIT_PULL)tasks.save(task);
          step.setLogText(tail(baseLog+withTrailingNewline(output)));step.setStatus(StepStatus.SUCCESS);step.setFinishedAt(LocalDateTime.now());steps.save(step);
        }
      }catch(Exception error){
        if(detaching(task))throw error;
        synchronized(this){
          if(cancellationRequested(task)){
            // The command has unwound; preserve its final buffered output without an interrupting JDBC write.
            Thread.interrupted();step.setLogText(tail(baseLog+liveOutput));steps.save(step);throw error;
          }
          step.setLogText(tail(baseLog+liveOutput+"失败："+firstLine(error)+System.lineSeparator()));step.setStatus(StepStatus.FAILED);step.setFinishedAt(LocalDateTime.now());steps.save(step);
        }
        throw error;
      }
    }
    synchronized(this){
      checkCancellation(task);
      transactions.executeWithoutResult(status->{
      if(task.getTaskKind()==TaskKind.BUILD&&!artifacts.existsByBuildTaskId(task.getId())){
        if("MOBILE".equals(task.getDeliveryType()))flutterBuild.persistArtifact(service,task,runner.imageVersion(task));
        else{
          Artifact artifact=new Artifact(service.getId(),task.getId(),runner.imageVersion(task),task.getBranch(),task.getCommitId(),runner.imageTag(service,task));
          artifact.setJarPath(service.getJarPath());artifact.setCreatedBy(task.getCreatedBy());artifacts.save(artifact);
        }
      }
      task.setStatus(TaskStatus.SUCCESS);task.setCurrentStep(null);task.setFinishedAt(LocalDateTime.now());tasks.save(task);
      if(task.getTaskKind()==TaskKind.DEPLOY&&"prod".equals(task.getEnvironmentName())){task.setMergeEnqueuePending(true);tasks.save(task);}
      });
      if(executionFor(task)!=null)executionFor(task).finalized=true;
    }
    // The artifact and SUCCESS state must commit before disposable build files are removed.
    drainAutoPublish();
    notifyFinished(task);
    if(task.getMergeEnqueuePending())try{productionMerge.ensureForSuccessfulDeployment(task);}catch(Exception mergeError){org.slf4j.LoggerFactory.getLogger(ReleaseExecutor.class).warn("Release #{} merge enqueue deferred",task.getId());}
    // Maintenance is still covered by the execution lock, but cannot fail a successful build.
    if(task.getTaskKind()==TaskKind.BUILD&&!"MOBILE".equals(task.getDeliveryType()))try{localGate.call(()->{buildStorage.afterSuccessfulBuild(task.getId());return null;});}
    catch(Exception maintenanceError){org.slf4j.LoggerFactory.getLogger(ReleaseExecutor.class).warn("Build #{} storage maintenance deferred",task.getId(),maintenanceError);}
    if(task.getTaskKind()==TaskKind.BUILD&&"MOBILE".equals(task.getDeliveryType()))try{localGate.call(()->{flutterBuild.cleanupAfterSuccess(task.getId());return null;});}
    catch(Exception maintenanceError){org.slf4j.LoggerFactory.getLogger(ReleaseExecutor.class).warn("App build #{} storage maintenance deferred",task.getId());}
  }

  private synchronized boolean cancellationRequested(ReleaseTask task){
    return executionFor(task)!=null&&executionFor(task).cancelRequested;
  }
  private void checkCancellation(ReleaseTask task){
    if(cancellationRequested(task))throw new java.util.concurrent.CancellationException(CANCELLED_REMOTE_MESSAGE);
  }
  private synchronized void finishExecutionFailure(ReleaseTask task,Exception error){
    if(detaching(task))return; // RUNNING is recovered against the same GitHub run.
    if(cancellationRequested(task)){
      // Clear only the interrupt issued for this execution, after its remote runner has returned.
      Thread.interrupted();
      if(GitHubMobileBuildService.usesGitHub(task))cloudBuild.requestCancel(task.getId());
      if(GitHubFrontendBuildService.usesGitHub(task))frontendCloud.requestCancel(task.getId());
      if(GitHubBackendBuildService.usesGitHub(task))backendCloud.requestCancel(task.getId());
      transactions.executeWithoutResult(status->{
        task.setStatus(TaskStatus.CANCELLED);task.setCurrentStep(null);task.setErrorMessage(CANCELLED_REMOTE_MESSAGE);task.setFinishedAt(LocalDateTime.now());tasks.save(task);
        for(ReleaseStep step:steps.findByReleaseTaskIdOrderById(task.getId()))if(step.getStatus()!=StepStatus.SUCCESS){
          step.setStatus(StepStatus.SKIPPED);step.appendLog(CANCELLED_REMOTE_MESSAGE);step.setFinishedAt(LocalDateTime.now());steps.save(step);
        }
      });
    }else failTask(task,error);
    if(executionFor(task)!=null)executionFor(task).finalized=true;
  }

  private void failTask(ReleaseTask task,Exception error){
    if(GitHubMobileBuildService.usesGitHub(task)&&cloudBuild!=null)cloudBuild.requestCancel(task.getId());
    if(GitHubFrontendBuildService.usesGitHub(task)&&frontendCloud!=null)frontendCloud.requestCancel(task.getId());
    if(GitHubBackendBuildService.usesGitHub(task)&&backendCloud!=null)backendCloud.requestCancel(task.getId());
    task.setStatus(TaskStatus.FAILED);task.setErrorMessage(errorSummary(error));task.setFinishedAt(LocalDateTime.now());tasks.save(task);
    if(autoPublish!=null)try{autoPublish.stopAfterBuild(task);}catch(Exception ignored){org.slf4j.LoggerFactory.getLogger(ReleaseExecutor.class).warn("Build #{} follow-up stop deferred",task.getId());}
    notifyFinished(task);
  }

  /** Publish only after the final database commit; notification failures cannot change the result. */
  private void notifyFinished(ReleaseTask task){
    if(task.getTaskKind()!=TaskKind.DEPLOY)return;
    try{publisher.publishEvent(new WecomNotificationService.ReleaseFinished(task));}
    catch(Exception error){org.slf4j.LoggerFactory.getLogger(ReleaseExecutor.class).warn("Release #{} notification deferred; exceptionType={}",task.getId(),error.getClass().getSimpleName());}
  }

  @EventListener(ApplicationReadyEvent.class)
  public synchronized void recover(){
    List<ReleaseTask> recoverable=new ArrayList<>(tasks.findByStatusIn(List.of(TaskStatus.PENDING,TaskStatus.RUNNING)));
    recoverable.sort(Comparator.comparing(ReleaseTask::getCreatedAt,Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(ReleaseTask::getId));
    for(ReleaseTask task:recoverable){
      if(task.getTaskKind()==TaskKind.DEPLOY&&"MOBILE".equals(task.getDeliveryType()))continue;
      if(task.isSimulate()){failTask(task,new IllegalStateException("模拟任务已停用，请新建真实任务"));continue;}
      if(task.getTaskKind()==TaskKind.BUILD&&(task.getCommitId()==null||!task.getCommitId().matches("[a-fA-F0-9]{40}"))){failTask(task,new IllegalStateException("旧任务未固定 Commit，请重新确认代码并新建打包"));continue;}
      if(task.getTaskKind()==TaskKind.BUILD&&"MOBILE".equals(task.getDeliveryType()))try{FlutterBuildConfiguration.fromTask(task);}catch(IllegalArgumentException error){failTask(task,error);continue;}
      if(task.getTaskKind()==TaskKind.DEPLOY&&task.getStatus()==TaskStatus.RUNNING){
        for(ReleaseStep step:steps.findByReleaseTaskIdOrderById(task.getId()))if(step.getStatus()==StepStatus.RUNNING){step.setStatus(StepStatus.FAILED);step.appendLog("平台重启中断，请核对远端状态后手动重试");steps.save(step);}
        failTask(task,new IllegalStateException("发布在平台重启时中断，请核对目标服务器状态后手动重试"));continue;
      }
      if(pending.size()>=capacity()){failTask(task,new IllegalStateException("恢复任务超过队列容量，请稍后手动重试"));continue;}
      upgradeLegacyImageSteps(task);
      for(ReleaseStep step:steps.findByReleaseTaskIdOrderById(task.getId()))if(step.getStatus()==StepStatus.RUNNING){step.setStatus(StepStatus.WAITING);step.appendLog("平台重启，步骤等待恢复");steps.save(step);}
      task.setStatus(TaskStatus.PENDING);tasks.save(task);pending.put(task.getId(),task);
    }
    drainAutoPublish();
    if(!pending.isEmpty()&&!dispatcherScheduled)enqueue(pending.values().iterator().next());
  }
  @EventListener(org.springframework.context.event.ContextClosedEvent.class)
  @PreDestroy public void stop(){stopping=true;if(cloudBuild!=null)cloudBuild.detach();if(frontendCloud!=null)frontendCloud.detach();if(backendCloud!=null)backendCloud.detach();}

  public String cloudRunUrl(Long taskId){String mobile=cloudBuild==null?null:cloudBuild.runUrl(taskId);String front=frontendCloud==null?null:frontendCloud.runUrl(taskId);return mobile!=null?mobile:front!=null?front:backendCloud==null?null:backendCloud.runUrl(taskId);}

  public BuildAutoPublishService.View buildAutoPublish(Long buildId){return autoPublish==null?null:autoPublish.view(buildId);}
  /** Reconcile only explicit durable plans; never creates a new authorization or runs commands here. */
  public void recoverAutomaticPublishing(){drainAutoPublish();}
  private String autoPublishFingerprint(String original,BuildAutoPublishService.Target target){return fingerprint(original,"AUTO",target.environment(),target.serverIds());}
  private void validateAutoPublishTarget(Long serviceId,BuildAutoPublishService.Target target,String actor){
    autoPublish.authorize(actor);
    selectedServers(deploymentConfiguration(serviceId,target.environment()),target.serverIds());
  }
  /** Best-effort enqueue after committed build success; failures never change a successful build result. */
  private synchronized void drainAutoPublish(){
    if(autoPublish==null||stopping)return;
    try{
      List<BuildAutoPublishPlan> earlier=new ArrayList<>();
      for(BuildAutoPublishPlan plan:autoPublish.pending()){
        ReleaseTask build=tasks.findById(plan.getBuildTaskId()).orElse(null);
        if(build==null){plan.stop("BLOCKED","原打包任务不存在，自动发布已停止");autoPublish.save(plan);continue;}
        if(build.getStatus()==TaskStatus.FAILED||build.getStatus()==TaskStatus.CANCELLED){autoPublish.stopAfterBuild(build);continue;}
        if(build.getStatus()!=TaskStatus.SUCCESS){earlier.add(plan);continue;}
        boolean blocked=earlier.stream().anyMatch(p->p.getEnvironmentName().equals(plan.getEnvironmentName())&&!Collections.disjoint(p.serverIdList(),plan.serverIdList()));
        if(blocked){plan.waitForEarlierBuild();autoPublish.save(plan);earlier.add(plan);continue;}
        String requestId="auto_build_"+build.getId()+"_followup",note="构建 #"+build.getId()+" 成功后自动发布";
        try{
          if(build.getTaskKind()!=TaskKind.BUILD||build.isSimulate()||"MOBILE".equals(build.getDeliveryType())||!Objects.equals(build.getCreatedBy(),plan.getActor()))throw new IllegalArgumentException("原打包任务与自动发布计划不匹配");
          Artifact artifact=artifacts.findByBuildTaskId(build.getId()).orElseThrow(()->new IllegalArgumentException("本次成功打包的制品不存在"));
          if(!Objects.equals(artifact.getCommitId(),build.getCommitId())||!Objects.equals(artifact.getServiceId(),build.getServiceId()))throw new IllegalArgumentException("制品代码与本次打包不匹配");
          // Reconcile a committed submission after process/response interruption before checking a new grant.
          ReleaseTask deployment=existingDeployment(build.getServiceId(),artifact.getId(),plan.getEnvironmentName(),plan.serverIdList(),note,requestId,plan.getActor());
          if(deployment==null){
            validateAutoPublishTarget(build.getServiceId(),autoPublish.normalize(new BuildAutoPublishService.Target(plan.getEnvironmentName(),plan.serverIdList())),plan.getActor());
            if(pending.size()>=capacity()){
              if(!"READY".equals(plan.getStatus())){plan.ready();autoPublish.save(plan);}
              earlier.add(plan);continue;
            }
            var review=deploymentReview(build.getServiceId(),artifact.getId(),plan.getEnvironmentName(),plan.serverIdList());
            Long linked=review.exactActiveTask(plan.serverIdList()).orElse(null);
            if(linked==null && review.action()==DeploymentReview.Action.CONFIRM) linked=matchingCompletedAutoTarget(review,plan.serverIdList());
            if(linked!=null) {
              plan.linkedToExisting(linked);
              autoPublish.save(plan);
              continue;
            }
            if(review.action()==DeploymentReview.Action.ACTIVE) throw new DeploymentReview.Conflict(review);
            if(review.nodes().stream().anyMatch(n->n.state()==DeploymentReview.State.REPEAT||n.state()==DeploymentReview.State.ROLLBACK))
              throw new IllegalArgumentException("该制品已有部分发布或回退记录，请手动核对发布范围后继续");
            deployment=createDeployment(build.getServiceId(),artifact.getId(),plan.getEnvironmentName(),plan.serverIdList(),note,false,plan.getActor(),requestId);
          }
          plan.linked(deployment.getId());autoPublish.save(plan);
        }catch(IllegalArgumentException|org.springframework.security.access.AccessDeniedException error){
          // A known validation failure requires an explicit new release; never retry it automatically.
          plan.stop("BLOCKED",error.getMessage());autoPublish.save(plan);
        }catch(Exception error){
          // Keep the immutable intent for restart/next-queue recovery. The deployment request key is unique.
          earlier.add(plan);
          org.slf4j.LoggerFactory.getLogger(ReleaseExecutor.class).warn("Build #{} automatic release enqueue deferred; exceptionType={}",build.getId(),error.getClass().getSimpleName());
        }
      }
    }catch(Exception error){org.slf4j.LoggerFactory.getLogger(ReleaseExecutor.class).warn("Automatic release recovery deferred; exceptionType={}",error.getClass().getSimpleName());}
  }

  /** Link only one task which fulfilled the complete original intent, never a subset. */
  private Long matchingCompletedAutoTarget(DeploymentReview.Result review,List<Long> serverIds) {
    if(review.nodes().isEmpty()||review.nodes().stream().anyMatch(n->n.state()!=DeploymentReview.State.REPEAT))return null;
    var completed=review.nodes().getFirst().latestSuccess();
    boolean sameTask=review.nodes().stream().allMatch(n->n.latestSuccess().taskId().equals(completed.taskId()));
    return sameTask && completed.serverIds().equals(serverIds.stream().distinct().sorted().toList())?completed.taskId():null;
  }

  private void upgradeLegacyImageSteps(ReleaseTask task){
    if(task.getTaskKind()!=TaskKind.BUILD||task.getRequestKey()!=null||task.isImageTagUpgraded())return;
    List<ReleaseStep> taskSteps=steps.findByReleaseTaskIdOrderById(task.getId());
    if(taskSteps.stream().anyMatch(step->step.getStepType()==StepType.BUILD_IMAGE&&step.getStatus()==StepStatus.SUCCESS)){
      for(ReleaseStep step:taskSteps)if(step.getStepType()==StepType.BUILD_IMAGE||step.getStepType()==StepType.PUSH_IMAGE){
        step.setStatus(StepStatus.WAITING);step.setStartedAt(null);step.setFinishedAt(null);
        step.appendLog("平台升级：旧镜像标签将重新生成为本任务唯一标签；代码和应用构建结果保留");steps.save(step);
      }
    }
    task.setImageTagUpgraded(true);
  }

  private int capacity(){return Math.max(1,Math.min(100,queueCapacity));}
  private String stepLabel(StepType step,AppService service){
    return switch(step){
      case GIT_PULL -> "拉取代码";
      case BUILD_JAR -> service.getServiceType()==ServiceType.FRONTEND?(BuildConfiguration.imageBuildMode(service)==ImageBuildMode.REPOSITORY_DOCKERFILE?"校验仓库 Dockerfile":"构建 Node 前端"):"构建 Spring Boot JAR";
      case BUILD_IMAGE -> "构建 Docker 镜像";
      case PUSH_IMAGE -> "推送镜像仓库";
      case SSH_CONNECT -> "连接远程服务器";
      case START_SERVICE -> "启动服务";
      case HEALTH_CHECK -> "健康检查";
      case BUILD_AAB -> "构建 Flutter Android AAB";
      case BUILD_APK -> "构建 Flutter Android 完整 APK";
      case VALIDATE_AAB, VALIDATE_APK -> "校验安装包与签名";
      case ARCHIVE_ARTIFACT -> "保存安装包";
    };
  }
  private void ensureCapacity(){if(stopping)throw new IllegalArgumentException("平台正在停止，请稍后重试");if(pending.size()>=capacity())throw new IllegalArgumentException("任务队列已满，请等待已有任务完成后再提交");}
  private AppService enabledService(Long id){AppService service=services.findById(id).orElseThrow(()->new IllegalArgumentException("服务不存在"));if(!service.isEnabled())throw new IllegalArgumentException("服务已停用");return service;}
  private void validateBuildConfiguration(AppService service){BuildConfiguration.command(service.getBuildCommand(),service.getServiceType());BuildConfiguration.repositoryPath(service.getWorkDirectory(),"工作目录");BuildConfiguration.repositoryPath(service.getJarPath(),"制品路径");BuildConfiguration.repositoryPath(service.getDockerfilePath(),"Dockerfile 路径");}
  private String mobileBuildEnvironment(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("请先选择 App 构建环境");return normalizeEnvironment(value);}
  private void validateTaskBuildConfiguration(AppService service,ReleaseTask task){
    if((service.getServiceType()==ServiceType.FLUTTER_ANDROID)!= "MOBILE".equals(task.getDeliveryType()))throw new IllegalArgumentException("服务构建类型已改变，请新建打包任务");
    if("MOBILE".equals(task.getDeliveryType())){FlutterBuildConfiguration.fromTask(task);FlutterBuildConfiguration.taskWorkDirectory(task);FlutterBuildConfiguration.verifyRepository(task,service);}
    else {validateBuildConfiguration(service);if(GitHubFrontendBuildService.usesGitHub(task))frontendCloud.validateTask(task,service);if(GitHubBackendBuildService.usesGitHub(task))backendCloud.validateTask(task,service);}
  }
  private ReleaseTask typedTask(Long id,TaskKind kind){ReleaseTask task=tasks.findById(id).orElseThrow(()->new IllegalArgumentException("任务不存在"));if(task.getTaskKind()!=kind)throw new IllegalArgumentException(kind==TaskKind.BUILD?"不是打包任务":"不是发版任务");return task;}
  private void rejectSimulation(boolean simulate){if(simulate)throw new IllegalArgumentException("模拟打包和模拟发布已停用");}
  private String validCommit(String value){if(value==null||!value.matches("[a-fA-F0-9]{40}"))throw new IllegalArgumentException("请重新读取并确认完整的 Git Commit");return value.toLowerCase(Locale.ROOT);}
  private void validateBranch(String value){if(value==null||value.isBlank()||value.length()>120||value.startsWith("-")||value.contains("..")||value.contains("@{")||value.endsWith(".")||value.endsWith("/")||!value.matches("[A-Za-z0-9._/-]+"))throw new IllegalArgumentException("代码分支名称格式不正确");}
  private String requestKey(TaskKind kind,String requestId,String operator){if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{16,80}"))throw new IllegalArgumentException("请求标识无效，请刷新页面后重试");return kind+":"+operator+":"+requestId;}
  private String retryKey(ReleaseTask task,String requestId,String operator){return task.getId()+":"+requestKey(task.getTaskKind(),requestId,operator);}
  private ReleaseTask existing(String key,String fingerprint){ReleaseTask task=tasks.findByRequestKey(key).orElse(null);if(task!=null&&!Objects.equals(task.getRequestFingerprint(),fingerprint))throw new IllegalArgumentException("请求标识已用于其他配置，请刷新后重新提交");return task;}
  private String deploymentFingerprint(Long serviceId,Long artifactId,String environment,List<Long> ids,String note){List<Long> sorted=ids==null?List.of():ids.stream().filter(Objects::nonNull).distinct().sorted().toList();return fingerprint(serviceId,artifactId,normalizeEnvironment(environment),sorted,note);}
  private String fingerprint(Object... values){try{StringBuilder text=new StringBuilder();for(Object value:values){String part=String.valueOf(value);text.append(part.length()).append(':').append(part);}return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
  private String mobileFingerprint(Long serviceId,String branch,String sha,String note,String environment,Long preBuildTaskId){return preBuildTaskId==null?fingerprint(serviceId,branch,sha,note,environment):fingerprint(serviceId,branch,sha,note,environment,preBuildTaskId);}
  private String errorSummary(Exception error){String message=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();return message.length()<=980?message:message.substring(0,180)+System.lineSeparator()+"...（完整错误请查看步骤日志）..."+System.lineSeparator()+message.substring(message.length()-740);}
  private String firstLine(Exception error){String message=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();int newline=message.indexOf('\n');return(newline<0?message:message.substring(0,newline)).replace("\r","");}
  private String withTrailingNewline(String value){if(value==null||value.isEmpty())return "";return value.endsWith("\n")?value:value+System.lineSeparator();}
  private String tail(String value){return value.length()<=BoundedProcessRunner.MAX_OUTPUT_CHARS?value:"[早期日志已截断]\n"+value.substring(value.length()-BoundedProcessRunner.MAX_OUTPUT_CHARS);}
  private String normalizeEnvironment(String value){String env=value==null?"":value.trim().toLowerCase(Locale.ROOT);if(!Set.of("dev","test","prod").contains(env))throw new IllegalArgumentException("目标环境不正确");return env;}
  private ServiceEnvironment deploymentConfiguration(Long serviceId,String environment){ServiceEnvironment config=environments.findByServiceIdAndEnvironmentName(serviceId,normalizeEnvironment(environment)).orElseThrow(()->new IllegalArgumentException("目标环境尚未配置"));if(!config.isEnabled())throw new IllegalArgumentException("目标环境已停用");if(config.getServerIds().isEmpty())throw new IllegalArgumentException("目标环境尚未绑定服务器");for(Long id:config.getServerIds()){DeploymentServer server=servers.findById(id).orElseThrow(()->new IllegalArgumentException("服务器不存在："+id));if(!server.isEnabled())throw new IllegalArgumentException("服务器已停用："+server.getName());}return config;}
  private List<Long> selectedServers(ServiceEnvironment environment,List<Long> ids){List<Long> selected=ids==null?List.of():ids.stream().filter(Objects::nonNull).distinct().toList();if(selected.isEmpty())selected=environment.getServerIds();for(Long id:selected)if(!environment.getServerIds().contains(id))throw new IllegalArgumentException("所选服务器不属于目标环境集群："+id);return selected;}
}
