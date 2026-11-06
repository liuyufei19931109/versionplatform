package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** A manually refreshed snapshot; reading this service never starts a deployment. */
@Service
public class RuntimeOverviewService {
  public record Expected(Long taskId, Long artifactId, String imageReference, String imageDigest,
                         String commitId, String version, LocalDateTime deployedAt) {}
  public record Row(String key, Long environmentId, Long serviceId, String serviceName, String serviceKey,
                    String serviceType, String environmentName, Long serverId, String serverName, String host,
                    boolean enabled, String checkState, Instant checkedAt, boolean stale, String message,
                    Expected expected, String comparison, String comparisonBasis,
                    List<RuntimeProbe.ContainerInfo> containers, List<String> accessUrls,String displayIcon) {}
  public record Snapshot(List<Row> rows, boolean checking, Instant serverTime) {}
  private record Binding(AppService service, ServiceEnvironment environment, DeploymentServer server,
                         Long serverId, String key, String fingerprint) {
    boolean enabled() { return service.isEnabled() && environment.isEnabled() && server != null && server.isEnabled(); }
  }
  private record Cached(String fingerprint, Instant checkedAt, RuntimeProbe.Result result) {}
  private static final Duration FRESHNESS = Duration.ofMinutes(5), COOLDOWN = Duration.ofSeconds(15);
  private static final int MAX_PENDING = 100;
  private final AppServiceRepository services;
  private final ServiceEnvironmentRepository environments;
  private final DeploymentServerRepository servers;
  private final ReleaseTaskRepository tasks;
  private final ArtifactRepository artifacts;
  private final RuntimeProbe probe;
  private final Clock clock;
  private final ExecutorService worker;
  private final Map<String, Cached> cache = new HashMap<>();
  private final Set<String> pending = new HashSet<>();

