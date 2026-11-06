package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.domain.WecomNotification.Status;
import com.local.release.repo.*;
import com.local.release.security.CredentialSecretCrypto;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class WecomNotificationService {
  private final WecomNotificationSettingsRepository settings;
  private final WecomNotificationRepository notifications;
  private final ReleaseTaskRepository tasks;
  private final AppServiceRepository services;
  private final CredentialSecretCrypto crypto;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final WecomMessageTransport transport;
  Clock clock=Clock.systemDefaultZone();
  public record SettingsRequest(boolean enabled,String webhook,String groupName,String publicBaseUrl,List<String> environments,boolean notifySuccess,boolean notifyFailure){}
  public record SettingsView(boolean enabled,boolean webhookConfigured,String groupName,String publicBaseUrl,List<String> environments,boolean notifySuccess,boolean notifyFailure){}
  public record NotificationView(Long id,Status status,int attempts,LocalDateTime createdAt,LocalDateTime sentAt,String lastError){}
  public record ReleaseFinished(ReleaseTask task){}
  public WecomNotificationService(WecomNotificationSettingsRepository settings,WecomNotificationRepository notifications,
      ReleaseTaskRepository tasks,AppServiceRepository services,CredentialSecretCrypto crypto,ObjectMapper json,
      PlatformTransactionManager manager,WecomMessageTransport transport){
    this.settings=settings;this.notifications=notifications;this.tasks=tasks;this.services=services;
    this.crypto=crypto;this.json=json;this.tx=new TransactionTemplate(manager);this.transport=transport;
  }
  public SettingsView getSettings(){return view(settings.findById(1L).orElseGet(WecomNotificationSettings::new));}
  public synchronized SettingsView saveSettings(SettingsRequest request){return tx.execute(status->{
    WecomNotificationSettings value=settings.findById(1L).orElseGet(WecomNotificationSettings::new);
    if(request.webhook()!=null&&!request.webhook().isBlank())value.setEncryptedWebhook(crypto.encrypt(WecomMessageTransport.validateWebhook(request.webhook())));
    String group=clean(request.groupName(),80);if(group.isBlank())group="发版通知群";
    Set<String> selected=new LinkedHashSet<>(request.environments()==null?List.of():request.environments());
    if(!Set.of("dev","test","prod").containsAll(selected))throw new IllegalArgumentException("通知环境仅支持开发/测试、预生产和生产");
    if(request.enabled()&&(selected.isEmpty()||(!request.notifySuccess()&&!request.notifyFailure())))throw new IllegalArgumentException("请至少选择一个环境和一种发布结果");
    String base=validateBaseUrl(request.publicBaseUrl(),request.enabled());
    if(request.enabled()&&(value.getEncryptedWebhook()==null||value.getEncryptedWebhook().isBlank()))throw new IllegalArgumentException("请先配置企业微信 Webhook");
    value.setEnabled(request.enabled());value.setGroupName(group);value.setPublicBaseUrl(base);
    value.setEnvironments(String.join(",",selected));value.setNotifySuccess(request.notifySuccess());value.setNotifyFailure(request.notifyFailure());
    return view(settings.save(value));
  });}
  public synchronized boolean initializeIfAbsent(SettingsRequest request,boolean testNotification){
    if(settings.existsById(1L))return false;
    saveSettings(request);if(testNotification)enqueueTest();return true;
  }
  public NotificationView enqueueTest(){return tx.execute(status->{
    WecomNotificationSettings setting=configured();
    WecomNotification row=create(null,UUID.randomUUID().toString(),setting,testCard(setting));
    return view(notifications.saveAndFlush(row));
  });}
  public List<NotificationView> list(Long taskId){deployment(taskId);return notifications.findByTaskIdOrderByIdDesc(taskId).stream().map(this::view).toList();}
  public NotificationView retry(Long taskId,Long id){deployment(taskId);return tx.execute(status->{
    WecomNotification value=notifications.lockById(id).orElseThrow(()->new IllegalArgumentException("通知记录不存在"));
    if(!Objects.equals(taskId,value.getTaskId()))throw new IllegalArgumentException("通知不属于当前发版任务");
    if(value.getStatus()!=Status.FAILED)throw new IllegalArgumentException("只有发送失败的通知可以重发，请勿重复点击");
    configured();
    // Preserve the original result and destination. Re-sending never executes a deployment.
    value.setStatus(Status.PENDING);value.setCycleAttempts(0);value.setLastError(null);value.setNextAttemptAt(now());
    return view(notifications.saveAndFlush(value));
  });}
  @EventListener public void completed(ReleaseFinished event){
    ReleaseTask task=event.task();
    if(task.getTaskKind()!=ModelEnums.TaskKind.DEPLOY||task.isSimulate()
        ||!Set.of(ModelEnums.TaskStatus.SUCCESS,ModelEnums.TaskStatus.FAILED).contains(task.getStatus()))return;
    try{tx.executeWithoutResult(status->{
      WecomNotificationSettings setting=settings.findById(1L).orElse(null);
      if(setting==null||!setting.isEnabled()||!Arrays.asList(setting.getEnvironments().split(",")).contains(task.getEnvironmentName()))return;
      if(task.getStatus()==ModelEnums.TaskStatus.SUCCESS?!setting.isNotifySuccess():!setting.isNotifyFailure())return;
      String execution=task.getId()+"|"+String.valueOf(task.getStartedAt())+"|"+String.valueOf(task.getLastRetryRequestKey());
      WecomNotification row=create(task.getId(),execution,setting,resultCard(task,setting));
      if(!notifications.existsByDedupKey(row.getDedupKey()))notifications.saveAndFlush(row);
    });}catch(Exception error){LoggerFactory.getLogger(getClass()).warn("Release #{} notification could not be queued; exceptionType={}",task.getId(),error.getClass().getSimpleName());}
  }
  /** Only this feature's existing queue is recovered; historical releases are never scanned. */
  public void recoverPending(){tx.executeWithoutResult(status->{
    for(WecomNotification row:notifications.findByStatus(Status.SENDING)){
      row.setStatus(row.getCycleAttempts()>=3?Status.FAILED:Status.PENDING);
      row.setNextAttemptAt(now().plusSeconds(10));row.setLastError("发送时平台重启；上次消息是否送达无法确认");notifications.save(row);
    }
  });}
  /** Invoked only by the dedicated one-thread dispatcher, never the build executor. */
  public synchronized void dispatchOne(){
    WecomNotification row=tx.execute(status->{
      WecomNotificationSettings setting=settings.findById(1L).orElse(null);
      if(setting==null||!setting.isEnabled())return null;
      LocalDateTime current=now();
      // A successful HTTP exchange followed by a transient DB error must not leave SENDING forever.
      // Delivery is at-least-once: WeCom has no idempotency token, so an interrupted exchange can duplicate.
      for(WecomNotification stale:notifications.findByStatus(Status.SENDING)){
        if(stale.getLastAttemptAt()!=null&&stale.getLastAttemptAt().plusSeconds(60).isBefore(current)){
          stale.setStatus(stale.getCycleAttempts()>=3?Status.FAILED:Status.PENDING);
          stale.setNextAttemptAt(current.plusSeconds(10));stale.setLastError("发送状态确认中断；上次消息是否送达无法确认");notifications.save(stale);
        }
      }
      for(WecomNotification candidate:notifications.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderById(Status.PENDING,current)){
        WecomNotification value=notifications.lockById(candidate.getId()).orElse(null);
        if(value==null||value.getStatus()!=Status.PENDING)continue;
        // At least 3.2 seconds between attempts for a robot, including manual retries and tests.
        LocalDateTime previous=notifications.findFirstByTargetHashAndLastAttemptAtNotNullOrderByLastAttemptAtDesc(value.getTargetHash()).map(WecomNotification::getLastAttemptAt).orElse(null);
        if(previous!=null&&previous.plusNanos(3_200_000_000L).isAfter(current))continue;
        value.setStatus(Status.SENDING);value.setAttempts(value.getAttempts()+1);value.setCycleAttempts(value.getCycleAttempts()+1);value.setLastAttemptAt(current);
        return notifications.saveAndFlush(value);
      }
      return null;
    });
    if(row==null)return;
    WecomMessageTransport.Delivery result;
    try{result=transport.send(crypto.decrypt(row.getEncryptedWebhook()),row.getPayload());}
    catch(Exception error){result=new WecomMessageTransport.Delivery(false,false,"无法读取通知凭据，请联系管理员");}
    WecomMessageTransport.Delivery delivery=result;
    WecomNotification completed=tx.execute(status->{
      WecomNotification value=notifications.lockById(row.getId()).orElseThrow();
      if(value.getStatus()!=Status.SENDING)return null;
      if(delivery.success()){value.setStatus(Status.SENT);value.setSentAt(now());value.setLastError(null);}
      else{value.setLastError(delivery.error());
        if(delivery.retryable()&&value.getCycleAttempts()<3){value.setStatus(Status.PENDING);value.setNextAttemptAt(now().plusSeconds(value.getCycleAttempts()==1?10:30));}
        else value.setStatus(Status.FAILED);
      }
      return notifications.saveAndFlush(value);
    });
    if(completed!=null)LoggerFactory.getLogger(getClass()).info("WECOM_NOTIFICATION id={} status={} attempts={}",completed.getId(),completed.getStatus(),completed.getAttempts());
  }
  private WecomNotification create(Long taskId,String execution,WecomNotificationSettings setting,Object payload){
    String webhook=WecomMessageTransport.validateWebhook(crypto.decrypt(setting.getEncryptedWebhook()));
    WecomNotification row=new WecomNotification();row.setTaskId(taskId);row.setTargetHash(hash(webhook));
    row.setDedupKey(hash(execution+"|"+row.getTargetHash()));row.setEncryptedWebhook(setting.getEncryptedWebhook());row.setNextAttemptAt(now());
    try{row.setPayload(json.writeValueAsString(payload));}catch(Exception error){throw new IllegalStateException("无法生成通知内容");}
    return row;
  }
  private Object resultCard(ReleaseTask task,WecomNotificationSettings setting){
    AppService service=services.findById(task.getServiceId()).orElse(null);
    boolean success=task.getStatus()==ModelEnums.TaskStatus.SUCCESS;
    String env=environmentLabel(task.getEnvironmentName());
    String name=service==null||service.getName()==null||service.getName().isBlank()?"服务 #"+task.getServiceId():service.getName();
    String title=clean(name,26);
    String key=service==null?null:service.getServiceKey();
    String desc=key==null||key.isBlank()?env:clean(key,30);
    List<Map<String,String>> lines=new ArrayList<>();
    lines.add(line("分支",task.getBranch()));lines.add(line("Commit",shortSha(task.getCommitId())));
    lines.add(line("操作人",task.getCreatedBy()));lines.add(line("完成时间",format(task.getFinishedAt())));
    lines.add(line("耗时",elapsed(task)));lines.add(line("任务", "#"+task.getId()));
    String subtitle=success?"部署与健康检查已完成。":failureSummary(task);
    return card(title,desc,(success?"✅ 发布成功":"❌ 发布失败")+"\n"+subtitle,lines,setting.getPublicBaseUrl()+"/releases/"+task.getId(),task.getEnvironmentName());
  }
  private Object testCard(WecomNotificationSettings setting){return card("🔔 发版通知测试","发版平台","这是一条配置测试消息，未执行任何业务发布。",
    List.of(line("发送时间",format(now())),line("通知环境",String.join("、",view(setting).environments().stream().map(WecomNotificationService::environmentLabel).toList()))),setting.getPublicBaseUrl()+"/releases",null);}
  static String environmentLabel(String env){return "prod".equals(env)?"生产":"test".equals(env)?"预生产":"dev".equals(env)?"开发/测试":"通知测试";}
  private Map<String,Object> card(String title,String desc,String subtitle,List<Map<String,String>> lines,String url,String environment){return Map.of("msgtype","template_card","template_card",Map.of(
    "card_type","text_notice","source",Map.of("desc",("prod".equals(environment)?"🔴 ":"test".equals(environment)?"🟢 ":"⚪ ")+environmentLabel(environment)+" · 发版平台","desc_color","prod".equals(environment)?2:"test".equals(environment)?3:0),
    "main_title",Map.of("title",title,"desc",desc),"sub_title_text",subtitle,"horizontal_content_list",lines,
    "jump_list",List.of(Map.of("type",1,"title","查看发版详情","url",url)),"card_action",Map.of("type",1,"url",url)));}
  // Classification deliberately sends no command output, URLs, raw exception or release note to the group.
  static String failureSummary(ReleaseTask task){
    String step=task.getCurrentStep()==null?"发布":switch(task.getCurrentStep()){case SSH_CONNECT->"远程连接";case START_SERVICE->"启动服务";case HEALTH_CHECK->"健康检查";default->"发布";};
    String error=String.valueOf(task.getErrorMessage()).toLowerCase(Locale.ROOT);
    String reason=error.contains("重启")||error.contains("中断")?"平台重启中断，请核对目标状态":error.contains("timeout")||error.contains("超时")?"操作超时":error.contains("auth")||error.contains("认证")?"远程认证失败":error.contains("permission")||error.contains("权限")?"远程权限不足":error.contains("health")||error.contains("健康")?"服务健康检查未通过":"执行未完成";
    return step+"失败："+reason+"。请在平台查看详细日志。";
  }
  private WecomNotificationSettings configured(){WecomNotificationSettings s=settings.findById(1L).orElseThrow(()->new IllegalArgumentException("请先保存企业微信通知配置"));if(!s.isEnabled())throw new IllegalArgumentException("请先启用企业微信通知");return s;}
  private ReleaseTask deployment(Long id){ReleaseTask task=tasks.findById(id).orElseThrow(()->new IllegalArgumentException("发版任务不存在"));if(task.getTaskKind()!=ModelEnums.TaskKind.DEPLOY)throw new IllegalArgumentException("不是发版任务");return task;}
  private SettingsView view(WecomNotificationSettings s){return new SettingsView(s.isEnabled(),s.getEncryptedWebhook()!=null&&!s.getEncryptedWebhook().isBlank(),s.getGroupName(),s.getPublicBaseUrl(),s.getEnvironments().isBlank()?List.of():Arrays.asList(s.getEnvironments().split(",")),s.isNotifySuccess(),s.isNotifyFailure());}
  private NotificationView view(WecomNotification n){return new NotificationView(n.getId(),n.getStatus(),n.getAttempts(),n.getCreatedAt(),n.getSentAt(),n.getLastError());}
  private LocalDateTime now(){return LocalDateTime.now(clock);}
  static String validateBaseUrl(String value,boolean required){String text=value==null?"":value.trim().replaceAll("/+$","");if(text.isBlank()&&!required)return "";try{URI uri=URI.create(text);if(text.length()>500||!("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!(uri.getPath()==null||uri.getPath().isEmpty()))throw new IllegalArgumentException();return text;}catch(Exception e){throw new IllegalArgumentException("请填写平台访问地址，例如 https://release.example.com，不含路径或账号密码");}}
  private static Map<String,String> line(String key,String value){return Map.of("keyname",key,"value",clean(value,26));}
  static String clean(String value,int limit){String result=value==null||value.isBlank()?"—":value.replaceAll("[\\p{Cntrl}]"," ").trim();return result.codePointCount(0,result.length())>limit?result.substring(0,result.offsetByCodePoints(0,limit-1))+"…":result;}
  private static String shortSha(String sha){return sha==null?"—":sha.substring(0,Math.min(8,sha.length()));}
  private static String format(LocalDateTime value){return value==null?"—":value.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"));}
  private static String elapsed(ReleaseTask t){if(t.getStartedAt()==null||t.getFinishedAt()==null)return "—";long sec=Math.max(0,Duration.between(t.getStartedAt(),t.getFinishedAt()).toSeconds());return sec/60+" 分 "+sec%60+" 秒";}
  static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("无法生成通知标识");}}
}
