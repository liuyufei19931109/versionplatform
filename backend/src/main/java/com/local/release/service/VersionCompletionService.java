package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.*;
import com.local.release.security.CredentialSecretCrypto;
import com.local.release.security.MenuPermissions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Administrative closure only: never builds, publishes, modifies Git or changes execution state. */
@Service
public class VersionCompletionService {
  public record Exclusion(Long serviceId,String reason) {}
  public record Input(String environment,String changes,String remaining,List<Exclusion> exclusions) {}
  public record Confirm(String environment,String changes,String remaining,List<Exclusion> exclusions,String summary,String fingerprint) {}
  public record Edit(Long expectedId,String changes,String remaining,String summary) {}
  public record Reopen(Long expectedId,String reason) {}
  public record Counts(int total,int published,int excluded,int unconfirmed) {}
  public record Item(Long serviceId,String serviceName,String serviceKey,ServiceType serviceType,String branch,String outcome,
    String message,String reason,Long taskId,Long artifactId,String commitId,String taskStatus,LocalDateTime finishedAt,
    List<Long> serverIds,boolean scopeComplete,String detailPath) {}
  public record Options(Long versionId,LocalDate versionDate,String branch,String environment,List<Item> items,Counts counts) {}
  public record Preview(Long versionId,LocalDate versionDate,String branch,String environment,List<Item> items,Counts counts,String fingerprint,String summary) {}
  public record Completion(Long id,Long versionId,LocalDate versionDate,String eventType,String environment,String changes,String remaining,
    String summary,String reason,String actor,LocalDateTime createdAt,String completedBy,LocalDateTime completedAt,Counts counts,List<Item> items) {}
  public record Detail(Long versionId,String state,Completion current,List<Completion> history) {}
  public record CompletionState(Long versionId,String state) {}
  public record PublicSummary(Long id,Long versionId,LocalDate versionDate,String state,String environment,String changes,String remaining,
    String summary,String actor,LocalDateTime createdAt,String completedBy,LocalDateTime completedAt,Counts counts) {}
  public record Content(LocalDate versionDate,String environment,String changes,String remaining,String summary,String reason,Counts counts,List<Item> items) {}
  public record Ticket(String purpose,Long versionId,String actor,Instant expiresAt,Long previousId,String inputHash,String snapshotHash) {}
  private record Snapshot(Options options,String freshness,boolean active) {}
  private final VersionCompletionEventRepository events;
  private final VersionBatchRepository versions;
  private final VersionMemberRepository members;
  private final VersionRepositoryRepository repositories;
  private final AppServiceRepository services;
  private final AdminUserRepository users;
  private final ReleaseTaskRepository tasks;
  private final ArtifactRepository artifacts;
  private final MobileDeliveryRepository deliveries;
  private final ServiceEnvironmentRepository environments;
  private final EntityManager entities;
  private final TransactionTemplate transaction;
  private final ObjectMapper json;
  private final CredentialSecretCrypto crypto;
  Clock clock=Clock.system(ZoneId.of("Asia/Shanghai"));

  public VersionCompletionService(VersionCompletionEventRepository events,VersionBatchRepository versions,VersionMemberRepository members,
    VersionRepositoryRepository repositories,AppServiceRepository services,AdminUserRepository users,ReleaseTaskRepository tasks,
    ArtifactRepository artifacts,MobileDeliveryRepository deliveries,ServiceEnvironmentRepository environments,EntityManager entities,
    PlatformTransactionManager manager,ObjectMapper json,CredentialSecretCrypto crypto){
    this.events=events;this.versions=versions;this.members=members;this.repositories=repositories;this.services=services;this.users=users;
    this.tasks=tasks;this.artifacts=artifacts;this.deliveries=deliveries;this.environments=environments;this.entities=entities;
    this.transaction=new TransactionTemplate(manager);this.json=json;this.crypto=crypto;
  }

