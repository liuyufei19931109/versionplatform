package com.local.release.service;

import com.local.release.domain.AppService;
import com.local.release.domain.DeploymentServer;
import com.local.release.domain.ServiceEnvironment;
import com.local.release.repo.AppServiceRepository;
import com.local.release.repo.DeploymentServerRepository;
import com.local.release.repo.ServiceEnvironmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContainerLogServiceTest {
  static final String A = "a".repeat(64), B = "b".repeat(64);
  @TempDir Path directory;
  ServiceEnvironmentRepository environments = mock(ServiceEnvironmentRepository.class);
  AppServiceRepository services = mock(AppServiceRepository.class);
  DeploymentServerRepository servers = mock(DeploymentServerRepository.class);
  SshClient ssh = mock(SshClient.class);
  ContainerLogService logs = new ContainerLogService(environments, services, servers, ssh);
  ServiceEnvironment environment = new ServiceEnvironment();
  DeploymentServer server = new DeploymentServer();

  @BeforeEach void setup() throws Exception {
    AppService service = new AppService(); ReflectionTestUtils.setField(service, "id", 1L); service.setName("示例服务"); service.setServiceKey("catalog");
    ReflectionTestUtils.setField(environment, "id", 3L);
    environment.setServiceId(1L); environment.setEnvironmentName("prod"); environment.setServerIds(List.of(9L));
    environment.setDeploymentMode("DOCKER_COMPOSE"); environment.setComposeService("catalog");
    ReflectionTestUtils.setField(server, "id", 9L); server.setName("prod-1"); server.setComposeDirectory(directory.toString()); server.setComposeFile("docker-compose.yml");
    when(environments.findById(3L)).thenReturn(Optional.of(environment));
    when(services.findById(1L)).thenReturn(Optional.of(service));
    when(servers.findById(9L)).thenReturn(Optional.of(server));
    Files.writeString(directory.resolve("docker"), """
      #!/bin/sh
      case "$1" in
        version) echo 24.0 ;;
        compose) printf '%s\\n%s\\n' "$FAKE_A" "$FAKE_B" ;;
        inspect) case "$4" in a*) echo "$4 /catalog-1 exited" ;; b*) echo "$4 /catalog-2 running" ;; esac ;;
        logs) echo "2026-10-19T06:03:22.123456789Z args: $*"; printf '2026-10-19T06:03:23.000000001Z \\033[31mERROR\\033[0m boom\\r\\n'; printf '\\tat com.example.Foo(Foo.java:1)\\n' ;;
      esac
      """.stripIndent());
    directory.resolve("docker").toFile().setExecutable(true);
  }

  private ContainerLogService.Request request(String container, Integer tail, String since, String until) {
    return new ContainerLogService.Request(3L, 9L, container, tail, since, until, "stdout", null);
  }

  private String runShell(String script) throws Exception {
    // A file instead of `sh -c` keeps the script intact on Windows, where argument quoting would split it.
    Path file = Files.writeString(Files.createTempFile(directory, "remote", ".sh"), script);
    ProcessBuilder builder = new ProcessBuilder("sh", file.toString()).redirectErrorStream(true);
    builder.environment().put("PATH", directory + java.io.File.pathSeparator + System.getenv("PATH"));
    builder.environment().put("FAKE_A", A); builder.environment().put("FAKE_B", B);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
    return output;
  }

  private static boolean shellAvailable() {
    try { Process process = new ProcessBuilder("sh", "-c", "exit 0").start(); return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0; }
    catch (Exception error) { return false; }
  }

  @Test void snapshotRunsOnlyDockerLogsAndPrefersRunningContainer() throws Exception {
    assumeTrue(shellAvailable(), "sh is not available on this test host");
    when(ssh.executeBounded(any(), anyString(), anyBoolean(), anyLong(), anyInt())).thenAnswer(call -> runShell(call.getArgument(1)));
    ContainerLogService.Snapshot snapshot = logs.snapshot(request(null, 300, "15m", null));
    assertThat(snapshot.header().containers()).extracting(ContainerLogService.Container::name).containsExactly("catalog-1", "catalog-2");
    assertThat(snapshot.header().containerId()).isEqualTo(B);
    assertThat(snapshot.header().containerName()).isEqualTo("catalog-2");
    assertThat(snapshot.lines()).hasSize(3);
    assertThat(snapshot.lines().get(0).text()).contains("logs --timestamps --tail 300 --since 15m " + B);
    assertThat(snapshot.lines().get(1)).isEqualTo(new ContainerLogService.Line("2026-10-19T06:03:23.000000001Z", "ERROR boom"));
    assertThat(snapshot.lines().get(2)).isEqualTo(new ContainerLogService.Line(null, "\tat com.example.Foo(Foo.java:1)"));
    assertThat(snapshot.truncated()).isFalse();
  }

  @Test void requestedContainerMustBelongToTheService() throws Exception {
    assumeTrue(shellAvailable(), "sh is not available on this test host");
    when(ssh.executeBounded(any(), anyString(), anyBoolean(), anyLong(), anyInt())).thenAnswer(call -> runShell(call.getArgument(1)));
    assertThat(logs.snapshot(request(A.substring(0, 12), null, null, null)).header().containerId()).isEqualTo(A);
    assertThatThrownBy(() -> logs.snapshot(request("c".repeat(12), null, null, null))).hasMessageContaining("所选容器已不存在");
  }

  @Test void followStreamsHeaderThenLinesAndEnds() throws Exception {
    assumeTrue(shellAvailable(), "sh is not available on this test host");
    when(ssh.stream(any(), anyString(), anyBoolean(), anyLong(), anyInt(), any(), any())).thenAnswer(call -> {
      SshProcessRunner.LineSink sink = call.getArgument(5);
      try (InputStream input = new ByteArrayInputStream(runShell(call.getArgument(1)).getBytes(StandardCharsets.UTF_8))) { SshProcessRunner.pumpLines(input, 16384, sink); }
      return SshProcessRunner.StreamEnd.ENDED;
    });
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    List<ContainerLogService.Line> received = Collections.synchronizedList(new ArrayList<>());
    ContainerLogService.FollowSession session = logs.prepareFollow(request(null, null, null, null), "yuan");
    session.run(new ContainerLogService.FollowListener() {
      public void header(ContainerLogService.Header header) { events.add("header:" + header.containerName()); }
      public void lines(List<ContainerLogService.Line> lines, int dropped) { events.add("lines"); received.addAll(lines); }
      public void heartbeat() {}
      public void end(String reason, String message) { events.add("end:" + reason); }
    });
    assertThat(events.get(0)).isEqualTo("header:catalog-2");
    assertThat(events.get(events.size() - 1)).isEqualTo("end:ENDED");
    assertThat(received).hasSize(3);
    assertThat(received.get(0).text()).contains("--tail 200").contains("--follow");
  }

  @Test void scriptQuotesConfigurationAndNeverFollowsSnapshots() {
    server.setComposeDirectory("/data/it's here");
    String snapshot = ContainerLogService.script(logs.target(request(null, null, null, null)), null, 100, null, "2026-10-19T06:03:22Z", false);
    assertThat(snapshot).contains("cd '/data/it'\"'\"'s here'").contains("--until '2026-10-19T06:03:22Z'").contains("| tail -c ").doesNotContain("--follow");
    String follow = ContainerLogService.script(logs.target(request(null, null, null, null)), A, 100, "5m", null, true);
    assertThat(follow).contains("exec docker logs --timestamps --tail 100 --since '5m' --follow").contains("want='" + A + "'");
    environment.setDeploymentMode("DOCKER_RUN"); environment.setContainerName("catalog.prod");
    assertThat(ContainerLogService.script(logs.target(request(null, null, null, null)), null, 100, null, null, false))
      .contains("--filter 'name=^/catalog\\.prod$'").doesNotContain("compose");
  }

  @Test void parametersAreValidated() {
    assertThatThrownBy(() -> ContainerLogService.tail(0, 1)).hasMessageContaining("日志行数");
    assertThatThrownBy(() -> ContainerLogService.tail(5001, 1)).hasMessageContaining("日志行数");
    assertThatThrownBy(() -> ContainerLogService.since("1d; rm -rf /")).hasMessageContaining("起始时间");
    assertThatThrownBy(() -> ContainerLogService.until("15m")).hasMessageContaining("截止时间");
    assertThatThrownBy(() -> ContainerLogService.containerId("'$(id)'")).hasMessageContaining("容器编号");
    assertThat(ContainerLogService.since("2026-10-19T06:03:22.123456789Z")).isNotNull();
    assertThatThrownBy(() -> ContainerLogService.source("/etc/passwd")).hasMessageContaining("日志来源");
    assertThatThrownBy(() -> ContainerLogService.fileId("../../sibling/info.log")).hasMessageContaining("日志文件编号");
    assertThat(ContainerLogService.source(null)).isEqualTo("application");
  }

  @Test void serverOutsideTheEnvironmentIsRejected() {
    assertThatThrownBy(() -> logs.snapshot(new ContainerLogService.Request(3L, 10L, null, null, null, null))).hasMessageContaining("不属于当前服务环境");
  }

  @Test void markerParsingIgnoresUnexpectedValues() {
    ContainerLogService.Parsed parsed = new ContainerLogService.Parsed();
    parsed.accept("random noise");
    parsed.accept(ContainerLogService.MARK + "CONTAINER " + A + " /bad$name running");
    parsed.accept(ContainerLogService.MARK + "CONTAINER short /x running");
    parsed.accept(ContainerLogService.MARK + "SELECTED " + A);
    assertThat(parsed.containers).containsExactly(new ContainerLogService.Container(A, "", "running"));
    assertThat(parsed.selected).isEqualTo(A);
    ContainerLogService.Parsed failed = new ContainerLogService.Parsed();
    failed.accept(ContainerLogService.MARK + "ERROR SOMETHING_ELSE");
    assertThat(failed.error).isEqualTo("NOT_FOUND");
  }

  @Test void followSlotsAreLimitedPerUserAndReleased() {
    List<ContainerLogService.FollowSession> sessions = new ArrayList<>();
    for (int i = 0; i < ContainerLogService.MAX_STREAMS_PER_USER; i++) sessions.add(logs.prepareFollow(request(null, null, null, null), "yuan"));
    assertThatThrownBy(() -> logs.prepareFollow(request(null, null, null, null), "yuan")).hasMessageContaining("实时日志");
    assertThatCode(() -> logs.prepareFollow(request(null, null, null, null), "other").release()).doesNotThrowAnyException();
    sessions.get(0).release(); sessions.get(0).release();
    assertThatCode(() -> logs.prepareFollow(request(null, null, null, null), "yuan")).doesNotThrowAnyException();
  }

  @Test void longLinesAreClippedAndCarriageReturnsRemoved() throws Exception {
    List<String> lines = new ArrayList<>();
    SshProcessRunner.pumpLines(new ByteArrayInputStream(("x".repeat(40) + "\nok\r\nlast").getBytes(StandardCharsets.UTF_8)), 10, lines::add);
    assertThat(lines).containsExactly("x".repeat(10) + " …[本行过长，已截断]", "ok", "last");
  }

  @Test void applicationMetadataAndTimeAreParsedWithoutDroppingTheFirstLogLine() throws Exception {
    String file = "example-catalog/info.log", fileId = "d".repeat(64);
    String encoded = Base64.getEncoder().encodeToString(file.getBytes(StandardCharsets.UTF_8));
    when(ssh.executeBounded(any(), anyString(), anyBoolean(), anyLong(), anyInt())).thenReturn(
      ContainerLogService.MARK + "CONTAINER " + A + " /catalog-1 running\n"
      + ContainerLogService.MARK + "SOURCE application\n"
      + ContainerLogService.MARK + "FILE " + fileId + " " + encoded + "\n"
      + ContainerLogService.MARK + "FILESELECTED " + fileId + "\n"
      + ContainerLogService.MARK + "TRUNCATED true\n"
      + ContainerLogService.MARK + "SELECTED " + A + "\n"
      + "2026-10-19T06:03:22.123Z 2026-10-19 08:03:22.123 INFO ready\n");
    ContainerLogService.Snapshot result = logs.snapshot(new ContainerLogService.Request(3L, 9L, null, 10, null, null));
    assertThat(result.header().source()).isEqualTo("application");
    assertThat(result.header().logDirectory()).isEqualTo("/var/log/apps");
    assertThat(result.header().files()).containsExactly(new ContainerLogService.LogFile(fileId, file));
    assertThat(result.header().fileId()).isEqualTo(fileId);
    assertThat(result.lines()).containsExactly(new ContainerLogService.Line("2026-10-19T06:03:22.123Z", "2026-10-19 08:03:22.123 INFO ready"));
    assertThat(result.truncated()).isTrue();
    verify(ssh).executeBounded(any(), contains("python3 - \"$sel\""), anyBoolean(), anyLong(), anyInt());
  }

  @Test void sourceFallbackIsVisibleAndUnsafeFileMetadataIsIgnored() {
    ContainerLogService.Parsed parsed = new ContainerLogService.Parsed();
    parsed.accept(ContainerLogService.MARK + "FILE " + A + " " + Base64.getEncoder().encodeToString("../another/info.log".getBytes(StandardCharsets.UTF_8)));
    parsed.accept(ContainerLogService.MARK + "FALLBACK NO_FILES");
    assertThat(parsed.files).isEmpty();
    assertThat(parsed.source).isEqualTo("stdout");
    assertThat(parsed.fallbackReason).contains("/var/log/apps").contains("标准输出");
  }

  @Test void unreadableApplicationFilesAreErrorsInsteadOfSuccessfulStdoutSnapshots() throws Exception {
    when(ssh.executeBounded(any(), anyString(), anyBoolean(), anyLong(), anyInt()))
      .thenReturn(ContainerLogService.MARK + "ERROR LOG_ACCESS\n");
    assertThatThrownBy(() -> logs.snapshot(new ContainerLogService.Request(3L, 9L, null, 10, null, null)))
      .hasMessageContaining("/var/log/apps").hasMessageContaining("读取权限");
  }

  private void composeLabelsDocker(String secondProject, String secondFile) throws Exception {
    Files.writeString(directory.resolve("docker"), """
      #!/bin/sh
      case "$1" in
        version) echo 24.0 ;;
        compose) exit 1 ;;
        ps)
          case "$*" in *'label=com.docker.compose.service=catalog'*) ;; *) exit 1;; esac
          case "$*" in *"label=com.docker.compose.project.working_dir=$(pwd -P)"*) ;; *) exit 1;; esac
          printf '%%s\\n%%s\\n' "$FAKE_A" "$FAKE_B" ;;
        inspect)
          case "$3" in
            *Config.Labels*) case "$4" in
              a*) echo "test|$(pwd -P)/docker-compose.yml,/tmp/override.yml" ;;
              b*) echo "%s|%s" ;; esac ;;
            *) echo "$4 /catalog-1 running" ;;
          esac ;;
        logs) echo '2026-10-19T06:00:00Z safe output' ;;
      esac
      """.formatted(secondProject, secondFile).stripIndent());
  }

  @Test void unreadableComposeConfigUsesServiceDirectoryAndExactBaseFileLabels() throws Exception {
    assumeTrue(shellAvailable(), "sh is not available on this test host");
    composeLabelsDocker("unrelated-project", "$(pwd -P)/another-compose.yml");
    when(ssh.executeBounded(any(), anyString(), anyBoolean(), anyLong(), anyInt())).thenAnswer(call -> runShell(call.getArgument(1)));
    ContainerLogService.Snapshot result = logs.snapshot(request(null, 10, null, null));
    assertThat(result.header().containerId()).isEqualTo(A);
    assertThat(result.header().containers()).hasSize(1);
    assertThat(result.lines()).containsExactly(new ContainerLogService.Line("2026-10-19T06:00:00Z", "safe output"));
  }

  @Test void composeLabelFallbackRejectsAmbiguousProjectsWithTheSameConfig() throws Exception {
    assumeTrue(shellAvailable(), "sh is not available on this test host");
    composeLabelsDocker("another-project", "$(pwd -P)/docker-compose.yml");
    when(ssh.executeBounded(any(), anyString(), anyBoolean(), anyLong(), anyInt())).thenAnswer(call -> runShell(call.getArgument(1)));
    assertThatThrownBy(() -> logs.snapshot(request(null, 10, null, null))).hasMessageContaining("无法读取 Compose 项目");
  }
}
