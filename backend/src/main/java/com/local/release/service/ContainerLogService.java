package com.local.release.service;

import com.local.release.domain.AppService;
import com.local.release.domain.DeploymentServer;
import com.local.release.domain.ModelEnums.ServiceType;
import com.local.release.domain.ServiceEnvironment;
import com.local.release.repo.AppServiceRepository;
import com.local.release.repo.DeploymentServerRepository;
import com.local.release.repo.ServiceEnvironmentRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only logs for a bound service node, scoped to its container and application log mounts. */
@Service
public class ContainerLogService {
  public record Container(String id, String name, String state) {}
  public record Line(String ts, String text) {}
  public record LogFile(String id, String name) {}
  public record Request(Long environmentId, Long serverId, String containerId, Integer tail, String since, String until, String source, String fileId) {
    public Request(Long environmentId, Long serverId, String containerId, Integer tail, String since, String until) {
      this(environmentId, serverId, containerId, tail, since, until, "application", null);
    }
  }
  public record Header(String serviceName, String environmentName, String serverName, List<Container> containers, String containerId,
      String containerName, String source, String logDirectory, List<LogFile> files, String fileId, String fallbackReason) {}
  public record Snapshot(Header header, List<Line> lines, boolean truncated, Instant fetchedAt) {}

  /** Events of one live session. Called from a single thread at a time; implementations should not throw. */
  public interface FollowListener {
    void header(Header header);
    void lines(List<Line> lines, int dropped);
    void heartbeat();
    void end(String reason, String message);
  }

