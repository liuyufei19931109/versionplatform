package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.security.TotpService;
import com.local.release.web.ReleaseTaskView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;

@Service
public class MobileReleaseService {
  private final ReleaseTaskRepository tasks;
  private final AppServiceRepository services;
  private final ArtifactRepository artifacts;
  private final MobileDeliveryRepository deliveries;
  private final FlutterBuildService flutter;
  private final TotpService totp;
  private final ProductionMergeService merge;
  private final ApplicationEventPublisher publisher;
  private final TransactionTemplate transactions;
  private final SecureRandom random=new SecureRandom();
  Clock clock=Clock.systemDefaultZone();

  public MobileReleaseService(ReleaseTaskRepository tasks,AppServiceRepository services,ArtifactRepository artifacts,
      MobileDeliveryRepository deliveries,FlutterBuildService flutter,TotpService totp,ProductionMergeService merge,
      ApplicationEventPublisher publisher,PlatformTransactionManager tx){
    this.tasks=tasks;this.services=services;this.artifacts=artifacts;this.deliveries=deliveries;this.flutter=flutter;
    this.totp=totp;this.merge=merge;this.publisher=publisher;this.transactions=new TransactionTemplate(tx);
  }
  public record Create(Long serviceId,Long artifactId,String environmentName,String releaseNote,String requestId,String totpCode){}
  public record Confirm(Long artifactId,String sha256,boolean success,String requestId,String message){}
  public record Detail(ReleaseTaskView task,MobileArtifact artifact,LocalDateTime downloadedAt,LocalDateTime confirmedAt,
      String confirmationStatus,String confirmationMessage,LocalDateTime accessExpiresAt){}
  public record Access(String fetchToken,String confirmToken,LocalDateTime expiresAt,String downloadUrl,String confirmationUrl){}
  public record TokenPrincipal(long taskId,String scope,String name){}

  public synchronized ReleaseTaskView create(Create request,String operator){
    String env=environment(request.environmentName());
    String key="MOBILE:"+operator+":"+validRequest(request.requestId());
    String fingerprint=hash(String.join("\n",String.valueOf(request.serviceId()),String.valueOf(request.artifactId()),env,Objects.toString(request.releaseNote(),"")));
    ReleaseTask existing=tasks.findByRequestKey(key).orElse(null);
    if(existing!=null){if(!fingerprint.equals(existing.getRequestFingerprint()))throw new IllegalArgumentException("同一请求标识不能用于不同环境或包版本");return view(existing);}
    AppService app=services.findById(request.serviceId()).orElseThrow(()->new IllegalArgumentException("服务不存在"));
    if(!app.isEnabled()||app.getServiceType()!=ServiceType.FLUTTER_ANDROID)throw new IllegalArgumentException("请选择已启用的 Flutter Android 服务");
    Artifact artifact=artifacts.findById(request.artifactId()).orElseThrow(()->new IllegalArgumentException("安装包不存在"));
    ReleaseTask build=tasks.findById(artifact.getBuildTaskId()).orElseThrow(()->new IllegalArgumentException("打包记录不存在"));
    MobileArtifact metadata=flutter.metadata(artifact.getId());
    if(!Objects.equals(artifact.getServiceId(),app.getId())||!Objects.equals(metadata.getArtifactId(),artifact.getId())||!Objects.equals(metadata.getServiceId(),app.getId())||!Objects.equals(build.getServiceId(),app.getId())
        ||build.getTaskKind()!=TaskKind.BUILD||build.getStatus()!=TaskStatus.SUCCESS||build.isSimulate()
        ||!"MOBILE".equals(build.getDeliveryType())||!Objects.equals(build.getCommitId(),artifact.getCommitId())
        ||!Objects.equals(metadata.getCommitId(),artifact.getCommitId())||!Objects.equals(metadata.getBuildTaskId(),build.getId()))
      throw new IllegalArgumentException("只能分发当前服务真实构建并校验成功的安装包");
    validateBuildEnvironment(env,build,metadata);
    verifiedFile(artifact.getId());
    if("prod".equals(env))totp.verifyAndConsume(operator,request.totpCode());
    ReleaseTask created=transactions.execute(status->{
      ReleaseTask task=new ReleaseTask(app.getId(),TaskKind.DEPLOY,artifact.getId(),env,artifact.getBranch(),request.releaseNote(),false);
      task.setDeliveryType("MOBILE");task.setCommitId(artifact.getCommitId());task.setCreatedBy(operator);
      task.setStatus(TaskStatus.WAITING_CONFIRMATION);task.setStartedAt(now());task.setRequestKey(key);task.setRequestFingerprint(fingerprint);
      task=tasks.saveAndFlush(task);deliveries.saveAndFlush(new MobileDelivery(task.getId(),artifact.getId(),metadata.getSha256()));return task;
    });
    return view(Objects.requireNonNull(created));
  }

