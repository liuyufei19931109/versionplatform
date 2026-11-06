package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.ServiceType;
import com.local.release.repo.*;
import com.local.release.security.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Service
public class VersionManagementService {
  public record Candidate(Long serviceId,String serviceName,String serviceKey,ServiceType serviceType,boolean enabled,boolean hasRepository,String displayIcon) {}
  public record Item(Long id,Long serviceId,String serviceName,String serviceKey,ServiceType serviceType,String branch,
    String baselineSha,String baselineShortSha,String baselineMessage,String status,String errorCode,String message,
    LocalDateTime createdAt,LocalDateTime updatedAt,String displayIcon) {}
  public record View(Long id,LocalDate versionDate,String branch,String createdBy,LocalDateTime createdAt,String status,
    int totalCount,int successCount,int failedCount,int runningCount,boolean canAppend,List<Item> items) {}
  public record PreviewItem(Long serviceId,String serviceName,String serviceKey,ServiceType serviceType,String branch,
    String baselineSha,String baselineShortSha,String baselineMessage,String status,String errorCode,String message,String displayIcon) {}
  public record Preview(String previewId,Long versionId,LocalDate versionDate,String branch,Instant expiresAt,
    List<PreviewItem> items,boolean canConfirm) {}
  /** Only the encrypted repository URL is persisted in previews; never returned in HTTP DTOs. */
  public record PreparedItem(Long serviceId,String serviceName,String serviceKey,ServiceType serviceType,String identity,
    String encryptedRepository,String configurationFingerprint,String baselineSha,String baselineMessage,String status,String errorCode,String message,String displayIcon) {}
  public record Prepared(Long versionId,LocalDate versionDate,String branch,List<PreparedItem> items) {}
  public record LinkTarget(Long serviceId,String serviceName,String serviceKey,ServiceType serviceType,String displayIcon) {}
  public record LinkPreview(String previewId,Long versionId,String branch,String baselineSha,String baselineMessage,
    String headSha,Instant expiresAt,List<LinkTarget> services) {}
  public record LinkContext(Long versionId,Long serviceId,Long repositoryId,String branch,String status,String attemptId,
    String encryptedRepository,Map<Long,String> configurations,List<LinkTarget> services) {}
  public record PreparedLink(String operation,LinkContext context,String baselineSha,String baselineMessage,String headSha) {}
  private record CommitResult(Long versionId,List<Long> operations) {}
  private record Job(Long id,String attemptId,String repository,String branch,String baseline,String operator,List<Long> serviceIds) {}
  private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
  private static final Set<String> ACTIVE=Set.of("PENDING","RUNNING");
  private static final Set<String> LINKABLE=Set.of("CONFLICT","FAILED","UNKNOWN");
  private static final String LINK_EXISTING="LINK_EXISTING";
  private final VersionBatchRepository versions;
  private final VersionRepositoryRepository repositories;
  private final VersionMemberRepository members;
  private final VersionPreviewRepository previews;
  private final AppServiceRepository services;
  private final AdminUserRepository users;
  private final VersionGitRepository git;
  private final CredentialSecretCrypto crypto;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("version-branches").factory());
  private final ExecutorService previewWorker=new ThreadPoolExecutor(3,3,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(200),Thread.ofPlatform().daemon(true).name("version-preview-",0).factory());
  private final Semaphore previewSlots=new Semaphore(2);
  Clock clock=Clock.system(SHANGHAI);
  private volatile boolean stopping;

  public VersionManagementService(VersionBatchRepository versions,VersionRepositoryRepository repositories,
    VersionMemberRepository members,VersionPreviewRepository previews,AppServiceRepository services,
    AdminUserRepository users,VersionGitRepository git,CredentialSecretCrypto crypto,ObjectMapper json,PlatformTransactionManager manager){
    this.versions=versions;this.repositories=repositories;this.members=members;this.previews=previews;
    this.services=services;this.users=users;this.git=git;this.crypto=crypto;this.json=json;this.transaction=new TransactionTemplate(manager);
  }

  public List<Candidate> candidates(String operator){
    AdminUser user=reader(operator);
    return services.findAll().stream().filter(s->visible(user,s.getServiceType())).map(s->new Candidate(s.getId(),s.getName(),s.getServiceKey(),s.getServiceType(),s.isEnabled(),s.getGitRepo()!=null&&!s.getGitRepo().isBlank(),s.getDisplayIcon())).toList();
  }
  public List<View> list(String operator){
    AdminUser user=reader(operator);
    return versions.findAllByOrderByVersionDateDesc().stream().map(v->view(v,user)).filter(v->user.isAdmin()||!v.items().isEmpty()).toList();
  }
  public View latest(String operator){
    AdminUser user=reader(operator);
    if(user.isAdmin())return versions.findFirstByOrderByVersionDateDesc().map(v->view(v,user)).orElse(null);
    if(!visible(user,ServiceType.BACKEND)&&!visible(user,ServiceType.FLUTTER_ANDROID))return null;
    int page=0;
    while(true){
      var candidates=versions.findAllByOrderByVersionDateDesc(org.springframework.data.domain.PageRequest.of(page++,10));
      for(VersionBatch version:candidates){View result=view(version,user);if(!result.items().isEmpty())return result;}
      if(!candidates.hasNext())return null;
    }
  }
  public View detail(Long id,String operator){
    AdminUser user=reader(operator);View result=view(find(id),user);
    if(!user.isAdmin()&&result.items().isEmpty())throw denied();return result;
  }

  public LinkPreview previewLink(Long versionId,Long serviceId,String operator)throws Exception {
    administrator(operator);requireRunning();
    if(!previewSlots.tryAcquire())throw new IllegalArgumentException("正在核对其他版本，请稍后重试");
    try{
      LinkContext context=linkContext(versionId,serviceId);
      VersionGitRepository.Snapshot snapshot=readLinkSnapshot(context);
      if(snapshot.existingBranchSha()==null)throw new IllegalArgumentException("远程分支不存在，请使用核对并重试创建分支");
      PreparedLink prepared=new PreparedLink(LINK_EXISTING,context,snapshot.sha(),snapshot.summary(),snapshot.existingBranchSha());
      administrator(operator);requireRunning();requireUnchanged(prepared);
      String token=UUID.randomUUID().toString();Instant expires=clock.instant().plusSeconds(600);
      previews.saveAndFlush(new VersionPreview(token,operator,expires,json.writeValueAsString(prepared)));
      return new LinkPreview(token,versionId,context.branch(),snapshot.sha(),snapshot.summary(),snapshot.existingBranchSha(),expires,context.services());
    }finally{previewSlots.release();}
  }

  public View linkExisting(Long versionId,String token,String operator)throws Exception {
    administrator(operator);requireRunning();
    VersionPreview preview=linkPreview(token,versionId,operator);
    if(preview.getConsumedVersionId()!=null)return detail(preview.getConsumedVersionId(),operator);
    PreparedLink prepared=json.readValue(preview.getPayload(),PreparedLink.class);
    requireUnchanged(prepared);
    if(!previewSlots.tryAcquire())throw new IllegalArgumentException("正在核对其他版本，请稍后重试");
    try{
      // Read only: linking never invokes Git creation, reset, merge or push.
      VersionGitRepository.Snapshot observed=readLinkSnapshot(prepared.context());
      if(!Objects.equals(prepared.headSha(),observed.existingBranchSha())||!Objects.equals(prepared.baselineSha(),observed.sha()))
        throw new IllegalArgumentException("分支或 master 已变化，请重新核对后确认关联");
      synchronized(this){
        requireRunning();administrator(operator);
        transaction.executeWithoutResult(tx->{
          VersionPreview current=linkPreview(token,versionId,operator);
          if(current.getConsumedVersionId()!=null)return;
          requireUnchanged(prepared);
          VersionRepository repository=repositories.findById(prepared.context().repositoryId()).orElseThrow();
          repository.prepare(prepared.context().encryptedRepository(),prepared.baselineSha(),prepared.baselineMessage(),operator);
          repository.result("SUCCESS",null,"已关联已有分支；核对时提交 "+prepared.headSha()+"；后续打包继续校验最新 master");
          repositories.saveAndFlush(repository);
          initializeCodeWatchBaselines(repository,prepared.headSha());
          current.consume(versionId);previews.saveAndFlush(current);
        });
      }
      return detail(versionId,operator);
    }finally{previewSlots.release();}
  }

  private VersionPreview linkPreview(String token,Long versionId,String operator){
    if(token==null||!token.matches("[0-9a-fA-F-]{36}"))throw new IllegalArgumentException("关联确认信息无效，请重新核对");
    VersionPreview preview=previews.findByToken(token).orElseThrow(()->new IllegalArgumentException("关联确认信息不存在，请重新核对"));
    if(!preview.getOperator().equals(operator))throw denied();
    PreparedLink prepared;
    try{
      prepared=json.readValue(preview.getPayload(),PreparedLink.class);
      if(!LINK_EXISTING.equals(prepared.operation())||prepared.context()==null||!Objects.equals(versionId,prepared.context().versionId()))
        throw new IllegalArgumentException();
    }catch(Exception invalid){throw new IllegalArgumentException("关联确认信息与操作或版本不匹配，请重新核对");}
    if(preview.getConsumedVersionId()==null&&!preview.getExpiresAt().isAfter(clock.instant()))throw new IllegalArgumentException("关联确认已过期，请重新核对");
    return preview;
  }

  private LinkContext linkContext(Long versionId,Long serviceId){
    VersionBatch version=find(versionId);
    VersionMember selected=members.findByVersionIdAndServiceId(versionId,serviceId).orElseThrow(()->new IllegalArgumentException("服务不属于此版本，请刷新后重试"));
    VersionRepository repository=repositories.findById(selected.getRepositoryId()).filter(r->r.getVersionId().equals(versionId)).orElseThrow(()->new IllegalArgumentException("版本仓库关联不存在"));
    if(!LINKABLE.contains(repository.getStatus()))throw new IllegalArgumentException("仅可关联冲突、失败或待核对的分支；请刷新版本状态");
    Map<Long,String> configurations=new LinkedHashMap<>();List<LinkTarget> targets=new ArrayList<>();String encryptedRepository=null;
    for(VersionMember member:members.findByVersionIdOrderByIdAsc(versionId)){
      if(!member.getRepositoryId().equals(repository.getId()))continue;
      AppService current=services.findById(member.getServiceId()).orElseThrow(()->new IllegalArgumentException("服务已不存在，请核对版本配置"));
      if(!repository.getRepositoryIdentity().equals(PolicyGitRepository.identity(Objects.toString(current.getGitRepo(),""))))
        throw new IllegalArgumentException("服务的仓库已变更，无法关联历史版本；请使用新版本");
      configurations.put(current.getId(),fingerprint(current));
      targets.add(new LinkTarget(current.getId(),current.getName(),current.getServiceKey(),current.getServiceType(),current.getDisplayIcon()));
      if(current.getId().equals(serviceId))encryptedRepository=crypto.encrypt(current.getGitRepo());
    }
    return new LinkContext(versionId,serviceId,repository.getId(),version.getBranch(),repository.getStatus(),repository.getAttemptId(),encryptedRepository,Map.copyOf(configurations),List.copyOf(targets));
  }

  private void requireUnchanged(PreparedLink prepared){
    LinkContext expected=prepared.context(),current=linkContext(expected.versionId(),expected.serviceId());
    if(!Objects.equals(expected.repositoryId(),current.repositoryId())||!Objects.equals(expected.branch(),current.branch())
      ||!Objects.equals(expected.status(),current.status())||!Objects.equals(expected.attemptId(),current.attemptId())
      ||!expected.configurations().equals(current.configurations()))
      throw new IllegalArgumentException("服务配置或版本状态已变化，请重新核对后确认关联");
  }
  private VersionGitRepository.Snapshot readLinkSnapshot(LinkContext context)throws Exception{
    try{return git.preview(crypto.decrypt(context.encryptedRepository()),context.branch());}
    catch(VersionGitRepository.Failure failure){throw new IllegalArgumentException(failure.getMessage());}
  }
  private void requireRunning(){if(stopping)throw new IllegalArgumentException("平台正在停止，请稍后重试");}

  public Preview preview(List<Long> serviceIds,Long versionId,String operator)throws Exception {
    return preview(serviceIds,versionId,null,operator);
  }
  public Preview preview(List<Long> serviceIds,Long versionId,LocalDate requestedDate,String operator)throws Exception {
    administrator(operator);if(stopping)throw new IllegalArgumentException("平台正在停止，请稍后重试");
    if(!previewSlots.tryAcquire())throw new IllegalArgumentException("正在核对其他版本，请稍后重试");
    Map<String,Future<VersionGitRepository.Snapshot>> reads=new LinkedHashMap<>();
    try{return preparePreview(serviceIds,versionId,requestedDate,operator,reads);}
    finally{reads.values().stream().filter(f->!f.isDone()).forEach(f->f.cancel(true));previewSlots.release();}
  }
  private Preview preparePreview(List<Long> serviceIds,Long versionId,LocalDate requestedDate,String operator,Map<String,Future<VersionGitRepository.Snapshot>> reads)throws Exception {
    if(serviceIds==null||serviceIds.isEmpty()||serviceIds.size()>100||serviceIds.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("请选择 1 至 100 个服务");
    LocalDate currentDate=today();
    VersionBatch version;
    LocalDate date;
    if(versionId==null){
      date=requestedDate==null?currentDate:requestedDate;validateDate(date);
      if(date.isBefore(currentDate))throw new IllegalArgumentException("新建版本请选择今天或未来日期；历史版本请从原版本重试");
      version=versions.findByVersionDate(date).orElse(null);
    }else{
      version=find(versionId);date=version.getVersionDate();validateDate(date);
      if(requestedDate!=null&&!requestedDate.equals(date))throw new IllegalArgumentException("所选日期与原版本日期不一致；补充和重试必须沿用原版本日期");
    }
    String branch="release"+date.format(DateTimeFormatter.BASIC_ISO_DATE);
    List<PreparedItem> prepared=new ArrayList<>();Map<Long,AppService> selectedServices=new HashMap<>();
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
    for(Long serviceId:new LinkedHashSet<>(serviceIds)){
      AppService service=services.findById(serviceId).orElseThrow(()->new IllegalArgumentException("选择的服务已不存在，请刷新列表"));
      selectedServices.put(serviceId,service);
      VersionMember member=version==null?null:members.findByVersionIdAndServiceId(version.getId(),serviceId).orElse(null);
      if(version!=null&&date.isBefore(currentDate)&&member==null)throw new IllegalArgumentException("历史版本不能补充服务，请使用今天或未来的版本；历史失败项目仍可重试");
      String repository=Objects.toString(service.getGitRepo(),"").trim(),identity;
      try{if(repository.isBlank())throw new IllegalArgumentException();identity=PolicyGitRepository.identity(repository);}
      catch(IllegalArgumentException invalid){prepared.add(prepared(service,"invalid-service-"+serviceId,"",null,null,"ERROR","REPOSITORY_MISSING","未配置有效的 Git 仓库"));continue;}
      VersionRepository prior=version==null?null:repositories.findByVersionIdAndRepositoryIdentity(version.getId(),identity).orElse(null);
      if(member!=null&&(prior==null||!member.getRepositoryId().equals(prior.getId()))){prepared.add(prepared(service,identity,repository,null,null,"ERROR","SERVICE_CHANGED","服务的仓库已变更，历史版本关联保留；请在新版本中创建"));continue;}
      if(prior!=null&&"SUCCESS".equals(prior.getStatus())){
        prepared.add(prepared(service,identity,repository,prior.getBaselineSha(),prior.getBaselineMessage(),"ALREADY_CREATED",null,"本版本已创建该仓库分支，将复用原关联；已有代码不变"));continue;
      }
      if(prior!=null&&ACTIVE.contains(prior.getStatus())){prepared.add(prepared(service,identity,repository,prior.getBaselineSha(),prior.getBaselineMessage(),"ERROR","IN_PROGRESS","该仓库正在创建分支，请稍后刷新"));continue;}
      String readRepository=repository;
      reads.computeIfAbsent(identity,key->previewWorker.submit(()->git.preview(readRepository,branch)));
      prepared.add(prepared(service,identity,repository,null,null,"READING",null,"正在读取基准分支"));
    }
    Map<String,PreparedItem> outcomes=new HashMap<>();
    for(int index=0;index<prepared.size();index++){
      PreparedItem item=prepared.get(index);if(!"READING".equals(item.status()))continue;
      AppService service=selectedServices.get(item.serviceId());String repository=crypto.decrypt(item.encryptedRepository());
      PreparedItem outcome=outcomes.get(item.identity());
      if(outcome!=null){prepared.set(index,prepared(service,item.identity(),repository,outcome.baselineSha(),outcome.baselineMessage(),outcome.status(),outcome.errorCode(),outcome.message()));continue;}
      try{
        Future<VersionGitRepository.Snapshot> future=reads.get(item.identity());
        VersionGitRepository.Snapshot snapshot;
        try{snapshot=future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(ExecutionException failure){if(failure.getCause() instanceof VersionGitRepository.Failure gitFailure)throw gitFailure;throw new VersionGitRepository.Failure("READ_FAILED","无法读取仓库，请检查地址、凭据和网络后重试");}
        boolean exists=snapshot.existingBranchSha()!=null;
        prepared.set(index,prepared(service,item.identity(),repository,snapshot.sha(),snapshot.summary(),exists?"CONFLICT":"READY",exists?"BRANCH_EXISTS":null,
          exists?"仓库已存在同名分支，平台不会覆盖或自动接管":"将基于此 master 提交创建分支"));
      }catch(TimeoutException timeout){prepared.set(index,prepared(service,item.identity(),repository,null,null,"ERROR","PREVIEW_TIMEOUT","本次预览超时，尚未创建分支；可减少服务数量后重试"));}
      catch(VersionGitRepository.Failure failure){prepared.set(index,prepared(service,item.identity(),repository,null,null,"ERROR",failure.code(),failure.getMessage()));}
      catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalArgumentException("预览已中止，请重新发起");}
      catch(Exception failure){prepared.set(index,prepared(service,item.identity(),repository,null,null,"ERROR","READ_FAILED","无法读取仓库，请检查地址、凭据和网络后重试"));}
      outcomes.put(item.identity(),prepared.get(index));
    }
    // Configuration is checked again at confirmation, including changes made while Git was being read.
    String token=UUID.randomUUID().toString();Instant expires=clock.instant().plusSeconds(600);
    Prepared payload=new Prepared(version==null?null:version.getId(),date,branch,List.copyOf(prepared));
    previews.saveAndFlush(new VersionPreview(token,operator,expires,json.writeValueAsString(payload)));
    List<PreviewItem> items=prepared.stream().map(i->new PreviewItem(i.serviceId(),i.serviceName(),i.serviceKey(),i.serviceType(),branch,i.baselineSha(),shortSha(i.baselineSha()),i.baselineMessage(),i.status(),i.errorCode(),i.message(),i.displayIcon())).toList();
    return new Preview(token,payload.versionId(),date,branch,expires,items,prepared.stream().anyMatch(i->Set.of("READY","ALREADY_CREATED").contains(i.status())));
  }

  public synchronized View create(String token,String operator){
    administrator(operator);if(stopping)throw new IllegalArgumentException("平台正在停止，请稍后重试");
    if(token==null||!token.matches("[0-9a-fA-F-]{36}"))throw new IllegalArgumentException("版本确认信息无效，请重新预览");
    Map<Long,String> watchHeads=registrationHeads(token,operator);
    administrator(operator);requireRunning();
    CommitResult result=transaction.execute(status->confirm(token,operator,watchHeads));
    if(result==null)throw new IllegalStateException("Version transaction failed");
    for(Long id:result.operations()){
      try{worker.submit(()->execute(id));}
      catch(RejectedExecutionException rejected){setResult(id,null,"FAILED","QUEUE_UNAVAILABLE","分支创建队列不可用，请稍后重新预览并重试");}
    }
    return detail(result.versionId(),operator);
  }

  /** New members of an already managed repository start watching its HEAD at registration, not at a later poll. */
  private Map<Long,String> registrationHeads(String token,String operator){
    VersionPreview preview=previews.findByToken(token).orElseThrow(()->new IllegalArgumentException("版本确认信息不存在，请重新预览"));
    if(!preview.getOperator().equals(operator))throw denied();
    if(preview.getConsumedVersionId()!=null)return Map.of();
    if(!preview.getExpiresAt().isAfter(clock.instant()))throw new IllegalArgumentException("版本确认已过期，请重新预览");
    Prepared prepared;
    try{
      if(json.readTree(preview.getPayload()).has("operation"))throw new IllegalArgumentException("请使用关联已有分支入口确认");
      prepared=json.readValue(preview.getPayload(),Prepared.class);
    }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalArgumentException("版本确认信息无法读取，请重新预览");}
    VersionBatch version=prepared.versionId()==null?versions.findByVersionDate(prepared.versionDate()).orElse(null):find(prepared.versionId());
    if(version==null)return Map.of();
    Map<Long,String> result=new HashMap<>();Map<String,String> heads=new HashMap<>();
    for(PreparedItem item:prepared.items()){
      AppService service=services.findById(item.serviceId()).orElseThrow(()->new IllegalArgumentException("选择的服务已被删除，请重新预览"));
      if(!fingerprint(service).equals(item.configurationFingerprint()))throw new IllegalArgumentException("服务或仓库配置已变化，请重新预览");
      if(members.findByVersionIdAndServiceId(version.getId(),item.serviceId()).isPresent())continue;
      VersionRepository repository=repositories.findByVersionIdAndRepositoryIdentity(version.getId(),item.identity()).orElse(null);
      if(repository==null||!"SUCCESS".equals(repository.getStatus()))continue;
      if(!heads.containsKey(item.identity())){
        try{
          VersionGitRepository.Snapshot observed=git.preview(crypto.decrypt(item.encryptedRepository()),version.getBranch());
          if(observed.existingBranchSha()==null)throw new IllegalArgumentException("远程版本分支不存在，无法补充服务，请核对后重试");
          PolicyGitRepository.validateSha(observed.existingBranchSha());heads.put(item.identity(),observed.existingBranchSha());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalArgumentException("分支核对已中止，请重新确认");}
        catch(IllegalArgumentException invalid){throw invalid;}
        catch(Exception failure){throw new IllegalArgumentException("无法核对远程版本分支，请检查仓库凭据和网络后重试");}
      }
      result.put(item.serviceId(),heads.get(item.identity()));
    }
    return Map.copyOf(result);
  }

  private CommitResult confirm(String token,String operator,Map<Long,String> watchHeads){
    VersionPreview preview=previews.findByToken(token).orElseThrow(()->new IllegalArgumentException("版本确认信息不存在，请重新预览"));
    if(!preview.getOperator().equals(operator))throw denied();
    try{if(json.readTree(preview.getPayload()).has("operation"))throw new IllegalArgumentException("请使用关联已有分支入口确认");}
    catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalArgumentException("版本确认信息无法读取，请重新预览");}
    if(preview.getConsumedVersionId()!=null)return new CommitResult(preview.getConsumedVersionId(),List.of());
    if(!preview.getExpiresAt().isAfter(clock.instant()))throw new IllegalArgumentException("版本确认已过期，请重新预览");
    Prepared prepared;
    try{prepared=json.readValue(preview.getPayload(),Prepared.class);}catch(Exception error){throw new IllegalArgumentException("版本确认信息无法读取，请重新预览");}
    if(prepared.items().stream().noneMatch(i->Set.of("READY","ALREADY_CREATED").contains(i.status())))throw new IllegalArgumentException("没有可创建或关联的服务，请先处理预览中的问题");
    LocalDate currentDate=today();validateDate(prepared.versionDate());
    if(!Objects.equals(prepared.branch(),"release"+prepared.versionDate().format(DateTimeFormatter.BASIC_ISO_DATE)))throw new IllegalArgumentException("版本日期与分支不一致，请重新预览");
    // Keep the preview's selected date, including future dates; never shift it at midnight.
    if(prepared.versionId()==null&&prepared.versionDate().isBefore(currentDate))throw new IllegalArgumentException("日期已变化，原预览日期已过去；请重新预览今天或未来的版本");
    Map<Long,AppService> current=new HashMap<>();
    for(PreparedItem item:prepared.items()){
      AppService service=services.findById(item.serviceId()).orElseThrow(()->new IllegalArgumentException("选择的服务已被删除，请重新预览"));
      if(!fingerprint(service).equals(item.configurationFingerprint()))throw new IllegalArgumentException("服务或仓库配置已变化，请重新预览");current.put(service.getId(),service);
    }
    VersionBatch version=prepared.versionId()==null?versions.findByVersionDate(prepared.versionDate()).orElseGet(()->versions.saveAndFlush(new VersionBatch(prepared.versionDate(),prepared.branch(),operator))):find(prepared.versionId());
    if(!version.getVersionDate().equals(prepared.versionDate())||!version.getBranch().equals(prepared.branch()))throw new IllegalArgumentException("版本日期或分支已变化，请重新预览");
    List<Long> operations=new ArrayList<>();Set<String> handled=new HashSet<>();
    for(PreparedItem item:prepared.items()){
      VersionMember oldMember=members.findByVersionIdAndServiceId(version.getId(),item.serviceId()).orElse(null);
      // A missing URL has no repository identity to bind. Leave it unselected so fixing its
      // configuration permits adding it later the same day instead of creating an immortal placeholder.
      if(oldMember==null&&"REPOSITORY_MISSING".equals(item.errorCode()))continue;
      VersionRepository repository=repositories.findByVersionIdAndRepositoryIdentity(version.getId(),item.identity()).orElse(null);
      if(oldMember!=null&&(repository==null||!oldMember.getRepositoryId().equals(repository.getId())))continue;
      // A second confirmation may arrive after another request created the shared repository.
      if(repository==null){repository=new VersionRepository(version.getId(),item.identity(),item.encryptedRepository(),operator);repository=repositories.saveAndFlush(repository);}
      if(oldMember==null){
        if(version.getVersionDate().isBefore(currentDate))throw new IllegalArgumentException("历史版本不能补充服务，请重新选择今天或未来的版本");
        VersionMember added=members.saveAndFlush(new VersionMember(version.getId(),repository.getId(),current.get(item.serviceId())));
        if("SUCCESS".equals(repository.getStatus())){
          String head=watchHeads.get(item.serviceId());if(head==null)throw new IllegalArgumentException("版本状态已变化，请重新预览并核对分支");
          members.initializeCodeWatchBaseline(added.getId(),head,clock.instant());
        }
      }
      if(!handled.add(item.identity())||"SUCCESS".equals(repository.getStatus())||ACTIVE.contains(repository.getStatus())&&repository.getAttemptId()!=null)continue;
      if("ALREADY_CREATED".equals(item.status()))throw new IllegalArgumentException("仓库创建状态已变化，请重新预览");
      repository.prepare(item.encryptedRepository(),item.baselineSha(),item.baselineMessage(),operator);
      if("READY".equals(item.status()))operations.add(repository.getId());
      else repository.result("CONFLICT".equals(item.status())?"CONFLICT":"FAILED",item.errorCode(),item.message());
      repositories.saveAndFlush(repository);
    }
    preview.consume(version.getId());previews.saveAndFlush(preview);
    return new CommitResult(version.getId(),List.copyOf(operations));
  }

  private void execute(Long id){
    Job job=null;
    try{
      synchronized(this){
        if(stopping)return;
        job=transaction.execute(status->{
          VersionRepository repository=repositories.findById(id).orElseThrow();
          if(!"PENDING".equals(repository.getStatus()))return null;
          VersionBatch version=find(repository.getVersionId());
          repository.result("RUNNING",null,"正在核对 master 并创建分支");repositories.saveAndFlush(repository);
          List<Long> selected=members.findByVersionIdOrderByIdAsc(version.getId()).stream().filter(m->m.getRepositoryId().equals(id)).map(VersionMember::getServiceId).toList();
          return new Job(id,repository.getAttemptId(),crypto.decrypt(repository.getEncryptedRepository()),version.getBranch(),repository.getBaselineSha(),repository.getRequestedBy(),selected);
        });
      }
      if(job==null)return;
      administrator(job.operator());
      for(Long serviceId:job.serviceIds()){
        AppService service=services.findById(serviceId).orElseThrow(()->new IllegalArgumentException("服务已删除，请重新核对"));
        if(!PolicyGitRepository.identity(Objects.toString(service.getGitRepo(),"")).equals(PolicyGitRepository.identity(job.repository())))throw new IllegalArgumentException("服务仓库已变化，请重新预览");
      }
      if(stopping||Thread.currentThread().isInterrupted())throw new InterruptedException();
      git.create(job.repository(),job.branch(),job.baseline());
      setResult(id,job.attemptId(),"SUCCESS",null,"分支已创建，可进入打包；后续开发提交保持不变");
    }catch(VersionGitRepository.Failure failure){String code=failure.code();setResult(id,job==null?null:job.attemptId(),"CREATE_UNKNOWN".equals(code)?"UNKNOWN":"BRANCH_EXISTS".equals(code)?"CONFLICT":"FAILED",code,failure.getMessage());}
    catch(InterruptedException interrupted){Thread.currentThread().interrupt();setResult(id,job==null?null:job.attemptId(),"UNKNOWN","CREATE_INTERRUPTED","操作已中止，远程结果需人工核对；平台不会自动覆盖或接管分支");}
    catch(AccessDeniedException denied){setResult(id,job==null?null:job.attemptId(),"FAILED","PERMISSION_CHANGED","管理员权限已失效，已停止创建分支");}
    catch(IllegalArgumentException changed){setResult(id,job==null?null:job.attemptId(),"FAILED","SERVICE_CHANGED","服务或仓库配置已变化，请重新预览");}
    catch(Exception failure){setResult(id,job==null?null:job.attemptId(),"UNKNOWN","CREATE_UNKNOWN","执行结果无法确认，请在仓库核对；平台不会覆盖或自动接管分支");}
  }

  private synchronized void setResult(Long id,String attempt,String status,String code,String message){
    transaction.executeWithoutResult(tx->repositories.findById(id).ifPresent(repository->{
      if(attempt!=null&&!attempt.equals(repository.getAttemptId()))return;
      if("SUCCESS".equals(repository.getStatus()))return;
      repository.result(status,code,message);repositories.saveAndFlush(repository);
      if("SUCCESS".equals(status))initializeCodeWatchBaselines(repository,repository.getBaselineSha());
    }));
  }
  private void initializeCodeWatchBaselines(VersionRepository repository,String head){
    for(VersionMember member:members.findByVersionIdOrderByIdAsc(repository.getVersionId()))if(member.getRepositoryId().equals(repository.getId()))members.initializeCodeWatchBaseline(member.getId(),head,clock.instant());
  }
  @EventListener(ApplicationReadyEvent.class) public synchronized void recover(){
    transaction.executeWithoutResult(tx->{for(VersionRepository repository:repositories.findByStatusIn(ACTIVE)){
      boolean running="RUNNING".equals(repository.getStatus());repository.result(running?"UNKNOWN":"FAILED",running?"CREATE_INTERRUPTED":"QUEUE_INTERRUPTED",running?"平台曾中断创建，远程结果需人工核对；不会自动接管同名分支":"平台重启前尚未执行，请重新预览后重试");repositories.save(repository);
    }});
  }
  @PreDestroy public void shutdown(){stopping=true;previewWorker.shutdownNow();worker.shutdownNow();}

  private View view(VersionBatch version,AdminUser user){
    Map<Long,VersionRepository> repos=new HashMap<>();repositories.findByVersionId(version.getId()).forEach(r->repos.put(r.getId(),r));
    List<Item> items=new ArrayList<>();
    for(VersionMember member:members.findByVersionIdOrderByIdAsc(version.getId())){
      AppService current=services.findById(member.getServiceId()).orElse(null);
      if(!user.isAdmin()){
        if(current==null||!visible(user,member.getServiceType())||!visible(user,current.getServiceType()))continue;
      }
      VersionRepository repository=repos.get(member.getRepositoryId());if(repository==null)continue;
      items.add(new Item(member.getId(),member.getServiceId(),member.getServiceName(),member.getServiceKey(),member.getServiceType(),version.getBranch(),repository.getBaselineSha(),shortSha(repository.getBaselineSha()),repository.getBaselineMessage(),repository.getStatus(),repository.getErrorCode(),repository.getMessage(),member.getCreatedAt(),repository.getUpdatedAt(),current==null?null:current.getDisplayIcon()));
    }
    int success=(int)items.stream().filter(i->i.status().equals("SUCCESS")).count(),running=(int)items.stream().filter(i->ACTIVE.contains(i.status())).count(),failed=items.size()-success-running;
    String state=running>0?"RUNNING":failed==0?"SUCCESS":success>0?"PARTIAL":"FAILED";
    return new View(version.getId(),version.getVersionDate(),version.getBranch(),version.getCreatedBy(),version.getCreatedAt(),state,items.size(),success,failed,running,!version.getVersionDate().isBefore(today()),List.copyOf(items));
  }
  private PreparedItem prepared(AppService s,String identity,String repository,String sha,String summary,String status,String code,String message){
    return new PreparedItem(s.getId(),s.getName(),s.getServiceKey(),s.getServiceType(),identity,crypto.encrypt(repository),fingerprint(s),sha,summary,status,code,message,s.getDisplayIcon());
  }
  private AdminUser reader(String operator){AdminUser user=user(operator);if(!user.isAdmin()&&!MenuPermissions.effective(user.menuPermissionSet()).contains("versions"))throw denied();return user;}
  private AdminUser administrator(String operator){AdminUser user=user(operator);if(!user.isAdmin())throw denied();return user;}
  private AdminUser user(String operator){return users.findByUsername(operator).filter(AdminUser::isEnabled).orElseThrow(VersionManagementService::denied);}
  private boolean visible(AdminUser user,ServiceType type){if(user.isAdmin())return true;Set<String> permissions=MenuPermissions.effective(user.menuPermissionSet());return type==ServiceType.FLUTTER_ANDROID?permissions.contains("apps"):permissions.stream().anyMatch(Set.of("services","builds","release")::contains);}
  private VersionBatch find(Long id){return versions.findById(id).orElseThrow(()->new IllegalArgumentException("版本不存在"));}
  private LocalDate today(){return LocalDate.now(clock.withZone(SHANGHAI));}
  private static void validateDate(LocalDate date){if(date==null||date.getYear()<1000||date.getYear()>9999)throw new IllegalArgumentException("版本日期必须是四位年份的有效日期（yyyy-MM-dd）");}
  private static String shortSha(String sha){return sha==null?null:sha.substring(0,Math.min(8,sha.length()));}
  private static AccessDeniedException denied(){return new AccessDeniedException("没有版本管理操作权限");}
  private static String fingerprint(AppService service){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((service.getId()+"\n"+service.getName()+"\n"+service.getServiceKey()+"\n"+service.getServiceType()+"\n"+Objects.toString(service.getGitRepo(),"")).getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException("Fingerprint unavailable");}}
}