  static final int DEFAULT_TAIL = 500, FOLLOW_TAIL = 200, MAX_TAIL = 5000, MAX_LINE_BYTES = 16384;
  static final int SNAPSHOT_CAP = 3 * 1024 * 1024, MAX_BUFFERED_LINES = 5000;
  static final int MAX_STREAMS = 12, MAX_STREAMS_PER_USER = 3;
  static final long SNAPSHOT_SECONDS = 45, FOLLOW_SECONDS = 30 * 60, FLUSH_MILLIS = 250, HEARTBEAT_MILLIS = 15_000;
  static final String MARK = "@@RELEASE_LOG ";
  static final String LOG_DIRECTORY = "/var/log/apps";
  private static final String APPLICATION_READER = applicationReader();
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
  private static final Pattern CONTAINER_ID = Pattern.compile("[a-f0-9]{12,64}");
  private static final Pattern FULL_ID = Pattern.compile("[a-f0-9]{64}");
  private static final Pattern SINCE = Pattern.compile("\\d{1,5}[smh]|\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z");
  private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z");
  private static final Pattern LOG_LINE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})) ?(.*)", Pattern.DOTALL);
  private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[()][A-Za-z0-9]|\u001B[=>]");
  private static final Set<String> STATES = Set.of("created", "running", "paused", "restarting", "removing", "exited", "dead");
  private static final Map<String, String> ERRORS = Map.ofEntries(
    Map.entry("NO_DOCKER", "目标服务器未安装 Docker，或当前账号无法执行 Docker。"),
    Map.entry("DOCKER_ACCESS", "无法访问 Docker，请检查服务器连接账号的 Docker 权限。"),
    Map.entry("COMPOSE_DIR", "无法进入 Compose 项目目录，请检查目录配置及读取权限。"),
    Map.entry("COMPOSE", "无法读取 Compose 项目，请检查配置文件及 .env 的读取权限。"),
    Map.entry("NOT_FOUND", "未找到该服务的容器，可能尚未发布或已被删除。"),
    Map.entry("CONTAINER_GONE", "所选容器已不存在（可能已重新发布），请重新选择容器。"),
    Map.entry("LOG_ACCESS", "无法读取该服务的应用日志，请检查 /var/log/apps 的读取权限。"),
    Map.entry("LOG_FILE_GONE", "所选日志文件已不存在或不属于当前服务，请重新选择日志文件。"),
    Map.entry("LOG_ARCHIVE", "该日志压缩包无法读取，请选择其他日志文件。"),
    Map.entry("LOG_TOO_LARGE", "该日志文件过大，请选择较小的归档文件或容器标准输出。"));

  private final ServiceEnvironmentRepository environments;
  private final AppServiceRepository services;
  private final DeploymentServerRepository servers;
  private final SshClient ssh;
  private final Map<String, Integer> streamsByUser = new HashMap<>();
  private final Set<FollowSession> active = ConcurrentHashMap.newKeySet();
  private int streams;

  public ContainerLogService(ServiceEnvironmentRepository environments, AppServiceRepository services,
      DeploymentServerRepository servers, SshClient ssh) {
    this.environments = environments; this.services = services; this.servers = servers; this.ssh = ssh;
  }

  record Target(AppService service, ServiceEnvironment environment, DeploymentServer server) {
    boolean sudo() { return "DOCKER_COMPOSE".equalsIgnoreCase(environment.getDeploymentMode()) && server.isComposeUseSudo(); }
  }

  public Snapshot snapshot(Request request) throws Exception {
    Target target = target(request);
    String script = script(target, containerId(request.containerId()), tail(request.tail(), DEFAULT_TAIL),
      since(request.since()), until(request.until()), false, source(request.source()), fileId(request.fileId()));
    String output = ssh.executeBounded(target.server(), script, target.sudo(), SNAPSHOT_SECONDS, SNAPSHOT_CAP + 65536);
    Parsed parsed = new Parsed();
    List<String> body = new ArrayList<>();
    int bodyBytes = 0;
    for (String raw : output.split("\n", -1)) {
      if (!parsed.selected()) { parsed.accept(raw); continue; }
      body.add(raw);
      bodyBytes += raw.getBytes(StandardCharsets.UTF_8).length + 1;
    }
    if (parsed.error != null) throw new IllegalArgumentException(ERRORS.get(parsed.error));
    if (!parsed.selected()) throw new IllegalStateException("日志读取结果不完整，请稍后重试。");
    if (!body.isEmpty() && body.get(body.size() - 1).isEmpty()) body.remove(body.size() - 1);
    // `tail -c` keeps the newest bytes; the first line is then partial and is dropped.
    boolean byteTruncated = bodyBytes >= SNAPSHOT_CAP - 4;
    boolean truncated = parsed.truncated || byteTruncated;
    if (byteTruncated && !body.isEmpty()) body.remove(0);
    List<Line> lines = new ArrayList<>(body.size());
    for (String raw : body) lines.add(line(raw));
    return new Snapshot(header(target, parsed), List.copyOf(lines), truncated, Instant.now());
  }

  /** Validates the request and reserves a stream slot; the caller must run or cancel the returned session. */
  public FollowSession prepareFollow(Request request, String user) {
    Target target = target(request);
    String script = script(target, containerId(request.containerId()), tail(request.tail(), FOLLOW_TAIL), since(request.since()), null, true,
      source(request.source()), fileId(request.fileId()));
    String owner = Objects.toString(user, "");
    synchronized (this) {
      if (streams >= MAX_STREAMS) throw new IllegalArgumentException("平台同时打开的实时日志过多，请稍后再试");
      if (streamsByUser.getOrDefault(owner, 0) >= MAX_STREAMS_PER_USER) throw new IllegalArgumentException("你已打开 " + MAX_STREAMS_PER_USER + " 个实时日志，请先关闭其他日志窗口");
      streams++; streamsByUser.merge(owner, 1, Integer::sum);
    }
    FollowSession session = new FollowSession(target, script, owner);
    active.add(session);
    return session;
  }

  public final class FollowSession {
    private final Target target; private final String script, owner;
    private final AtomicBoolean cancelled = new AtomicBoolean(), released = new AtomicBoolean();
    private FollowSession(Target target, String script, String owner) { this.target = target; this.script = script; this.owner = owner; }
    public void cancel() { cancelled.set(true); }
    public boolean cancelled() { return cancelled.get(); }

    /** Blocks until the remote command ends, the session is cancelled, or the time limit passes. Always releases its slot. */
    public void run(FollowListener listener) {
      try {
        Parsed parsed = new Parsed();
        ArrayDeque<Line> buffer = new ArrayDeque<>();
        int[] dropped = {0};
        AtomicBoolean finished = new AtomicBoolean();
        SshProcessRunner.LineSink sink = raw -> {
          if (!parsed.selected()) { parsed.accept(raw); return; }
          if ((MARK + "HEARTBEAT").equals(raw)) return;
          Line value = line(raw);
          synchronized (buffer) {
            if (buffer.size() >= MAX_BUFFERED_LINES) { buffer.pollFirst(); dropped[0]++; }
            buffer.addLast(value);
          }
        };
        boolean[] headerSent = {false};
        Runnable flush = () -> {
          if (!headerSent[0] && parsed.selected()) { listener.header(header(target, parsed)); headerSent[0] = true; }
          List<Line> batch; int skipped;
          synchronized (buffer) { batch = new ArrayList<>(buffer); buffer.clear(); skipped = dropped[0]; dropped[0] = 0; }
          if (!batch.isEmpty() || skipped > 0) listener.lines(List.copyOf(batch), skipped);
        };
        Thread flusher = Thread.ofVirtual().name("container-log-flush").start(() -> {
          long beat = System.currentTimeMillis();
          while (!finished.get() && !cancelled.get()) {
            try { Thread.sleep(FLUSH_MILLIS); } catch (InterruptedException error) { return; }
            if (finished.get()) return;
            int before; synchronized (buffer) { before = buffer.size() + dropped[0]; }
            boolean pendingHeader = !headerSent[0] && parsed.selected();
            flush.run();
            long now = System.currentTimeMillis();
            if (before > 0 || pendingHeader) beat = now;
            else if (now - beat >= HEARTBEAT_MILLIS) { listener.heartbeat(); beat = now; }
          }
        });
        SshProcessRunner.StreamEnd end;
        try { end = ssh.stream(target.server(), script, target.sudo(), FOLLOW_SECONDS, MAX_LINE_BYTES, sink, cancelled::get); }
        finally { finished.set(true); flusher.interrupt(); flusher.join(2000); }
        if (cancelled.get()) return;
        if (parsed.error != null) { listener.end("ERROR", ERRORS.get(parsed.error)); return; }
        flush.run();
        if (end == SshProcessRunner.StreamEnd.TIME_LIMIT) listener.end("TIME_LIMIT", "已持续跟踪 " + (FOLLOW_SECONDS / 60) + " 分钟，自动停止；可继续跟踪");
        else if (!parsed.selected()) listener.end("ERROR", "日志读取结果不完整，请稍后重试。");
        else listener.end("ENDED", "日志输出已结束（归档文件读取完毕，或容器已停止）");
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      } catch (Exception error) {
        if (!cancelled.get()) listener.end("ERROR", failureMessage(error));
      } finally { release(); }
    }

    void release() {
      if (!released.compareAndSet(false, true)) return;
      active.remove(this);
      synchronized (ContainerLogService.this) {
        streams--; streamsByUser.computeIfPresent(owner, (key, count) -> count <= 1 ? null : count - 1);
      }
    }
  }

  @PreDestroy void stop() { active.forEach(FollowSession::cancel); }

  Target target(Request request) {
    if (request.environmentId() == null || request.serverId() == null) throw new IllegalArgumentException("请选择要查看日志的服务节点");
    ServiceEnvironment environment = environments.findById(request.environmentId()).orElseThrow(() -> new IllegalArgumentException("服务环境不存在"));
    if (!environment.getServerIds().contains(request.serverId())) throw new IllegalArgumentException("该服务器不属于当前服务环境");
    AppService service = services.findById(environment.getServiceId()).orElseThrow(() -> new IllegalArgumentException("服务不存在"));
    if (service.getServiceType() == ServiceType.FLUTTER_ANDROID) throw new IllegalArgumentException("App 没有服务器容器日志");
    DeploymentServer server = servers.findById(request.serverId()).orElseThrow(() -> new IllegalArgumentException("服务器不存在"));
    return new Target(service, environment, server);
  }

  static String script(Target target, String containerId, int tail, String since, String until, boolean follow) {
    return script(target, containerId, tail, since, until, follow, "stdout", null);
  }

  static String script(Target target, String containerId, int tail, String since, String until, boolean follow, String source, String fileId) {
    ServiceEnvironment environment = target.environment();
    StringBuilder script = new StringBuilder();
    script.append("command -v docker >/dev/null 2>&1 || { echo '").append(MARK).append("ERROR NO_DOCKER'; exit 0; }\n");
    script.append("docker version --format '{{.Server.Version}}' >/dev/null 2>&1 || { echo '").append(MARK).append("ERROR DOCKER_ACCESS'; exit 0; }\n");
    String mode = environment.getDeploymentMode();
    if ("DOCKER_COMPOSE".equalsIgnoreCase(mode)) {
      String directory = target.server().getComposeDirectory(), name = environment.getComposeService();
      String file = target.server().getComposeFile();
      if (file == null || file.isBlank()) file = "docker-compose.yml";
      if (directory == null || directory.isBlank() || directory.length() > 500 || hasControl(directory)) throw new IllegalArgumentException("未配置有效的 Compose 项目目录");
      if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("未配置有效的 Compose 服务名");
      if (file.length() > 500 || file.startsWith("-") || hasControl(file)) throw new IllegalArgumentException("Compose 配置文件路径无效");
      script.append("cd ").append(quote(directory)).append(" 2>/dev/null || { echo '").append(MARK).append("ERROR COMPOSE_DIR'; exit 0; }\n");
      script.append("if ! ids=$(docker compose -f ").append(quote(file)).append(" ps -a -q ").append(quote(name)).append(" 2>/dev/null); then\n");
      // Reading logs must not require access to deployment secrets in .env. Match
      // Docker's immutable Compose labels instead, including the exact base file.
      script.append("  project_dir=$(pwd -P); config_file=$(readlink -f -- ").append(quote(file)).append(" 2>/dev/null) || { echo '").append(MARK).append("ERROR COMPOSE'; exit 0; }\n");
      script.append("  case \"$config_file\" in *,*) echo '").append(MARK).append("ERROR COMPOSE'; exit 0;; esac\n");
      script.append("  candidates=$(docker ps -a -q --no-trunc --filter ").append(quote("label=com.docker.compose.service=" + name))
        .append(" --filter \"label=com.docker.compose.project.working_dir=$project_dir\" 2>/dev/null) || { echo '").append(MARK).append("ERROR COMPOSE'; exit 0; }\n");
      script.append("  ids=''; project=''\n  for candidate in $candidates; do\n");
      script.append("    labels=$(docker inspect -f '{{ index .Config.Labels \"com.docker.compose.project\" }}|{{ index .Config.Labels \"com.docker.compose.project.config_files\" }}' \"$candidate\" 2>/dev/null) || continue\n");
      script.append("    candidate_project=${labels%%|*}; configs=${labels#*|}\n");
      script.append("    case \",$configs,\" in *,\"$config_file\",*)\n");
      script.append("      [ -n \"$candidate_project\" ] && [ \"$candidate_project\" != '<no value>' ] || continue\n");
      script.append("      if [ -n \"$project\" ] && [ \"$project\" != \"$candidate_project\" ]; then echo '").append(MARK).append("ERROR COMPOSE'; exit 0; fi\n");
      script.append("      project=$candidate_project; ids=\"$ids $candidate\";;\n    esac\n  done\n");
      script.append("  [ -n \"$ids\" ] || { echo '").append(MARK).append("ERROR COMPOSE'; exit 0; }\nfi\n");
    } else if ("DOCKER_RUN".equalsIgnoreCase(mode)) {
      String name = environment.getContainerName();
      if (name == null || name.isBlank()) name = target.service().getServiceKey() + "-" + environment.getEnvironmentName();
      if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("未配置有效的容器名称");
      script.append("ids=$(docker ps -a -q --no-trunc --filter ").append(quote("name=^/" + name.replace(".", "\\.") + "$")).append(" 2>/dev/null)\n");
    } else throw new IllegalArgumentException("该部署模式暂不支持查看日志");
    script.append("want=").append(quote(containerId == null ? "" : containerId)).append("; sel=''; first=''; live=''\n");
    script.append("for id in $ids; do\n");
    script.append("  info=$(docker inspect -f '{{.Id}} {{.Name}} {{.State.Status}}' \"$id\" 2>/dev/null) || continue\n");
    script.append("  echo \"").append(MARK).append("CONTAINER $info\"\n");
    script.append("  full=${info%% *}; state=${info##* }\n");
    script.append("  [ -z \"$first\" ] && first=$full\n");
    script.append("  [ -z \"$live\" ] && [ \"$state\" = running ] && live=$full\n");
    script.append("  if [ -n \"$want\" ]; then case \"$full\" in \"$want\"*) sel=$full;; esac; fi\n");
    script.append("done\n");
    script.append("[ -z \"$want\" ] && sel=${live:-$first}\n");
    script.append("if [ -z \"$sel\" ]; then if [ -n \"$want\" ]; then echo '").append(MARK).append("ERROR CONTAINER_GONE'; else echo '").append(MARK).append("ERROR NOT_FOUND'; fi; exit 0; fi\n");
    if ("application".equals(source)) {
      script.append("if command -v python3 >/dev/null 2>&1; then\n");
      if (follow) script.append("python3 -c ").append(quote("exec(__import__('base64').b64decode('"
        + Base64.getEncoder().encodeToString(APPLICATION_READER.getBytes(StandardCharsets.UTF_8)) + "'))"));
      else script.append("python3 -");
      script.append(" \"$sel\" ").append(quote(fileId == null ? "" : fileId)).append(' ').append(tail)
        .append(' ').append(quote(since == null ? "" : since)).append(' ').append(quote(until == null ? "" : until))
        .append(' ').append(follow ? "1\n" : "0 <<'RELEASE_APP_LOG_PY'\n");
      if (!follow) script.append(APPLICATION_READER).append("\nRELEASE_APP_LOG_PY\n");
      script.append("code=$?\n[ \"$code\" -eq 75 ] || exit \"$code\"\n");
      script.append("echo '").append(MARK).append("FALLBACK NO_FILES'\n");
      script.append("else\necho '").append(MARK).append("FALLBACK NO_PYTHON'\nfi\n");
    }
    script.append("echo '").append(MARK).append("SOURCE stdout'\n");
    script.append("echo \"").append(MARK).append("SELECTED $sel\"\n");
    StringBuilder logs = new StringBuilder("docker logs --timestamps --tail ").append(tail);
    if (since != null) logs.append(" --since ").append(quote(since));
    if (until != null) logs.append(" --until ").append(quote(until));
    if (follow) script.append("exec ").append(logs).append(" --follow \"$sel\" 2>&1\n");
    else script.append(logs).append(" \"$sel\" 2>&1 | tail -c ").append(SNAPSHOT_CAP).append("\n");
    return script.toString();
  }

  /** Marker lines precede the log body; once SELECTED is seen everything else is container output. */
  static final class Parsed {
    final List<Container> containers = Collections.synchronizedList(new ArrayList<>());
    final List<LogFile> files = Collections.synchronizedList(new ArrayList<>());
    volatile String selected, error;
    volatile String source = "stdout", fileId, fallbackReason;
    volatile boolean truncated;
    boolean selected() { return selected != null; }
    void accept(String raw) {
      if (!raw.startsWith(MARK)) return;
      String[] parts = raw.substring(MARK.length()).trim().split(" ", 4);
      switch (parts[0]) {
        case "ERROR" -> { if (parts.length > 1 && ERRORS.containsKey(parts[1])) error = parts[1]; else error = "NOT_FOUND"; }
        case "CONTAINER" -> {
          if (parts.length < 4 || !FULL_ID.matcher(parts[1]).matches() || containers.size() >= 24) return;
          String name = parts[2].startsWith("/") ? parts[2].substring(1) : parts[2];
          containers.add(new Container(parts[1], NAME.matcher(name).matches() ? name : "", STATES.contains(parts[3]) ? parts[3] : "unknown"));
        }
        case "SELECTED" -> { if (parts.length > 1 && FULL_ID.matcher(parts[1]).matches()) selected = parts[1]; else error = "NOT_FOUND"; }
        case "SOURCE" -> { if (parts.length > 1 && Set.of("application", "stdout").contains(parts[1])) source = parts[1]; }
        case "FILE" -> {
          if (parts.length < 3 || !FULL_ID.matcher(parts[1]).matches() || files.size() >= 300) return;
          try {
            String name = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
            if (name.length() > 1000 || name.startsWith("/") || name.contains("\\") || hasControl(name)
                || Arrays.stream(name.split("/", -1)).anyMatch(p -> p.isBlank() || p.equals(".") || p.equals(".."))) return;
            files.add(new LogFile(parts[1], name));
          } catch (IllegalArgumentException ignored) {}
        }
        case "FILESELECTED" -> { if (parts.length > 1 && FULL_ID.matcher(parts[1]).matches()) fileId = parts[1]; }
        case "TRUNCATED" -> truncated = parts.length > 1 && "true".equals(parts[1]);
        case "FALLBACK" -> fallbackReason = parts.length > 1 && "NO_PYTHON".equals(parts[1])
          ? "服务器未安装 Python 3，当前显示容器标准输出。"
          : "该服务未在 /var/log/apps 下挂载独立日志目录，或目录中暂无日志文件，当前显示容器标准输出。";
        default -> {}
      }
    }
  }

  private static Header header(Target target, Parsed parsed) {
    List<Container> containers;
    synchronized (parsed.containers) { containers = List.copyOf(parsed.containers); }
    String name = containers.stream().filter(c -> c.id().equals(parsed.selected)).map(Container::name).findFirst().orElse("");
    List<LogFile> files;
    synchronized (parsed.files) { files = List.copyOf(parsed.files); }
    return new Header(target.service().getName(), target.environment().getEnvironmentName(), target.server().getName(), containers, parsed.selected, name,
      parsed.source, LOG_DIRECTORY, files, parsed.fileId, parsed.fallbackReason);
  }

  static Line line(String raw) {
    String clean = ANSI.matcher(raw).replaceAll("");
    clean = clean.codePoints().filter(c -> c == '\t' || !Character.isISOControl(c))
      .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    Matcher match = LOG_LINE.matcher(clean);
    return match.matches() ? new Line(match.group(1), match.group(2)) : new Line(null, clean);
  }

  static int tail(Integer value, int fallback) {
    if (value == null) return fallback;
    if (value < 1 || value > MAX_TAIL) throw new IllegalArgumentException("日志行数须为 1～" + MAX_TAIL);
    return value;
  }
  static String since(String value) {
    if (value == null || value.isBlank()) return null;
    if (!SINCE.matcher(value).matches()) throw new IllegalArgumentException("日志起始时间格式不正确");
    return value;
  }
  static String until(String value) {
    if (value == null || value.isBlank()) return null;
    if (!TIMESTAMP.matcher(value).matches()) throw new IllegalArgumentException("日志截止时间格式不正确");
    return value;
  }
  static String containerId(String value) {
    if (value == null || value.isBlank()) return null;
    if (!CONTAINER_ID.matcher(value).matches()) throw new IllegalArgumentException("容器编号格式不正确");
    return value;
  }
  static String source(String value) {
    if (value == null || value.isBlank()) return "application";
    if (!Set.of("application", "stdout").contains(value)) throw new IllegalArgumentException("日志来源无效");
    return value;
  }
  static String fileId(String value) {
    if (value == null || value.isBlank()) return null;
    if (!FULL_ID.matcher(value).matches()) throw new IllegalArgumentException("日志文件编号格式不正确");
    return value;
  }
  private static String applicationReader() {
    try (InputStream input = ContainerLogService.class.getResourceAsStream("/runtime/application-logs.py")) {
      if (input == null) throw new IllegalStateException("Application log reader is missing");
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) { throw new IllegalStateException("Cannot load application log reader", error); }
  }
  static String failureMessage(Exception error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof SshExecutionException ssh) return ssh.getMessage().replaceFirst("^\\[[A-Z_]+] ", "");
      if (cause instanceof IllegalArgumentException argument && argument.getMessage() != null) return argument.getMessage();
    }
    return "日志读取中断，请检查节点连接后重试。";
  }
  private static boolean hasControl(String value) { return value.codePoints().anyMatch(Character::isISOControl); }
  private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
}