  private void validateBuildEnvironment(String environment,ReleaseTask build,MobileArtifact metadata){
    if(metadata.getBuildEnvironmentName()==null||metadata.getBuildEnvironmentName().isBlank())
      throw new IllegalArgumentException("旧安装包没有绑定构建环境，请重新按环境打包后发布");
    if(!environment.equals(metadata.getBuildEnvironmentName()))
      throw new IllegalArgumentException("安装包构建环境与发布环境不一致，请选择对应环境重新打包的安装包");
    FlutterBuildConfiguration config=FlutterBuildConfiguration.fromTask(build);
    if(!environment.equals(config.environmentName())||!environment.equals(build.getEnvironmentName())
        ||!Objects.equals(config.buildMode(),metadata.getBuildMode())
        ||!Objects.equals(config.applicationId(),metadata.getApplicationId())
        ||!Objects.equals(config.expectedCertificateSha256(),metadata.getCertificateSha256())
        ||!Objects.equals(config.outputFormat(),metadata.getFormat()))
      throw new IllegalArgumentException("安装包环境、签名或构建模式与锁定的构建配置不一致，请重新按环境打包");
    if("prod".equals(environment)&&(!"RELEASE".equals(metadata.getBuildMode())||config.allowDebugSigning()))
      throw new IllegalArgumentException("生产环境只允许使用正式签名的 Release 安装包");
  }

  public Detail detail(Long taskId){
    ReleaseTask task=task(taskId);MobileDelivery delivery=delivery(taskId);
    return new Detail(view(task),flutter.metadata(delivery.getArtifactId()),delivery.getDownloadedAt(),delivery.getConfirmedAt(),
      delivery.getConfirmationStatus(),delivery.getConfirmationMessage(),delivery.getAccessExpiresAt());
  }
  public MobileArtifact metadata(Long artifactId){return flutter.metadata(artifactId);}
  public Path downloadFile(Long taskId){task(taskId);return verifiedFile(delivery(taskId).getArtifactId());}

  /** Called only after the response stream was copied and flushed successfully. */
  public synchronized void downloaded(Long taskId,String operator){
    transactions.executeWithoutResult(tx->{
      task(taskId);MobileDelivery d=locked(taskId);if(d.getDownloadedAt()==null){d.setDownloadedAt(now());d.setDownloadedBy(operator);deliveries.save(d);}
    });
  }