  public Detail detail(Long id,String operator){
    AdminUser user=user(operator);VersionBatch version=find(id);
    requireDetails(version,user);
    List<Completion> history=events.findByVersionIdOrderByIdDesc(id).stream().map(event->view(event,user)).toList();
    return new Detail(id,state(history.isEmpty()?null:history.getFirst().eventType()),history.isEmpty()?null:history.getFirst(),history);
  }
  public Options options(Long id,String environment,String operator){administrator(operator);return snapshot(find(id),environment(environment),List.of()).options();}
  public List<CompletionState> states(String operator){
    AdminUser user=user(operator);if(!user.isAdmin()&&!MenuPermissions.effective(user.menuPermissionSet()).contains("versions"))throw denied();
    Set<Long> seen=new HashSet<>();List<CompletionState> result=new ArrayList<>();
    for(VersionCompletionEvent event:events.findAllByOrderByIdDesc())if(seen.add(event.getVersionId())){
      VersionBatch version=versions.findById(event.getVersionId()).orElse(null);if(version==null)continue;
      try{requireDetails(version,user);result.add(new CompletionState(version.getId(),state(event.getEventType())));}catch(AccessDeniedException ignored){}
    }
    return List.copyOf(result);
  }

  public Preview preview(Long id,Input request,String operator){
    administrator(operator);Input input=normalize(request);VersionBatch version=find(id);
    VersionCompletionEvent current=current(id);
    if(completed(current))throw new IllegalArgumentException("版本已经完成；可补充小结，或重新打开后再次收尾");
    Snapshot snapshot=snapshot(version,input.environment(),input.exclusions());
    requireIdle(snapshot);
    if(snapshot.options().items().isEmpty())throw new IllegalArgumentException("版本没有服务模块，不能完成");
    Ticket ticket=new Ticket("VERSION_COMPLETION_V1",id,operator,clock.instant().plusSeconds(600),current==null?null:current.getId(),hash(input),snapshot.freshness());
    Options options=snapshot.options();
    return new Preview(id,version.getVersionDate(),version.getBranch(),input.environment(),options.items(),options.counts(),crypto.encrypt(write(ticket)),draft(options,input));
  }

  public Detail complete(Long id,Confirm request,String operator){
    administrator(operator);
    if(request==null)throw new IllegalArgumentException("请先生成并核对版本小结");
    Input input=normalize(new Input(request.environment(),request.changes(),request.remaining(),request.exclusions()));
    String summary=text(request.summary(),8000,true,"请填写公示小结");
    Ticket ticket=ticket(request.fingerprint());
    if(!Objects.equals(ticket.versionId(),id)||!Objects.equals(ticket.actor(),operator)||!Objects.equals(ticket.inputHash(),hash(input)))
      throw new IllegalArgumentException("小结内容或操作者已变化，请重新生成预览");
    transaction.executeWithoutResult(status->{
      VersionBatch version=lock(id);administrator(operator);
      VersionCompletionEvent current=current(id);
      String requestHash=hash(List.of(operator,request.fingerprint(),summary));
      if(current!=null&&"COMPLETE".equals(current.getEventType())&&requestHash.equals(current.getRequestHash()))return;
      if(completed(current)||!Objects.equals(ticket.previousId(),current==null?null:current.getId()))throw new IllegalArgumentException("版本状态已变化，请刷新后重新确认");
      if(!clock.instant().isBefore(ticket.expiresAt()))throw new IllegalArgumentException("版本小结预览已过期，请重新生成");
      Snapshot snapshot=snapshot(version,input.environment(),input.exclusions());
      requireIdle(snapshot);
      if(!Objects.equals(snapshot.freshness(),ticket.snapshotHash()))throw new IllegalArgumentException("服务范围或发布记录已变化，请重新生成预览后确认");
      if(snapshot.options().items().isEmpty())throw new IllegalArgumentException("版本没有服务模块，不能完成");
      if(snapshot.options().counts().unconfirmed()>0&&input.remaining().isBlank())throw new IllegalArgumentException("仍有未确认发布的模块，请填写遗留事项或明确本次不发布的原因");
      Content content=new Content(version.getVersionDate(),input.environment(),input.changes(),input.remaining(),summary,null,snapshot.options().counts(),snapshot.options().items());
      events.saveAndFlush(new VersionCompletionEvent(id,"COMPLETE",operator,operator,now(),requestHash,write(content)));
    });
    return detail(id,operator);
  }

