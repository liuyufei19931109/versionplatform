package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.*;
import com.local.release.security.MenuPermissions;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class RuntimeRestartService {
  public record Container(String id,String name,String state,String imageReference,String imageId) {}
  public record Preview(String token,Long environmentId,Long serviceId,String serviceName,String serviceKey,
    String environmentName,Long serverId,String serverName,String host,List<Container> containers,Instant expiresAt) {}
  private record Target(AppService service,ServiceEnvironment environment,DeploymentServer server,String fingerprint) {}
  private record Prepared(Preview preview,String operator,String fingerprint,Map<String,String> images) {}
  private final AppServiceRepository services;
  private final ServiceEnvironmentRepository environments;
  private final DeploymentServerRepository servers;
  private final AdminUserRepository users;
  private final ReleaseTaskRepository releases;
  private final RuntimeOperationRepository operations;
  private final RuntimeProbe probe;
  private final RuntimeRestartRunner runner;
  private final RuntimeOverviewService overview;
  private final ReleaseExecutor executor;
  private final Map<String,Prepared> previews=new HashMap<>();
  private final Map<String,Long> active=new HashMap<>();
  private final Map<Long,RestartExecution> executions=new HashMap<>();
  private static final String CANCELLED_REMOTE_MESSAGE="已中止后续步骤，远端状态需核对；平台不会自动回滚";
  /** All fields are guarded by this service's monitor. The owner is bound before waiting for the serial slot. */
  private static final class RestartExecution {
    final RuntimeOperation operation; Thread owner; boolean cancelRequested,finalized;
    RestartExecution(RuntimeOperation operation){this.operation=operation;}
  }
  private final ExecutorService worker=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("runtime-restart-",0).factory());
  private volatile boolean stopping;

  public RuntimeRestartService(AppServiceRepository services,ServiceEnvironmentRepository environments,
      DeploymentServerRepository servers,AdminUserRepository users,ReleaseTaskRepository releases,
      RuntimeOperationRepository operations,RuntimeProbe probe,RuntimeRestartRunner runner,
      RuntimeOverviewService overview,ReleaseExecutor executor) {
    this.services=services;this.environments=environments;this.servers=servers;this.users=users;this.releases=releases;
    this.operations=operations;this.probe=probe;this.runner=runner;this.overview=overview;this.executor=executor;
  }

  public Preview preview(Long environmentId,Long serverId,String operator)throws Exception {
    authorized(operator);
    Target target=target(environmentId,serverId);
    synchronized(this){available(target);prune();if(previews.size()>=200)throw new IllegalArgumentException("重启确认请求较多，请稍后重试");}
    noDeployment(target);
    RuntimeProbe.Result result=probe.inspect(target.server(),target.service(),target.environment());
    Map<String,String> expected=images(result);
    runner.configuration("restart",target.service(),target.environment(),target.server(),expected);
    // Detect edits during SSH inspection before presenting a confirmation.
    if(!target.fingerprint().equals(target(environmentId,serverId).fingerprint()))throw new IllegalArgumentException("服务或节点配置已变化，请重新确认");
    List<Container> containers=result.containers().stream().map(c->new Container(c.id(),c.name(),c.state(),c.imageReference(),c.imageId())).toList();
    Preview preview=new Preview(UUID.randomUUID().toString(),environmentId,target.service().getId(),target.service().getName(),target.service().getServiceKey(),
      target.environment().getEnvironmentName(),serverId,target.server().getName(),target.server().getHost(),containers,Instant.now().plusSeconds(120));
    synchronized(this){available(target);prune();if(previews.size()>=200)throw new IllegalArgumentException("重启确认请求较多，请稍后重试");previews.put(preview.token(),new Prepared(preview,operator,target.fingerprint(),expected));}
    return preview;
  }

  public synchronized RuntimeOperation existing(String token,String requestId,String operator) {
    authorized(operator);
    return operations.findByRequestKey(requestKey(requestId,operator)).map(value->{
      if(!value.getRequestFingerprint().equals(hash(token)))throw new IllegalArgumentException("该请求标识已用于其他重启确认，请重新打开确认窗口");
      return value;
    }).orElse(null);
  }

  public synchronized Preview precheck(String token,String operator) {
    authorized(operator);prune();
    Prepared prepared=previews.get(token);
    if(prepared==null||!prepared.operator().equals(operator))throw new IllegalArgumentException("重启确认已过期，请重新打开确认窗口");
    Target target=target(prepared.preview().environmentId(),prepared.preview().serverId());
    if(!prepared.fingerprint().equals(target.fingerprint()))throw new IllegalArgumentException("服务或节点配置已变化，请重新确认");
    available(target);noDeployment(target);
    runner.configuration("restart",target.service(),target.environment(),target.server(),prepared.images());
    return prepared.preview();
  }

  public synchronized RuntimeOperation create(String token,String requestId,String operator) {
    RuntimeOperation prior=existing(token,requestId,operator);if(prior!=null)return prior;
    Preview confirmation=precheck(token,operator);
    Prepared prepared=previews.get(token);
    RuntimeOperation operation=new RuntimeOperation(confirmation.environmentId(),confirmation.serviceId(),confirmation.serverId(),confirmation.serviceName(),
      confirmation.serviceKey(),confirmation.environmentName(),confirmation.serverName(),confirmation.host(),operator,requestKey(requestId,operator),hash(token));
    operation.appendLog("更新配置并重启：已确认 "+confirmation.containers().size()+" 个现有容器，将读取最新 Compose 与环境配置并保留当前镜像，等待串行执行");
    for(Container container:confirmation.containers())operation.appendLog("确认容器 "+container.name()+" · "+container.id()+" · "+container.imageReference());
    operation=operations.save(operation);
    RuntimeOperation saved=operation;
    executions.put(saved.getId(),new RestartExecution(saved));
    active.put(key(confirmation.environmentId(),confirmation.serverId()),saved.getId());
    previews.remove(token);
    try{worker.submit(()->execute(saved,prepared));}
    catch(RuntimeException error){executions.remove(saved.getId());active.remove(key(confirmation.environmentId(),confirmation.serverId()),saved.getId());fail(saved,"重启队列暂时不可用，请稍后重新发起");}
    return operation;
  }

  private void execute(RuntimeOperation operation,Prepared prepared) {
    try {
      synchronized(this){
        RestartExecution execution=executions.get(operation.getId());
        // A task cancelled before its worker starts must never re-enter the global queue.
        if(execution==null||execution.finalized)return;
        checkpoint(operation);execution.owner=Thread.currentThread();
      }
      executor.executeCancellableRuntimeOperation("更新配置并重启 "+operation.getServiceName()+" · "+operation.getServerName(),()->{
        try {
          synchronized(this){
            RestartExecution execution=executions.get(operation.getId());
            if(execution==null||execution.finalized)return;
            checkpoint(operation);
            operation.setStatus(TaskStatus.RUNNING);operation.setStartedAt(LocalDateTime.now());operations.save(operation);
          }
          authorized(operation.getCreatedBy());
          Target target=target(operation.getEnvironmentId(),operation.getServerId());
          if(stopping||!target.fingerprint().equals(prepared.fingerprint()))throw new IllegalArgumentException("服务或节点配置已变化，重启已中止，请重新确认");
          noDeployment(target);
          stage(operation,"CHECK_TARGET","重新检查目标容器和实际镜像");
          if(!images(probe.inspect(target.server(),target.service(),target.environment())).equals(prepared.images()))throw new IllegalArgumentException("实际容器或镜像已变化，重启已中止，请重新确认");
          stage(operation,"RELOAD_CONFIGURATION","读取并校验最新 Compose 与环境文件；校验通过后，使用当前镜像重建所选服务");
          Map<String,String> replacements=runner.restart(target.service(),target.environment(),target.server(),prepared.images());
          stage(operation,"HEALTH_CHECK","配置已加载，目标容器已重建且镜像一致，等待新容器的 Docker 状态与应用健康检查");
          for(var entry:replacements.entrySet())operation.appendLog("新容器 "+entry.getKey()+" · "+entry.getValue());
          operations.save(operation);
          runner.health(target.service(),target.environment(),target.server(),replacements);
          synchronized(this){
            checkpoint(operation);
            operation.setStatus(TaskStatus.SUCCESS);operation.setCurrentStage("COMPLETE");operation.setFinishedAt(LocalDateTime.now());
            operation.appendLog("全部目标容器运行正常，健康检查通过");operations.save(operation);
            executions.get(operation.getId()).finalized=true;
          }
        }catch(Exception error){finishFailure(operation,error);}
      });
    }catch(Exception error){finishFailure(operation,error);}
    finally{
      synchronized(this){
        RestartExecution execution=executions.remove(operation.getId());
        if(execution!=null&&execution.cancelRequested&&execution.owner==Thread.currentThread())Thread.interrupted();
        active.remove(key(operation.getEnvironmentId(),operation.getServerId()),operation.getId());
      }
      overview.invalidate(operation.getEnvironmentId(),operation.getServerId());
    }
  }
  private synchronized void stage(RuntimeOperation operation,String stage,String message) {
    checkpoint(operation);
    operation.setCurrentStage(stage);operation.appendLog(message);operations.save(operation);
  }
  private synchronized void checkpoint(RuntimeOperation operation){
    RestartExecution execution=executions.get(operation.getId());
    if(execution!=null&&execution.cancelRequested)throw new CancellationException(CANCELLED_REMOTE_MESSAGE);
  }
  private synchronized void finishFailure(RuntimeOperation operation,Exception error){
    RestartExecution execution=executions.get(operation.getId());
    if(execution!=null&&execution.finalized)return;
    if(execution!=null&&execution.cancelRequested){
      if(execution.owner==Thread.currentThread())Thread.interrupted();
      cancelled(operation,CANCELLED_REMOTE_MESSAGE);
    }else fail(operation,safeFailure(error));
    if(execution!=null)execution.finalized=true;
  }
  private void cancelled(RuntimeOperation operation,String message){
    operation.setStatus(TaskStatus.CANCELLED);operation.setCurrentStage("CANCELLED");operation.setErrorMessage(message);operation.setFinishedAt(LocalDateTime.now());
    operation.appendLog(message);operations.save(operation);
  }
  public synchronized String cancel(Long id,String operator){
    AdminUser user=authorized(operator);
    RuntimeOperation saved=detail(id);
    if(!user.isAdmin()&&!Objects.equals(operator,saved.getCreatedBy()))throw new AccessDeniedException("只能中止自己发起的重启任务");
    if(saved.getStatus()==TaskStatus.CANCELLED)return "任务已取消；远端状态需核对";
    RestartExecution execution=executions.get(id);
    if(execution==null||execution.finalized||!Set.of(TaskStatus.PENDING,TaskStatus.RUNNING).contains(saved.getStatus()))throw new IllegalArgumentException("只能取消排队中或执行中的重启任务，请刷新状态");
    if(!execution.cancelRequested){
      execution.cancelRequested=true;
      if(execution.operation.getStatus()==TaskStatus.PENDING){
        cancelled(execution.operation,"任务在开始前已取消");execution.finalized=true;
        active.remove(key(execution.operation.getEnvironmentId(),execution.operation.getServerId()),id);
        if(execution.owner==null)executions.remove(id);
      }
      if(execution.owner!=null)execution.owner.interrupt();
    }
    return execution.finalized?"排队任务已取消":"已请求中止，等待当前远程命令退出；远端状态需核对";
  }
  private void fail(RuntimeOperation operation,String message) {
    operation.setStatus(TaskStatus.FAILED);operation.setErrorMessage(message);operation.setFinishedAt(LocalDateTime.now());
    operation.appendLog("操作未完成："+message);operations.save(operation);
  }
  public List<RuntimeOperation> history(Long environmentId,Long serverId) {
    target(environmentId,serverId);
    return operations.findTop50ByEnvironmentIdAndServerIdOrderByIdDesc(environmentId,serverId);
  }
  public List<RuntimeOperation> activeOperations(){return operations.findByStatusIn(List.of(TaskStatus.PENDING,TaskStatus.RUNNING));}
  public RuntimeOperation detail(Long id){return operations.findById(id).orElseThrow(()->new IllegalArgumentException("重启记录不存在"));}

  private AdminUser authorized(String username) {
    AdminUser user=users.findByUsername(username).filter(AdminUser::isEnabled).orElseThrow(()->new AccessDeniedException("账号不可用"));
    Set<String> permissions=MenuPermissions.effective(user.menuPermissionSet());
    if(!user.isAdmin()&&!(permissions.contains("runtime")&&permissions.contains("release")))throw new AccessDeniedException("需要运行总览及新建发版权限");
    return user;
  }
  private Target target(Long environmentId,Long serverId) {
    ServiceEnvironment env=environments.findById(environmentId).orElseThrow(()->new IllegalArgumentException("服务环境不存在，请刷新页面"));
    if(!env.getServerIds().contains(serverId))throw new IllegalArgumentException("所选节点不属于这个服务环境");
    AppService service=services.findById(env.getServiceId()).orElseThrow(()->new IllegalArgumentException("服务不存在"));
    DeploymentServer server=servers.findById(serverId).orElseThrow(()->new IllegalArgumentException("服务器不存在"));
    if(!service.isEnabled()||!env.isEnabled()||!server.isEnabled())throw new IllegalArgumentException("服务、环境或服务器已停用");
    String fingerprint=hash(String.join("\u0000",String.valueOf(service.getId()),String.valueOf(service.getUpdatedAt()),String.valueOf(env.getId()),String.valueOf(env.getUpdatedAt()),
      String.valueOf(server.getId()),String.valueOf(server.getUpdatedAt()),env.getDeploymentMode(),Objects.toString(env.getContainerName(),""),Objects.toString(env.getComposeService(),""),
      String.valueOf(env.getContainerPort()),Objects.toString(env.getHealthCheckUrl(),""),server.getHost(),String.valueOf(server.getPort()),server.getUsername(),
      Objects.toString(server.getComposeDirectory(),""),Objects.toString(server.getComposeFile(),""),String.valueOf(server.isComposeUseSudo()),server.getAuthType(),
      Objects.toString(server.getPrivateKeyPath(),""),Objects.toString(server.getEncryptedPassword(),"")));
    return new Target(service,env,server,fingerprint);
  }
  private void noDeployment(Target target) {
    boolean deploying=releases.findByTaskKindAndStatusIn(TaskKind.DEPLOY,List.of(TaskStatus.PENDING,TaskStatus.RUNNING)).stream().anyMatch(t->
      !t.isSimulate()&&Objects.equals(t.getServiceId(),target.service().getId())&&Objects.equals(t.getEnvironmentName(),target.environment().getEnvironmentName())&&t.targetServerIdList().contains(target.server().getId()));
    if(deploying)throw new IllegalArgumentException("目标节点有排队或执行中的发版任务，请等待完成后再重启");
  }
  private synchronized void available(Target target) {
    if(stopping)throw new IllegalArgumentException("平台正在停止，请稍后重试");
    if(active.containsKey(key(target.environment().getId(),target.server().getId())))throw new IllegalArgumentException("这个服务节点已有重启任务，请查看操作记录");
    if(active.size()>=20)throw new IllegalArgumentException("重启队列已满，请稍后重试");
  }
  private void prune(){previews.values().removeIf(value->!value.preview().expiresAt().isAfter(Instant.now()));}
  private Map<String,String> images(RuntimeProbe.Result result) {
    if(!"OK".equals(result.status())||result.containers().isEmpty())throw new IllegalArgumentException("NOT_FOUND".equals(result.status())?"目标服务没有容器，请先发布服务":result.message());
    Map<String,String> values=new TreeMap<>();
    for(var container:result.containers()) {
      if(!Set.of("running","exited","created").contains(container.state()))throw new IllegalArgumentException("容器正在重启、暂停或删除，请核对状态后重试");
      if(!container.id().matches("[a-f0-9]{64}")||!container.imageId().matches("sha256:[a-f0-9]{64}")||values.put(container.id(),container.imageId())!=null)throw new IllegalArgumentException("目标容器信息不完整，请重新检查");
    }
    if(values.size()>12)throw new IllegalArgumentException("目标服务容器过多，当前不支持整体重启");
    return Map.copyOf(values);
  }
  private static String requestKey(String requestId,String operator) {
    if(requestId==null||requestId.isBlank()||requestId.length()>80)throw new IllegalArgumentException("请求标识不正确，请重新打开确认窗口");
    return hash(operator+"\u0000"+requestId);
  }
  private static String key(Long environmentId,Long serverId){return environmentId+":"+serverId;}
  private static String hash(String value) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Objects.toString(value,"").getBytes(StandardCharsets.UTF_8)));}
    catch(Exception error){throw new IllegalStateException(error);}
  }
  private static String safeFailure(Exception error) {
    if(error instanceof AccessDeniedException)return "账号权限已变化，重启已中止";
    if(error.getClass()==IllegalArgumentException.class&&error.getMessage()!=null&&error.getMessage().length()<=500)return error.getMessage();
    return RuntimeOverviewService.safeFailure(error)+"；请核对目标节点状态后再决定是否重试";
  }
  @EventListener(ApplicationReadyEvent.class) public void recover() {
    for(RuntimeOperation operation:activeOperations())fail(operation,"平台重启中断了操作，请先核对目标节点状态；平台不会自动重复重启");
  }
  @PreDestroy public void close(){stopping=true;worker.shutdown();}
}
