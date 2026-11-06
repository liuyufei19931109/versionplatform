package com.local.release.service;

import com.fasterxml.jackson.databind.*;
import com.local.release.domain.*;
import com.local.release.repo.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.local.release.domain.ModelEnums.*;

/** GitHub deliveries request an observation only. This component has no build/deploy/merge command dependency. */
@Service
public class GitHubCodeUpdateService {
  private static final Logger log=LoggerFactory.getLogger(GitHubCodeUpdateService.class);
  public record Receipt(String status){}
  public record Environment(String environmentName, String status, Long buildTaskId, Long artifactId) {
    Environment(String name, String status, Long taskId) { this(name, status, taskId, null); }
  }
  public record Item(Long serviceId,String branch,String status,String headSha,String title,String actor,Instant updatedAt,Instant checkedAt,String eventType,String eventUrl,String message,List<Environment> environments,String buildStatus,String baselineSha,Long buildTaskId,Long artifactId){}
  public record Updates(boolean enabled,List<Item> items){}
  private record Key(String identity,String branch){}
  private record Managed(String identity,String branch,String repository,String fullName,List<Long> memberIds){}
  private record Job(Long id,Key key,long revision,String repository,List<Long> memberIds){}
  private final GitHubWebhookSettings settings;
  private final GitHubWebhookDeliveryRepository deliveries;
  private final GitHubBranchObservationRepository observations;
  private final AppServiceRepository services;
  private final VersionBatchRepository versions;
  private final VersionRepositoryRepository repositories;
  private final VersionMemberRepository members;
  private final VersionManagementService management;
  private final ReleaseTaskRepository tasks;
  private final ArtifactRepository artifacts;
  private final MobileArtifactRepository mobile;
  private final ProductionMergeRepository merges;
  private final GitHubBranchReader git;
  private final GitHubEventService events;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final Object stateLock=new Object(),workerLock=new Object();
  private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("github-observations").factory());
  private final AtomicBoolean queued=new AtomicBoolean();
  private volatile boolean started,stopping;
  Clock clock=Clock.systemUTC();

  public GitHubCodeUpdateService(GitHubWebhookSettings settings,GitHubWebhookDeliveryRepository deliveries,GitHubBranchObservationRepository observations,
      AppServiceRepository services,VersionBatchRepository versions,VersionRepositoryRepository repositories,VersionMemberRepository members,VersionManagementService management,
      ReleaseTaskRepository tasks,ArtifactRepository artifacts,MobileArtifactRepository mobile,ProductionMergeRepository merges,GitHubBranchReader git,ObjectMapper json,PlatformTransactionManager manager,GitHubEventService events){
    this.settings=settings;this.deliveries=deliveries;this.observations=observations;this.services=services;this.versions=versions;this.repositories=repositories;this.members=members;this.management=management;
    this.tasks=tasks;this.artifacts=artifacts;this.mobile=mobile;this.merges=merges;this.git=git;this.json=json;this.tx=new TransactionTemplate(manager);this.events=events;
  }

  public Receipt receive(String deliveryId,String event,String signature,byte[] body){
    settings.verify(signature,body);
    if(deliveryId==null||!deliveryId.matches("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")||event==null||!event.matches("[a-z_]{1,40}"))throw invalid(400,"GITHUB_HEADER_INVALID","Webhook 请求标识无效");
    JsonNode payload;
    try{payload=json.readTree(body);if(payload==null||!payload.isObject())throw new IllegalArgumentException();}catch(Exception error){throw invalid(400,"GITHUB_JSON_INVALID","Webhook JSON 格式无效");}
    String hash=sha256(body);Receipt result;
    synchronized(stateLock){result=tx.execute(status->accept(deliveryId.toLowerCase(Locale.ROOT),event,hash,payload));}
    if(result!=null&&"ACCEPTED".equals(result.status()))enqueue();return result;
  }

  private Receipt accept(String id,String event,String hash,JsonNode payload){
    GitHubWebhookDelivery prior=deliveries.findByDeliveryId(id).orElse(null);
    if(prior!=null){if(!prior.getBodySha256().equals(hash)||!prior.getEventType().equals(event))throw invalid(409,"GITHUB_DELIVERY_CONFLICT","相同投递标识的请求内容不一致");return new Receipt("DUPLICATE");}
    String owner=text(payload.path("repository").path("owner").path("login"));
    String organization=text(payload.path("organization").path("login"));
    if("ping".equals(event)){
      if(!settings.owner().equalsIgnoreCase(owner)&&!settings.owner().equalsIgnoreCase(organization))throw invalid(403,"GITHUB_OWNER_DENIED","Webhook 组织不在允许范围");
      if(!owner.isEmpty()&&!settings.owner().equalsIgnoreCase(owner)||!organization.isEmpty()&&!settings.owner().equalsIgnoreCase(organization))throw invalid(403,"GITHUB_OWNER_DENIED","Webhook 组织不在允许范围");
      deliveries.saveAndFlush(new GitHubWebhookDelivery(id,hash,event,"PING"));return new Receipt("PING");
    }
    if(!settings.owner().equalsIgnoreCase(owner)||!organization.isEmpty()&&!settings.owner().equalsIgnoreCase(organization))throw invalid(403,"GITHUB_OWNER_DENIED","Webhook 组织不在允许范围");
    String fullName=text(payload.path("repository").path("full_name")).toLowerCase(Locale.ROOT);
    if(!fullName.matches(java.util.regex.Pattern.quote(settings.owner())+"/[a-z0-9_.-]{1,100}"))throw invalid(400,"GITHUB_REPOSITORY_INVALID","Webhook 仓库信息无效");
    List<AppService> trusted=services.findAll().stream().filter(s->fullName.equals(githubName(s.getGitRepo()))).toList();
    if(trusted.isEmpty())return new Receipt("IGNORED");
    String branch,before=null,sha,title,actor,url;Instant time=null;boolean recorded=false;
    if("pull_request".equals(event)){
      JsonNode pr=payload.path("pull_request");
      if(!"closed".equals(text(payload.path("action")))||!pr.path("merged").isBoolean()||!pr.path("merged").booleanValue())return new Receipt("IGNORED");
      if(!fullName.equals(text(pr.path("base").path("repo").path("full_name")).toLowerCase(Locale.ROOT)))return new Receipt("IGNORED");
      branch=text(pr.path("base").path("ref"));sha=commit(text(pr.path("merge_commit_sha")));
      if(sha==null)return new Receipt("IGNORED");
      title=safe(text(pr.path("title")),300);actor=actor(text(pr.path("merged_by").path("login")));if(actor==null)actor=actor(text(payload.path("sender").path("login")));
      long number=pr.path("number").asLong(payload.path("number").asLong(0));url=number>0?"https://github.com/"+fullName+"/pull/"+number:null;time=time(text(pr.path("merged_at")));
    }else if("push".equals(event)){
      String ref=text(payload.path("ref"));if(!ref.startsWith("refs/heads/"))return new Receipt("IGNORED");branch=ref.substring(11);
      if(!GitHubEventService.validBranch(branch))return new Receipt("IGNORED");
      before=commit(text(payload.path("before")));sha=commit(text(payload.path("after")));
      boolean deleted=payload.path("deleted").isBoolean()&&payload.path("deleted").booleanValue();if(sha==null&&!deleted)return new Receipt("IGNORED");
      title=safe(text(payload.path("head_commit").path("message")),300);actor=actor(text(payload.path("sender").path("login")));url=sha==null?null:"https://github.com/"+fullName+"/commit/"+sha;
      time=time(text(payload.path("head_commit").path("timestamp")));
      if(!deleted&&sha!=null){events.record(id,fullName,PolicyGitRepository.identity(trusted.getFirst().getGitRepo()),branch,before,sha,title,actor,clock.instant());recorded=true;}
    }else return new Receipt("IGNORED");
    if("master".equals(branch)){
      if(!"push".equals(event))return new Receipt("IGNORED");
      String identity=PolicyGitRepository.identity(trusted.getFirst().getGitRepo());
      GitHubWebhookDelivery delivery=new GitHubWebhookDelivery(id,hash,event,"MASTER_CHECK");delivery.details(identity,branch,before,sha,null,actor,null,time);
      Long merge=platformMerge(delivery);if(merge!=null)delivery.platformMerge(merge);deliveries.saveAndFlush(delivery);return new Receipt("SILENT");
    }
    if(!branch.matches("release[0-9]{8}"))return recorded?recorded(id,hash,event):new Receipt("IGNORED");
    List<Managed> selected=managed().values().stream().filter(m->m.fullName().equals(fullName)&&m.branch().equals(branch)).toList();
    if(selected.isEmpty())return recorded?recorded(id,hash,event):new Receipt("IGNORED");
    // One repository/ref observation is shared by PR and push; no duplicate notification row is created.
    for(Managed managed:selected){
      GitHubBranchObservation observation=observations.findByRepositoryIdentityAndBranch(managed.identity(),branch).orElse(null);
      if(observation==null)observation=new GitHubBranchObservation(managed.identity(),branch);else observation.request();observations.saveAndFlush(observation);
    }
    GitHubWebhookDelivery delivery=new GitHubWebhookDelivery(id,hash,event,"ACCEPTED");delivery.details(selected.getFirst().identity(),branch,before,sha,title,actor,url,time);deliveries.saveAndFlush(delivery);
    return new Receipt("ACCEPTED");
  }

  private Receipt recorded(String id,String hash,String event){deliveries.saveAndFlush(new GitHubWebhookDelivery(id,hash,event,"EVENT_ONLY"));return new Receipt("RECORDED");}

  @EventListener(ApplicationReadyEvent.class) public void start(){
    if(!settings.enabled()||started||stopping)return;started=true;
    worker.scheduleWithFixedDelay(this::quietReconcile,15,15,TimeUnit.SECONDS);
  }
  private void enqueue(){if(!started||stopping||!queued.compareAndSet(false,true))return;try{worker.execute(()->{try{quietReconcile();}finally{queued.set(false);}});}catch(RejectedExecutionException stopped){queued.set(false);}}
  private void quietReconcile(){
    try{reconcile();}
    catch(Exception failure){logReconcileFailure(failure);/* Durable revisions remain pending for the next pass. */}
    catch(Error failure){logReconcileFailure(failure);throw failure;}
  }
  private static void logReconcileFailure(Throwable failure){
    List<String> types=new ArrayList<>(),locations=new ArrayList<>();
    for(Throwable cause=failure;cause!=null&&types.size()<4;cause=cause.getCause()){
      types.add(cause.getClass().getName());
      Arrays.stream(cause.getStackTrace()).filter(frame->frame.getClassName().startsWith("com.local.release.")).limit(3)
        .forEach(frame->locations.add(frame.getClassName()+"#"+frame.getMethodName()+":"+frame.getLineNumber()));
    }
    // Never pass the Throwable/message to a logger: Git/SQL exception messages may contain credentials.
    log.warn("GITHUB_RECONCILE_FAILED types={} platformLocations={}",types,locations);
  }

  /** Network work is serial and outside all database transactions and servlet threads. */
  public void reconcile(){
    if(!settings.enabled()||stopping)return;
    synchronized(workerLock){
      Map<Key,Managed> managed=managed();
      synchronized(stateLock){tx.executeWithoutResult(status->{
        Instant stale=clock.instant().minusSeconds(300);
        for(var entry:managed.entrySet()){
          GitHubBranchObservation observation=observations.findByRepositoryIdentityAndBranch(entry.getKey().identity(),entry.getKey().branch()).orElse(null);
          if(observation==null)observations.save(new GitHubBranchObservation(entry.getKey().identity(),entry.getKey().branch()));
          else if(!observation.isPending()&&(observation.getCheckedAt()==null||observation.getCheckedAt().isBefore(stale)||members.findAllById(entry.getValue().memberIds()).stream().anyMatch(m->needsFreshObservation(m,observation)))){observation.request();observations.save(observation);}
        }
        // A GitHub callback can arrive before ProductionMerge has persisted its after-SHA.
        for(GitHubWebhookDelivery delivery:deliveries.findTop100ByStatusOrderByIdDesc("MASTER_CHECK")){Long taskId=platformMerge(delivery);if(taskId!=null){delivery.platformMerge(taskId);deliveries.save(delivery);}}
      });}
      List<Job> jobs;
      synchronized(stateLock){jobs=observations.findAll().stream().filter(GitHubBranchObservation::isPending).sorted(Comparator.comparing(GitHubBranchObservation::getCheckedAt,Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(GitHubBranchObservation::getId)).filter(o->managed.containsKey(new Key(o.getRepositoryIdentity(),o.getBranch()))).limit(5)
        .map(o->{Managed selected=managed.get(new Key(o.getRepositoryIdentity(),o.getBranch()));return new Job(o.getId(),new Key(o.getRepositoryIdentity(),o.getBranch()),o.getRequestedRevision(),selected.repository(),List.copyOf(selected.memberIds()));}).toList();}
      long passDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
      for(Job job:jobs){
        if(stopping||Thread.currentThread().isInterrupted()||System.nanoTime()>=passDeadline)return;
        String head=null,error=null;Instant readStarted=clock.instant();
        try{head=git.head(job.repository(),job.key().branch());}catch(Exception failure){if(failure instanceof InterruptedException){Thread.currentThread().interrupt();return;}error="核对远程分支失败，请检查 Git 凭据和网络；保留上次观察结果";}
        String observed=head,problem=error;
        synchronized(stateLock){tx.executeWithoutResult(status->{
          GitHubBranchObservation row=observations.findById(job.id()).orElse(null);
          // A new event arrived while ls-remote ran. Discard this result and retry its durable newer revision.
          if(row==null||row.getRequestedRevision()!=job.revision())return;
          if(problem==null)row.verified(observed,job.revision(),readStarted);else row.failed(job.revision(),clock.instant(),problem);observations.saveAndFlush(row);
          // Upgrade existing members only from a fresh, confirmed remote HEAD. Never consume old cached observations.
          if(problem==null&&observed!=null)for(Long memberId:job.memberIds())members.initializeCodeWatchBaseline(memberId,observed,readStarted);
        });}
      }
    }
  }

  public Updates updates(Long versionId,String operator){
    VersionManagementService.View view=management.detail(versionId,operator); // Authoritative menu + object/family visibility before reading notifications.
    if(!settings.enabled())return new Updates(false,List.of());
    Map<Long,VersionMember> selected=new HashMap<>();members.findByVersionIdOrderByIdAsc(versionId).forEach(m->selected.put(m.getServiceId(),m));
    Map<Long,VersionRepository> repos=new HashMap<>();repositories.findByVersionId(versionId).forEach(r->repos.put(r.getId(),r));
    List<Item> items=new ArrayList<>();
    for(var visible:view.items()){
      AppService service=services.findById(visible.serviceId()).orElse(null);VersionMember member=selected.get(visible.serviceId());VersionRepository repository=member==null?null:repos.get(member.getRepositoryId());
      String fullName=service==null?null:githubName(service.getGitRepo());
      if(service==null||!service.isEnabled()||member==null||repository==null||service.getServiceType()!=member.getServiceType()||!sameIdentity(service,repository)){
        items.add(empty(visible.serviceId(),view.branch(),"SERVICE_CHANGED","服务已停用、删除或仓库配置已变化，请先核对服务配置"));continue;
      }
      if(fullName==null||!"SUCCESS".equals(repository.getStatus())){items.add(empty(service.getId(),view.branch(),"UNKNOWN",fullName==null?"当前仓库不在 GitHub 联动范围":"分支尚未成功创建，暂不判断打包状态"));continue;}
      GitHubBranchObservation observation=observations.findByRepositoryIdentityAndBranch(repository.getRepositoryIdentity(),view.branch()).orElse(null);
      if(observation==null){items.add(empty(service.getId(),view.branch(),"UNKNOWN","尚未核对远程分支，请稍后刷新"));continue;}
      String head=observation.getHeadSha(),state,message,buildStatus="UNKNOWN",baseline=member.getCodeWatchBaselineSha();
      List<Environment> environments=List.of();
      Environment match = null;
      if(observation.isPending()){state="UNKNOWN";message="正在核对远程分支，尚不能判断最新代码的打包状态";}
      else if("ERROR".equals(observation.getSyncStatus())){state="SYNC_ERROR";message=observation.getErrorMessage();}
      else if("DELETED".equals(observation.getSyncStatus())){state="BRANCH_DELETED";message="远程版本分支已删除";}
      else if(head==null){state="UNKNOWN";message="尚未取得远程分支 Commit";}
      else if(needsFreshObservation(member,observation)){state="UNKNOWN";message="正在核对关联后的远程分支，请稍后刷新";}
      else if(service.getServiceType()==ServiceType.FLUTTER_ANDROID){
        List<Environment> envs=new ArrayList<>();
        for(String env:List.of("dev","test","prod"))try{FlutterBuildConfiguration.forEnvironment(service,env);envs.add(buildState(service,view.branch(),head,env));}catch(IllegalArgumentException ignored){}
        environments=List.copyOf(envs);buildStatus=aggregate(envs);state=watchStatus(buildStatus,baseline,head);message=envs.isEmpty()?"App 尚未配置可用构建环境":message(state);
      } else {
        match = buildState(service, view.branch(), head, null);
        buildStatus = match.status();
        state = watchStatus(buildStatus, baseline, head);
        message = message(state);
      }
      if("BUILT".equals(state)&&!Objects.equals(baseline,head)&&acknowledgeBuilt(member,observation,head))baseline=head;
      GitHubWebhookDelivery event=head==null?null:metadata(repository.getRepositoryIdentity(),view.branch(),head);
      items.add(new Item(service.getId(),view.branch(),state,head,event==null?null:event.getTitle(),event==null?null:event.getActor(),event!=null&&event.getEventTime()!=null?event.getEventTime():observation.getHeadUpdatedAt(),observation.getCheckedAt(),event==null?"SYNC":"pull_request".equals(event.getEventType())?"MERGED":"PUSH",event==null?(head==null?null:"https://github.com/"+fullName+"/commit/"+head):event.getEventUrl(),message,environments,buildStatus,baseline,match==null?null:match.buildTaskId(),match==null?null:match.artifactId()));
    }
    return new Updates(true,List.copyOf(items));
  }

  private Environment buildState(AppService service,String branch,String head,String env){
    boolean app=service.getServiceType()==ServiceType.FLUTTER_ANDROID;
    List<ReleaseTask> builds=tasks.findByServiceIdAndTaskKindAndBranchAndCommitIdAndSimulateFalseOrderByIdDesc(service.getId(),TaskKind.BUILD,branch,head).stream()
      .filter(t->app?"MOBILE".equals(t.getDeliveryType())&&Objects.equals(env,t.getEnvironmentName()):!"MOBILE".equals(t.getDeliveryType())).toList();
    // A successful artifact remains usable even if someone later retries an identical commit and fails.
    for (ReleaseTask task : builds) {
      if (task.getStatus() != TaskStatus.SUCCESS) continue;
      Artifact artifact = artifacts.findByBuildTaskId(task.getId()).orElse(null);
      if (validArtifact(artifact,service,task,head,branch,env)) return new Environment(env,"BUILT",task.getId(),artifact.getId());
    }
    for(ReleaseTask task:builds)if(Set.of(TaskStatus.PENDING,TaskStatus.RUNNING).contains(task.getStatus()))return new Environment(env,"BUILDING",task.getId());
    if(!builds.isEmpty()&&Set.of(TaskStatus.FAILED,TaskStatus.CANCELLED).contains(builds.getFirst().getStatus()))return new Environment(env,"BUILD_FAILED",builds.getFirst().getId());
    return new Environment(env,"PENDING_BUILD",null);
  }
  private boolean validArtifact(Artifact artifact,AppService service,ReleaseTask task,String head,String branch,String env){
    if(artifact==null||!Objects.equals(artifact.getServiceId(),service.getId())||!head.equals(artifact.getCommitId())||!branch.equals(artifact.getBranch()))return false;
    if(env==null)return artifact.getImageUrl()!=null&&!artifact.getImageUrl().isBlank();
    MobileArtifact metadata=mobile.findByBuildTaskId(task.getId()).orElse(null);
    if(metadata==null||!artifact.getId().equals(metadata.getArtifactId())||!service.getId().equals(metadata.getServiceId())||!head.equals(metadata.getCommitId())||!env.equals(metadata.getBuildEnvironmentName())||metadata.getSizeBytes()<=0)return false;
    try{var config=FlutterBuildConfiguration.fromTask(task);return env.equals(config.environmentName())&&config.buildMode().equals(metadata.getBuildMode())&&config.applicationId().equals(metadata.getApplicationId())&&config.expectedCertificateSha256().equals(metadata.getCertificateSha256())&&config.outputFormat().equals(metadata.getFormat());}catch(IllegalArgumentException invalid){return false;}
  }
  private static String aggregate(List<Environment> envs){if(envs.isEmpty())return "UNKNOWN";if(envs.stream().allMatch(e->"BUILT".equals(e.status())))return "BUILT";for(String value:List.of("BUILDING","BUILD_FAILED","PENDING_BUILD"))if(envs.stream().anyMatch(e->value.equals(e.status())))return value;return "UNKNOWN";}
  private static String watchStatus(String buildStatus,String baseline,String head){return "PENDING_BUILD".equals(buildStatus)&&Objects.equals(baseline,head)?"NO_UPDATES":buildStatus;}
  private static boolean needsFreshObservation(VersionMember member,GitHubBranchObservation observation){return member.getCodeWatchBaselineSha()==null||member.getCodeWatchStartedAt()!=null&&(observation.getCheckedAt()==null||observation.getCheckedAt().isBefore(member.getCodeWatchStartedAt()));}
  private boolean acknowledgeBuilt(VersionMember member,GitHubBranchObservation observed,String head){
    synchronized(stateLock){return Boolean.TRUE.equals(tx.execute(status->{
      GitHubBranchObservation current=observations.findById(observed.getId()).orElse(null);
      if(current==null||current.isPending()||!"SYNCED".equals(current.getSyncStatus())||!Objects.equals(head,current.getHeadSha())||current.getCheckedRevision()!=observed.getCheckedRevision())return false;
      return members.advanceCodeWatchBaseline(member.getId(),member.getCodeWatchBaselineSha(),head)==1;
    }));}
  }
  private static String message(String status){return switch(status){case "NO_UPDATES"->"暂无新提交；当前 Commit 尚无可用制品，可按需手动打包";case "BUILT"->"当前 Commit 已有成功制品";case "BUILDING"->"当前 Commit 正在打包或排队";case "BUILD_FAILED"->"当前 Commit 的打包未成功，可查看打包记录";case "PENDING_BUILD"->"版本分支有新提交，当前 Commit 尚无可用制品，请按需打包";default->"尚未核对";};}
  private static Item empty(Long id,String branch,String status,String message){return new Item(id,branch,status,null,null,null,null,null,null,null,message,List.of(),"UNKNOWN",null,null,null);}
  private GitHubWebhookDelivery metadata(String identity,String branch,String head){
    List<GitHubWebhookDelivery> events=deliveries.findTop30ByRepositoryIdentityAndBranchAndEventShaOrderByIdDesc(identity,branch,head);
    return events.stream().filter(d->"ACCEPTED".equals(d.getStatus())&&"pull_request".equals(d.getEventType())).findFirst().orElseGet(()->events.stream().filter(d->"ACCEPTED".equals(d.getStatus())).findFirst().orElse(null));
  }
  private Map<Key,Managed> managed(){
    Map<Key,Managed> result=new LinkedHashMap<>();Map<Long,AppService> current=new HashMap<>();services.findAll().forEach(s->current.put(s.getId(),s));
    for(VersionRepository repository:repositories.findByStatusIn(List.of("SUCCESS"))){
      VersionBatch version=versions.findById(repository.getVersionId()).orElse(null);if(version==null||!version.getBranch().matches("release[0-9]{8}"))continue;
      for(VersionMember member:members.findByVersionIdOrderByIdAsc(version.getId())){
        if(!member.getRepositoryId().equals(repository.getId()))continue;AppService service=current.get(member.getServiceId());
        if(service==null||!service.isEnabled()||service.getServiceType()!=member.getServiceType()||!sameIdentity(service,repository))continue;
        String name=githubName(service.getGitRepo());if(name!=null)result.computeIfAbsent(new Key(repository.getRepositoryIdentity(),version.getBranch()),key->new Managed(repository.getRepositoryIdentity(),version.getBranch(),service.getGitRepo(),name,new ArrayList<>())).memberIds().add(member.getId());
      }
    }
    return result;
  }
  private Long platformMerge(GitHubWebhookDelivery delivery){
    if(delivery.getBeforeSha()==null||delivery.getEventSha()==null)return null;
    for(ProductionMerge merge:merges.findByRepositoryIdentityAndBaselineBeforeAndBaselineAfter(delivery.getRepositoryIdentity(),delivery.getBeforeSha(),delivery.getEventSha())){
      if(!Set.of(ProductionMerge.Status.MERGED,ProductionMerge.Status.ALREADY_MERGED).contains(merge.getStatus()))continue;
      ReleaseTask task=tasks.findById(merge.getReleaseTaskId()).orElse(null);
      if(task!=null&&task.getTaskKind()==TaskKind.DEPLOY&&task.getStatus()==TaskStatus.SUCCESS&&!task.isSimulate()&&"prod".equals(task.getEnvironmentName())&&task.getServiceId().equals(merge.getServiceId())&&Objects.equals(task.getCommitId(),merge.getCommitId()))return task.getId();
    }
    return null;
  }
  private static boolean sameIdentity(AppService service,VersionRepository repository){try{return repository.getRepositoryIdentity().equals(PolicyGitRepository.identity(service.getGitRepo()));}catch(IllegalArgumentException invalid){return false;}}
  String githubName(String repository){
    return GitHubEventService.repositoryName(repository,settings.owner());
  }
  private static String commit(String value){return value!=null&&value.matches("(?i)[a-f0-9]{40}|[a-f0-9]{64}")&&!value.matches("0+")?value.toLowerCase(Locale.ROOT):null;}
  private static String text(JsonNode node){return node.isTextual()?node.textValue():"";}
  private static String safe(String value,int max){String result=value.replaceAll("[\\p{Cntrl}]"," ").trim();return result.isBlank()?null:result.substring(0,Math.min(max,result.length()));}
  private static String actor(String actor){return actor.matches("[A-Za-z0-9][A-Za-z0-9\\[\\]_-]{0,79}")?actor:null;}
  private static Instant time(String value){try{return Instant.parse(value);}catch(Exception invalid){try{return OffsetDateTime.parse(value).toInstant();}catch(Exception ignored){return null;}}}
  private static String sha256(byte[] body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));}catch(Exception error){throw new IllegalStateException("Digest unavailable");}}
  private static GitHubWebhookException invalid(int status,String code,String message){return new GitHubWebhookException(status,code,message);}
  @PreDestroy public void stop(){stopping=true;worker.shutdownNow();}
}