  public Detail edit(Long id,Edit request,String operator){
    administrator(operator);if(request==null)throw new IllegalArgumentException("请填写补充小结");
    String changes=text(request.changes(),4000,true,"请填写本次变更"),remaining=text(request.remaining(),4000,false,"遗留事项过长"),summary=text(request.summary(),8000,true,"请填写公示小结");
    transaction.executeWithoutResult(status->{
      lock(id);administrator(operator);VersionCompletionEvent current=expected(id,request.expectedId());
      if(!completed(current))throw new IllegalArgumentException("版本尚未完成，不能补充已完成小结");
      Content old=content(current);
      if(old.counts().unconfirmed()>0&&remaining.isBlank())throw new IllegalArgumentException("原快照仍有未确认发布的模块，请保留遗留事项说明");
      Content changed=new Content(old.versionDate(),old.environment(),changes,remaining,summary,null,old.counts(),old.items());
      events.saveAndFlush(new VersionCompletionEvent(id,"EDIT",operator,current.getCompletedBy(),current.getCompletedAt(),null,write(changed)));
    });
    return detail(id,operator);
  }

  public Detail reopen(Long id,Reopen request,String operator){
    administrator(operator);if(request==null)throw new IllegalArgumentException("请填写重新打开原因");
    String reason=text(request.reason(),1000,true,"请填写重新打开原因");
    transaction.executeWithoutResult(status->{
      lock(id);administrator(operator);VersionCompletionEvent current=expected(id,request.expectedId());
      if(!completed(current))throw new IllegalArgumentException("版本已经处于进行中");
      Content old=content(current);
      Content reopened=new Content(old.versionDate(),old.environment(),old.changes(),old.remaining(),old.summary(),reason,old.counts(),old.items());
      events.saveAndFlush(new VersionCompletionEvent(id,"REOPEN",operator,current.getCompletedBy(),current.getCompletedAt(),null,write(reopened)));
    });
    return detail(id,operator);
  }

  public PublicSummary publicDetail(Long id,String operator){user(operator);find(id);VersionCompletionEvent current=current(id);return current==null?null:publicView(current);}
  public PublicSummary latest(String operator){
    user(operator);Set<Long> seen=new HashSet<>();
    return events.findAllByOrderByIdDesc().stream().filter(event->seen.add(event.getVersionId())).filter(this::completed)
      .max(Comparator.comparing(VersionCompletionEvent::getCompletedAt).thenComparing(VersionCompletionEvent::getId)).map(this::publicView).orElse(null);
  }