  @org.springframework.beans.factory.annotation.Autowired
  public RuntimeOverviewService(AppServiceRepository services, ServiceEnvironmentRepository environments,
      DeploymentServerRepository servers, ReleaseTaskRepository tasks, ArtifactRepository artifacts, RuntimeProbe probe) {
    this(services, environments, servers, tasks, artifacts, probe, Clock.systemUTC(),
      Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "runtime-inspection"); t.setDaemon(true); return t; }));
  }
  RuntimeOverviewService(AppServiceRepository services, ServiceEnvironmentRepository environments,
      DeploymentServerRepository servers, ReleaseTaskRepository tasks, ArtifactRepository artifacts,
      RuntimeProbe probe, Clock clock, ExecutorService worker) {
    this.services=services; this.environments=environments; this.servers=servers; this.tasks=tasks;
    this.artifacts=artifacts; this.probe=probe; this.clock=clock; this.worker=worker;
  }

  public Snapshot snapshot() {
    List<Binding> bindings = bindings();
    // History is paged in descending completion order; retries of an old task still count as recent.
    Map<String, ReleaseTask> successes = successfulHistory(bindings);
    Map<Long, Artifact> versions = artifacts.findAllById(successes.values().stream()
      .map(ReleaseTask::getArtifactId).filter(Objects::nonNull).collect(Collectors.toSet()))
      .stream().collect(Collectors.toMap(Artifact::getId, a -> a));
    List<ReleaseTask> latestAttempts = tasks.findRuntimeActivity(TaskKind.DEPLOY, TaskStatus.RUNNING, LocalDateTime.ofInstant(clock.instant().minus(FRESHNESS), ZoneId.systemDefault()));
    Instant now = clock.instant();
    List<Row> rows = new ArrayList<>();
    synchronized (this) {
      Set<String> live = bindings.stream().map(Binding::key).collect(Collectors.toSet());
      cache.keySet().removeIf(key -> !live.contains(key));
      for (Binding b : bindings) {
        Cached c = cache.get(b.key());
        if (c != null && !Objects.equals(c.fingerprint(), b.fingerprint())) c = null;
        ReleaseTask success = successes.get(b.key());
        Artifact artifact = success == null ? null : versions.get(success.getArtifactId());
        Expected expected = success == null ? null : new Expected(success.getId(), success.getArtifactId(),
          artifact == null ? null : artifact.getImageUrl(), artifact == null ? null : artifact.getImageDigest(),
          artifact == null ? success.getCommitId() : artifact.getCommitId(), artifact == null ? null : artifact.getVersion(),
          success.getFinishedAt());
        Cached observed = c;
        boolean changed = latestAttempts.stream().anyMatch(t -> targets(t,b) && (t.getStatus()==TaskStatus.RUNNING
          || (observed != null && later(t.getStartedAt(), observed.checkedAt()))
          || (observed != null && later(t.getFinishedAt(), observed.checkedAt()))));
        boolean active = latestAttempts.stream().anyMatch(t -> t.getStatus()==TaskStatus.RUNNING && targets(t,b));
        boolean stale = c != null && (Duration.between(c.checkedAt(), now).compareTo(FRESHNESS) > 0 || changed);
        String state, message;
        if (b.server()==null) { state="NOT_CONFIGURED"; message=b.serverId()==null?"尚未绑定服务器":"绑定的服务器已不存在，请检查环境配置"; }
        else if (!b.enabled()) { state="DISABLED"; message="服务、环境或节点已停用"; }
        else if (pending.contains(b.key())) { state="CHECKING"; message="正在排队或读取容器状态"; }
        else if (c==null) { state="UNCHECKED"; message="尚未检查，点击检查读取实际运行状态"; }
        else { state="ERROR".equals(c.result().status())?"ERROR":"READY"; message=c.result().message(); }
        if (active) message="该节点正在发布，请在发布结束后重新检查";
        else if (stale && changed) message="检查后发生过发布，请重新检查实际版本";
        List<RuntimeProbe.ContainerInfo> containers = c==null ? List.of() : c.result().containers();
        String basis = expected==null?null:nonblank(expected.imageDigest())?"DIGEST":nonblank(expected.imageReference())?"TAG":null;
        String comparison = c!=null && !stale && !active && "READY".equals(state) && "OK".equals(c.result().status())
          ? compare(expected, containers) : "UNKNOWN";
        rows.add(new Row(b.key(), b.environment().getId(), b.service().getId(), b.service().getName(),
          b.service().getServiceKey(), b.service().getServiceType().name(), b.environment().getEnvironmentName(),
          b.serverId(), b.server()==null?"未绑定节点":b.server().getName(), b.server()==null?"":b.server().getHost(),
          b.enabled(), state, c==null?null:c.checkedAt(), stale, message, expected, comparison, basis,
          containers, accessUrls(b, containers),b.service().getDisplayIcon()));
      }
      return new Snapshot(List.copyOf(rows), !pending.isEmpty(), now);
    }
  }

  public Snapshot requestCheck(Long environmentId, Long serverId) {
    if ((environmentId==null)!=(serverId==null)) throw new IllegalArgumentException("请选择完整的环境和节点");
    List<Binding> selected = bindings().stream().filter(b -> environmentId==null ||
      Objects.equals(environmentId,b.environment().getId()) && Objects.equals(serverId,b.serverId())).toList();
    if (selected.isEmpty() && environmentId!=null) throw new IllegalArgumentException("环境或节点绑定已变化，请刷新页面");
    List<ReleaseTask> running = tasks.findByTaskKindAndStatusIn(TaskKind.DEPLOY, List.of(TaskStatus.RUNNING));
    synchronized (this) {
      for (Binding b : selected) {
        if (!b.enabled() || pending.contains(b.key()) || running.stream().anyMatch(t -> targets(t,b))) continue;
        Cached c = cache.get(b.key());
        if (c!=null && Objects.equals(c.fingerprint(), b.fingerprint()) &&
            Duration.between(c.checkedAt(), clock.instant()).compareTo(COOLDOWN)<0) continue;
        if (pending.size() >= MAX_PENDING) break;
        pending.add(b.key());
        worker.submit(() -> inspect(b));
      }
    }
    return snapshot();
  }

  public synchronized void invalidate(Long environmentId,Long serverId) {
    cache.remove(environmentId+":"+serverId);
  }

  private void inspect(Binding original) {
    try {
      // A queued check must not use credentials or container names changed while it was waiting.
      Binding b = bindings().stream().filter(value -> value.key().equals(original.key())).findFirst().orElse(null);
      if (b==null || !b.enabled() || !b.fingerprint().equals(original.fingerprint())) return;
      if (tasks.findByTaskKindAndStatusIn(TaskKind.DEPLOY, List.of(TaskStatus.RUNNING)).stream().anyMatch(t -> targets(t,b))) return;
      Instant started = clock.instant();
      RuntimeProbe.Result result;
      try { result = probe.inspect(b.server(), b.service(), b.environment()); }
      catch (Exception error) { result = new RuntimeProbe.Result("ERROR", safeFailure(error), List.of()); }
      synchronized (this) { cache.put(b.key(), new Cached(b.fingerprint(), started, result)); }
    } catch (Exception error) {
      synchronized (this) { cache.put(original.key(), new Cached(original.fingerprint(), clock.instant(), new RuntimeProbe.Result("ERROR", safeFailure(error), List.of()))); }
    } finally { synchronized (this) { pending.remove(original.key()); } }
  }

  private List<Binding> bindings() {
    Map<Long, AppService> apps = services.findAll().stream().collect(Collectors.toMap(AppService::getId,a->a));
    Map<Long, DeploymentServer> nodes = servers.findAll().stream().collect(Collectors.toMap(DeploymentServer::getId,a->a));
    List<Binding> result = new ArrayList<>();
    for (ServiceEnvironment env : environments.findAll()) {
      AppService app=apps.get(env.getServiceId()); if(app==null||app.getServiceType()==ServiceType.FLUTTER_ANDROID) continue;
      List<Long> ids=env.getServerIds().isEmpty()?Collections.singletonList(null):env.getServerIds().stream().distinct().toList();
      for(Long id:ids) {
        DeploymentServer server=nodes.get(id);
        String fingerprint=String.valueOf(app.getUpdatedAt())+"/"+app.getServiceKey()+"/"+app.isEnabled()+"/"+
          env.getUpdatedAt()+"/"+env.getDeploymentMode()+"/"+env.getContainerName()+"/"+env.getComposeService()+"/"+env.isEnabled()+"/"+
          (server==null?"none":server.getUpdatedAt()+"/"+server.getHost()+"/"+server.getPort()+"/"+server.getComposeDirectory()+"/"+server.getComposeFile()+"/"+server.isEnabled());
        result.add(new Binding(app,env,server,id,env.getId()+":"+id,fingerprint));
      }
    }
    result.sort(Comparator.comparing((Binding b)->b.service().getId())
      .thenComparingInt(b -> switch(b.environment().getEnvironmentName()) { case "dev"->0; case "test"->1; case "prod"->2; default->3; })
      .thenComparing(b->b.serverId(),Comparator.nullsLast(Long::compareTo)));
    return result;
  }

  private Map<String, ReleaseTask> successfulHistory(List<Binding> bindings) {
    Map<String, ReleaseTask> result=new HashMap<>();
    var pageable=org.springframework.data.domain.PageRequest.of(0,200);
    int needed=(int)bindings.stream().filter(b->b.server()!=null).count();
    if(needed==0) return result;
    while(true) {
      var page=tasks.findByTaskKindAndStatusAndSimulateFalseOrderByFinishedAtDescIdDesc(TaskKind.DEPLOY,TaskStatus.SUCCESS,pageable);
      for(ReleaseTask task:page.getContent()) for(Binding b:bindings)
        if(!result.containsKey(b.key()) && targets(task,b)) result.put(b.key(),task);
      if(!page.hasNext() || result.size()>=needed) break;
      pageable=pageable.next();
    }
    return result;
  }

  static boolean targets(ReleaseTask task, Binding b) {
    if(task.isSimulate() || task.getTaskKind()!=TaskKind.DEPLOY || b.serverId()==null ||
      !Objects.equals(task.getServiceId(),b.service().getId()) || !Objects.equals(task.getEnvironmentName(),b.environment().getEnvironmentName())) return false;
    // Empty legacy target lists cannot establish which node was actually deployed.
    return task.targetServerIdList().contains(b.serverId());
  }
  private boolean later(LocalDateTime time, Instant checked) { return time!=null && time.atZone(ZoneId.systemDefault()).toInstant().isAfter(checked); }
  static String compare(Expected expected, List<RuntimeProbe.ContainerInfo> containers) {
    if(expected==null || containers.isEmpty()) return "UNKNOWN";
    if(nonblank(expected.imageDigest())) {
      String digest=digest(expected.imageDigest());
      if(digest==null) return "UNKNOWN";
      boolean missing=false;
      for(var c:containers) {
        if(c.repoDigests()==null || c.repoDigests().isEmpty()) { missing=true; continue; }
        List<String> valid=c.repoDigests().stream().filter(Objects::nonNull).map(RuntimeOverviewService::digest).filter(Objects::nonNull).toList();
        if(valid.isEmpty()) { missing=true; continue; }
        if(!valid.contains(digest)) return "DIFFERENT";
      }
      return missing?"UNKNOWN":"MATCH";
    }
    if(!nonblank(expected.imageReference())) return "UNKNOWN";
    if(containers.stream().anyMatch(c->!nonblank(c.imageReference()))) return "UNKNOWN";
    return containers.stream().allMatch(c->Objects.equals(expected.imageReference(),c.imageReference()))?"MATCH":"DIFFERENT";
  }
  private static String digest(String value) { var m=java.util.regex.Pattern.compile("(?:^|@)(sha256:[0-9a-fA-F]{64})$").matcher(value);return m.find()?m.group(1).toLowerCase(Locale.ROOT):null; }
  private static boolean nonblank(String value) { return value!=null && !value.isBlank(); }
  private static List<String> accessUrls(Binding b, List<RuntimeProbe.ContainerInfo> containers) {
    if(b.server()==null || b.service().getServiceType()!=ServiceType.FRONTEND) return List.of();
    // An SSH alias is not a public website address. A shared node's business entry
    // is displayed in server management, not misrepresented as every service's URL.
    if("SSH_CONFIG".equals(b.server().getConnectionMode())) return List.of();
    String host=b.server().getHost();
    if(host==null || !host.matches("[a-zA-Z0-9.:-]+")) return List.of();
    if(host.contains(":")) host="["+host+"]";
    Set<String> result=new LinkedHashSet<>();
    for(var container:containers) if("running".equals(container.state())) for(var p:container.ports()) {
      // Only an observed plain HTTP listener mapping is advertised. Environment metadata is not a port mapping.
      if(!"tcp".equals(p.protocol()) || !"80".equals(p.containerPort()) ||
        !Set.of("0.0.0.0","::").contains(p.hostIp()) || p.hostPort()==null || !p.hostPort().matches("[0-9]{1,5}")) continue;
      int port=Integer.parseInt(p.hostPort()); if(port<1 || port>65535) continue;
      result.add("http://"+host+(port==80?"":":"+port)+"/");
    }
    return List.copyOf(result);
  }
  static String safeFailure(Exception error) {
    for(Throwable cause=error;cause!=null;cause=cause.getCause())
      if(cause instanceof SshExecutionException sshError) return sshError.getMessage();
    String text=String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
    if(text.contains("auth fail") || text.contains("authentication") || text.contains("publickey")) return "SSH 认证失败，请检查节点账号或密钥";
    if(text.contains("permission") || text.contains("sudo") || text.contains("access denied")) return "读取权限不足，请检查节点 Docker、Compose 文件及 sudo 配置";
    if(text.contains("timeout") || text.contains("timed out") || text.contains("超时")) return "检查超时，请检查节点网络和 Docker 状态";
    if(text.contains("refused") || text.contains("no route") || text.contains("unknownhost")) return "无法连接节点，请检查 SSH 地址、端口和网络";
    return "运行状态读取失败，请检查节点连接、Docker 和 Compose 配置";
  }
  @PreDestroy public void close() { worker.shutdownNow(); }
}