  public synchronized Detail confirm(Long taskId,Confirm request,String operator){
    validRequest(request.requestId());
    if(request.sha256()==null||!request.sha256().matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("请提供安装包的 SHA-256");
    String fingerprint=hash(request.artifactId()+"\n"+request.sha256().toLowerCase(Locale.ROOT)+"\n"+request.success()+"\n"+Objects.toString(request.message(),""));
    boolean[] changed={false};
    ReleaseTask result=transactions.execute(tx->{
      MobileDelivery d=locked(taskId);ReleaseTask task=task(taskId);
      if(!Objects.equals(request.artifactId(),d.getArtifactId())||!d.getSha256().equalsIgnoreCase(request.sha256()))
        throw new IllegalArgumentException("确认的安装包与该环境发布记录不一致");
      if(d.getConfirmedAt()!=null){
        if(!Objects.equals(d.getConfirmationRequestId(),request.requestId())||!fingerprint.equals(d.getConfirmationFingerprint()))
          throw new IllegalArgumentException("该发布记录已有最终结果，不能更改；再次发布请创建新记录");
        return task;
      }
      if(task.getStatus()!=TaskStatus.WAITING_CONFIRMATION)throw new IllegalArgumentException("该发布记录不能再确认");
      if(d.getDownloadedAt()==null)throw new IllegalArgumentException("请先通过本发布记录拉取安装包，再确认实际发布结果");
      d.setConfirmedAt(now());d.setConfirmedBy(operator);d.setConfirmationStatus(request.success()?"SUCCESS":"FAILED");
      d.setConfirmationMessage(request.message());d.setConfirmationRequestId(request.requestId());d.setConfirmationFingerprint(fingerprint);deliveries.save(d);
      task.setStatus(request.success()?TaskStatus.SUCCESS:TaskStatus.FAILED);task.setFinishedAt(now());task.setCurrentStep(null);
      task.setErrorMessage(request.success()?null:"外部分发方确认发布失败，请查看分发结果");
      task.setMergeEnqueuePending(request.success()&&"prod".equals(task.getEnvironmentName()));tasks.save(task);changed[0]=true;return task;
    });
    if(changed[0]){
      // These side effects follow the committed result. A notification/merge error never causes redeployment.
      try{publisher.publishEvent(new WecomNotificationService.ReleaseFinished(result));}catch(Exception ignored){}
      if(result.getMergeEnqueuePending())try{merge.ensureForSuccessfulDeployment(result);}catch(Exception ignored){}
    }
    return detail(taskId);
  }

  public synchronized Access issueAccess(Long taskId){
    String fetch=token(taskId,"fetch"),confirm=token(taskId,"confirm");LocalDateTime expiry=now().plusHours(24);
    transactions.executeWithoutResult(tx->{
      if(task(taskId).getStatus()!=TaskStatus.WAITING_CONFIRMATION)throw new IllegalArgumentException("仅等待发布确认的记录可以签发分发凭证");
      MobileDelivery d=locked(taskId);d.setFetchTokenHash(hash(fetch));d.setConfirmTokenHash(hash(confirm));d.setAccessExpiresAt(expiry);deliveries.save(d);
    });
    String base="/api/mobile/releases/"+taskId;
    return new Access(fetch,confirm,expiry,base+"/download",base+"/confirm");
  }
  public synchronized void revokeAccess(Long taskId){transactions.executeWithoutResult(tx->{task(taskId);MobileDelivery d=locked(taskId);d.setFetchTokenHash(null);d.setConfirmTokenHash(null);d.setAccessExpiresAt(null);deliveries.save(d);});}
  public TokenPrincipal validateAccess(String value,String method,String path){
    if(value==null||!value.matches("mrel_[1-9][0-9]{0,18}_(fetch|confirm)_[A-Za-z0-9_-]{43}"))return null;
    String[] parts=value.split("_",4);long id;try{id=Long.parseLong(parts[1]);}catch(NumberFormatException ex){return null;}
    String scope=parts[2],base="/api/mobile/releases/"+id;
    boolean allowed="GET".equals(method)&&path.equals(base)
      ||"fetch".equals(scope)&&"GET".equals(method)&&path.equals(base+"/download")
      ||"confirm".equals(scope)&&"POST".equals(method)&&path.equals(base+"/confirm");
    if(!allowed)return null;
    MobileDelivery d=deliveries.findByReleaseTaskId(id).orElse(null);
    if(d==null||d.getAccessExpiresAt()==null||!now().isBefore(d.getAccessExpiresAt()))return null;
    String expected="fetch".equals(scope)?d.getFetchTokenHash():d.getConfirmTokenHash();
    if(expected==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),hash(value).getBytes(StandardCharsets.US_ASCII)))return null;
    return new TokenPrincipal(id,scope,"mobile:"+id+":"+scope);
  }
  private Path verifiedFile(Long artifactId){try{return flutter.verifiedFile(artifactId);}catch(Exception e){throw new IllegalArgumentException("安装包文件缺失或校验失败，请重新构建");}}
  private ReleaseTask task(Long id){ReleaseTask t=tasks.findById(id).orElseThrow(()->new IllegalArgumentException("App 发布记录不存在"));if(t.getTaskKind()!=TaskKind.DEPLOY||!"MOBILE".equals(t.getDeliveryType()))throw new IllegalArgumentException("不是 App 发布记录");return t;}
  private MobileDelivery delivery(Long id){return deliveries.findByReleaseTaskId(id).orElseThrow(()->new IllegalArgumentException("App 分发记录不存在"));}
  private MobileDelivery locked(Long id){return deliveries.locked(id).orElseThrow(()->new IllegalArgumentException("App 分发记录不存在"));}
  private ReleaseTaskView view(ReleaseTask task){return ReleaseTaskView.one(task,services);}
  private LocalDateTime now(){return LocalDateTime.now(clock);}
  private String token(Long id,String scope){byte[] bytes=new byte[32];random.nextBytes(bytes);return "mrel_"+id+"_"+scope+"_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
  private static String validRequest(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{16,80}"))throw new IllegalArgumentException("请求标识无效，请重新提交");return id;}
  private static String environment(String value){String env=value==null?"":value.trim().toLowerCase(Locale.ROOT);if(!Set.of("dev","test","prod").contains(env))throw new IllegalArgumentException("请选择开发、预生产或生产环境");return env;}
  private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