  private Snapshot snapshot(VersionBatch version,String environment,List<Exclusion> exclusions){
    List<VersionMember> membership=members.findByVersionIdOrderByIdAsc(version.getId());
    Map<Long,String> omitted=new TreeMap<>();for(Exclusion exclusion:exclusions)omitted.put(exclusion.serviceId(),exclusion.reason());
    Set<Long> known=new HashSet<>();membership.forEach(member->known.add(member.getServiceId()));
    if(!known.containsAll(omitted.keySet()))throw new IllegalArgumentException("本次不发布的服务不属于此版本，请刷新后重试");
    List<Item> items=new ArrayList<>();List<Object> evidence=new ArrayList<>();boolean active=false;
    for(VersionMember member:membership){
      AppService service=services.findById(member.getServiceId()).orElse(null);
      VersionRepository repository=repositories.findById(member.getRepositoryId()).orElse(null);
      List<ReleaseTask> matching=tasks.findByServiceIdAndTaskKindOrderByCreatedAtDesc(member.getServiceId(),TaskKind.DEPLOY).stream()
        .filter(task->!task.isSimulate()&&environment.equals(task.getEnvironmentName())&&version.getBranch().equals(task.getBranch()))
        .sorted(Comparator.comparing(this::activityTime,Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(ReleaseTask::getId).reversed()).toList();
      ReleaseTask task=matching.isEmpty()?null:matching.getFirst();
      active|=matching.stream().anyMatch(t->Set.of(TaskStatus.PENDING,TaskStatus.RUNNING).contains(t.getStatus()));
      ServiceEnvironment configuration=environments.findByServiceIdAndEnvironmentName(member.getServiceId(),environment).orElse(null);
      Artifact artifact=task==null||task.getArtifactId()==null?null:artifacts.findById(task.getArtifactId()).orElse(null);
      ReleaseTask build=artifact==null?null:tasks.findById(artifact.getBuildTaskId()).orElse(null);
      MobileDelivery delivery=task==null?null:deliveries.findByReleaseTaskId(task.getId()).orElse(null);
      List<Long> nodeIds=task==null?List.of():task.targetServerIdList().stream().sorted().toList();
      boolean mobile=member.getServiceType()==ServiceType.FLUTTER_ANDROID;
      boolean scope=mobile||task!=null&&("prod".equals(environment)&&task.getProductionScopeComplete()!=null?Boolean.TRUE.equals(task.getProductionScopeComplete()):configuration!=null&&!nodeIds.isEmpty()&&new HashSet<>(nodeIds).equals(new HashSet<>(configuration.getServerIds())));
      String message=null;
      if(service==null||service.getServiceType()!=member.getServiceType()||repository==null||!sameRepository(repository,service))message="服务或仓库配置已变化，请人工核对本次范围";
      else if(task==null)message="没有此分支在所选环境的真实发布记录";
      else if(task.getStatus()!=TaskStatus.SUCCESS)message=switch(task.getStatus()){
        case PENDING,RUNNING -> "发布仍在等待或执行中，不能视为已发布";
        case WAITING_CONFIRMATION -> "安装包等待外部平台确认发布";
        case FAILED -> "最近一次发布失败，请核对后记录遗留事项";
        default -> "最近一次发布尚未成功";
      };
      else if(task.getFinishedAt()==null||artifact==null||build==null||build.getTaskKind()!=TaskKind.BUILD||build.getStatus()!=TaskStatus.SUCCESS||build.isSimulate()
        ||!artifact.getServiceId().equals(member.getServiceId())||!Objects.equals(artifact.getBranch(),version.getBranch())||!Objects.equals(artifact.getCommitId(),task.getCommitId())
        ||!Objects.equals(build.getServiceId(),member.getServiceId())||!Objects.equals(build.getCommitId(),artifact.getCommitId())||!Objects.equals(build.getBranch(),version.getBranch()))message="发布对应制品或成功构建信息不完整，需人工确认";
      else if(mobile&&(!"MOBILE".equals(task.getDeliveryType())||delivery==null||!Objects.equals(delivery.getArtifactId(),task.getArtifactId())||!"SUCCESS".equals(delivery.getConfirmationStatus())||delivery.getConfirmedAt()==null))message="App 尚无有效的外部发布成功确认";
      else if(!mobile&&"MOBILE".equals(task.getDeliveryType()))message="发布方式与服务类型不一致，请人工核对";
      else if(!scope)message="本次成功记录未确认覆盖全部目标节点，请核对发布范围";
      String outcome=message==null?"PUBLISHED":"UNCONFIRMED",reason=omitted.get(member.getServiceId());
      if(reason!=null){if("PUBLISHED".equals(outcome))throw new IllegalArgumentException("已确认发布成功的模块不能标为本次不发布");outcome="EXCLUDED";}
      items.add(new Item(member.getServiceId(),member.getServiceName(),member.getServiceKey(),member.getServiceType(),version.getBranch(),outcome,
        message==null?mobile?"外部平台已确认发布成功":"部署及健康检查成功":message,reason,task==null?null:task.getId(),task==null?null:task.getArtifactId(),task==null?null:task.getCommitId(),task==null?null:task.getStatus().name(),task==null?null:task.getFinishedAt(),nodeIds,scope,
        task==null?null:mobile?"/apps/releases/"+task.getId():"/releases/"+task.getId()));
      // Include attempts/retries and underlying state, not only visible counts, to reject a stale confirmation.
      evidence.add(Arrays.asList(member.getId(),member.getServiceId(),member.getRepositoryId(),service==null?null:service.getUpdatedAt(),repository==null?null:repository.getUpdatedAt(),
        configuration==null?null:configuration.getUpdatedAt(),configuration==null?null:configuration.getServerIds().stream().sorted().toList(),
        matching.stream().map(t->Arrays.asList(t.getId(),t.getStatus(),t.getUpdatedAt(),t.getArtifactId(),t.getCommitId(),t.getStartedAt(),t.getFinishedAt(),t.getTargetServerIds())).toList(),
        artifact==null?null:artifact.getUpdatedAt(),build==null?null:Arrays.asList(build.getId(),build.getStatus(),build.getUpdatedAt()),
        delivery==null?null:Arrays.asList(delivery.getId(),delivery.getUpdatedAt(),delivery.getConfirmationStatus(),delivery.getConfirmedAt())));
    }
    Options options=new Options(version.getId(),version.getVersionDate(),version.getBranch(),environment,List.copyOf(items),counts(items));
    return new Snapshot(options,hash(Arrays.asList(options,evidence)),active);
  }

  private LocalDateTime activityTime(ReleaseTask task){return task.getStartedAt()==null?task.getCreatedAt():task.getStartedAt();}
  private boolean sameRepository(VersionRepository repository,AppService service){try{return Objects.equals(repository.getRepositoryIdentity(),PolicyGitRepository.identity(service.getGitRepo()));}catch(Exception ignored){return false;}}
  private Counts counts(List<Item> items){return new Counts(items.size(),(int)items.stream().filter(i->"PUBLISHED".equals(i.outcome())).count(),(int)items.stream().filter(i->"EXCLUDED".equals(i.outcome())).count(),(int)items.stream().filter(i->"UNCONFIRMED".equals(i.outcome())).count());}
  private String draft(Options options,Input input){
    Counts c=options.counts();String label=switch(input.environment()){case "prod"->"生产";case "test"->"预生产";default->"开发";};
    return options.versionDate()+" 版本小结（"+label+"）\n\n本次变更\n"+input.changes()+"\n\n发布范围："+c.total()+" 个模块，已确认发布 "+c.published()+" 个，本次不发布 "+c.excluded()+" 个，待确认 "+c.unconfirmed()+" 个。"
      +(input.exclusions().isEmpty()?"":"\n\n本次不发布安排\n"+input.exclusions().stream().map(Exclusion::reason).distinct().map(reason->"- "+reason).collect(java.util.stream.Collectors.joining("\n")))
      +(input.remaining().isBlank()?"":"\n\n遗留事项\n"+input.remaining());
  }
  private void requireIdle(Snapshot snapshot){if(snapshot.active())throw new IllegalArgumentException("本版本在所选环境仍有排队或执行中的发布，请等待结束或取消后再完成版本");}
  private Input normalize(Input request){
    if(request==null)throw new IllegalArgumentException("请填写版本小结内容");
    List<Exclusion> exclusions=new ArrayList<>();Set<Long> ids=new HashSet<>();
    if(request.exclusions()!=null){if(request.exclusions().size()>500)throw new IllegalArgumentException("本次不发布的模块过多");for(Exclusion exclusion:request.exclusions()){
      if(exclusion==null||exclusion.serviceId()==null||exclusion.serviceId()<=0||!ids.add(exclusion.serviceId()))throw new IllegalArgumentException("本次不发布的模块有重复或无效记录");
      exclusions.add(new Exclusion(exclusion.serviceId(),text(exclusion.reason(),500,true,"每个不发布的模块都必须填写原因")));
    }}
    exclusions.sort(Comparator.comparing(Exclusion::serviceId));
    return new Input(environment(request.environment()),text(request.changes(),4000,true,"请填写本次变更"),text(request.remaining(),4000,false,"遗留事项过长"),List.copyOf(exclusions));
  }
  private Ticket ticket(String value){
    try{if(value==null||value.length()>10000)throw new IllegalArgumentException();Ticket result=json.readValue(crypto.decrypt(value),Ticket.class);
      if(!"VERSION_COMPLETION_V1".equals(result.purpose())||result.expiresAt()==null)throw new IllegalArgumentException();return result;
    }catch(Exception invalid){throw new IllegalArgumentException("小结预览凭证无效，请重新生成预览");}
  }
  private Completion view(VersionCompletionEvent event,AdminUser user){
    Content c=content(event);List<Item> visible=c.items().stream().filter(item->visible(user,item)).toList();
    return new Completion(event.getId(),event.getVersionId(),c.versionDate(),event.getEventType(),c.environment(),c.changes(),c.remaining(),c.summary(),c.reason(),event.getActor(),event.getCreatedAt(),event.getCompletedBy(),event.getCompletedAt(),c.counts(),visible);
  }
  private PublicSummary publicView(VersionCompletionEvent event){Content c=content(event);return new PublicSummary(event.getId(),event.getVersionId(),c.versionDate(),state(event.getEventType()),c.environment(),c.changes(),c.remaining(),c.summary(),event.getActor(),event.getCreatedAt(),event.getCompletedBy(),event.getCompletedAt(),c.counts());}
  private void requireDetails(VersionBatch version,AdminUser user){
    if(user.isAdmin())return;
    if(!MenuPermissions.effective(user.menuPermissionSet()).contains("versions")||members.findByVersionIdOrderByIdAsc(version.getId()).stream().noneMatch(member->{AppService current=services.findById(member.getServiceId()).orElse(null);return current!=null&&familyVisible(user,member.getServiceType())&&familyVisible(user,current.getServiceType());}))throw denied();
  }
  private boolean visible(AdminUser user,Item item){if(user.isAdmin())return true;AppService current=services.findById(item.serviceId()).orElse(null);return current!=null&&familyVisible(user,item.serviceType())&&familyVisible(user,current.getServiceType());}
  private boolean familyVisible(AdminUser user,ServiceType type){Set<String> permissions=MenuPermissions.effective(user.menuPermissionSet());return type==ServiceType.FLUTTER_ANDROID?permissions.contains("apps"):permissions.stream().anyMatch(Set.of("services","builds","release")::contains);}
  private VersionCompletionEvent expected(Long id,Long expected){VersionCompletionEvent current=current(id);if(current==null||!Objects.equals(current.getId(),expected))throw new IllegalArgumentException("小结已被其他操作更新，请刷新后重试");return current;}
  private VersionCompletionEvent current(Long id){List<VersionCompletionEvent> list=events.findByVersionIdOrderByIdDesc(id);return list.isEmpty()?null:list.getFirst();}
  private boolean completed(VersionCompletionEvent event){return event!=null&&!"REOPEN".equals(event.getEventType());}
  private String state(String eventType){return eventType==null||"REOPEN".equals(eventType)?"OPEN":"COMPLETED";}
  private VersionBatch find(Long id){if(id==null)throw new IllegalArgumentException("版本不存在");return versions.findById(id).orElseThrow(()->new IllegalArgumentException("版本不存在"));}
  private VersionBatch lock(Long id){VersionBatch version=entities.find(VersionBatch.class,id,LockModeType.PESSIMISTIC_WRITE);if(version==null)throw new IllegalArgumentException("版本不存在");return version;}
  private AdminUser user(String operator){return users.findByUsername(operator).filter(AdminUser::isEnabled).orElseThrow(VersionCompletionService::denied);}
  private AdminUser administrator(String operator){AdminUser user=user(operator);if(!user.isAdmin())throw denied();return user;}
  private static AccessDeniedException denied(){return new AccessDeniedException("没有版本收尾操作权限");}
  private String environment(String value){String normalized=value==null||value.isBlank()?"prod":value.trim();if(!Set.of("dev","test","prod").contains(normalized))throw new IllegalArgumentException("请选择开发、预生产或生产环境");return normalized;}
  private String text(String value,int max,boolean required,String message){String result=value==null?"":value.trim();if(result.length()>max||required&&result.isBlank())throw new IllegalArgumentException(message);return result;}
  private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai"));}
  private Content content(VersionCompletionEvent event){try{return json.readValue(event.getContentJson(),Content.class);}catch(Exception error){throw new IllegalStateException("版本小结快照无法读取",error);}}
  private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException("版本小结无法保存",error);}}
  private String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
}
