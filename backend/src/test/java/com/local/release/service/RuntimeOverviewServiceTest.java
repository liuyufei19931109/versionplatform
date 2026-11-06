package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeOverviewServiceTest {
  private final AppServiceRepository services = mock(AppServiceRepository.class);
  private final ServiceEnvironmentRepository environments = mock(ServiceEnvironmentRepository.class);
  private final DeploymentServerRepository servers = mock(DeploymentServerRepository.class);
  private final ReleaseTaskRepository tasks = mock(ReleaseTaskRepository.class);
  private final ArtifactRepository artifacts = mock(ArtifactRepository.class);
  private final RuntimeProbe probe = mock(RuntimeProbe.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-09-06T06:00:00Z"));
  private final ManualExecutor executor = new ManualExecutor();
  private RuntimeOverviewService overview;
  private AppService app;
  private ServiceEnvironment environment;
  private DeploymentServer first, second;
  private List<ReleaseTask> history = List.of(), activity = List.of();
  private List<Artifact> versions = List.of();
  private static final String IMAGE = "registry.example/team/ui:v1";
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String OTHER_DIGEST = "sha256:" + "b".repeat(64);

  @BeforeEach void setUp() throws Exception {
    app = id(new AppService(), 35L); app.setName("前端服务"); app.setServiceKey("example-ui"); app.setServiceType(ServiceType.FRONTEND);
    environment = id(new ServiceEnvironment(), 8L); environment.setServiceId(35L); environment.setEnvironmentName("test"); environment.setServerIds(List.of(3L));
    first = node(3L, "203.0.113.10"); second = node(4L, "203.0.113.11");
    doReturn(List.of(app)).when(services).findAll();
    when(environments.findAll()).thenReturn(List.of(environment));
    when(servers.findAll()).thenReturn(List.of(first, second));
    when(tasks.findByTaskKindAndStatusAndSimulateFalseOrderByFinishedAtDescIdDesc(eq(TaskKind.DEPLOY), eq(TaskStatus.SUCCESS), any(Pageable.class)))
      .thenAnswer(call -> {
        Pageable page = call.getArgument(2);
        int from = Math.min((int)page.getOffset(), history.size()), to = Math.min(from + page.getPageSize(), history.size());
        return new SliceImpl<>(history.subList(from, to), page, to < history.size());
      });
    when(tasks.findRuntimeActivity(eq(TaskKind.DEPLOY), eq(TaskStatus.RUNNING), any(LocalDateTime.class)))
      .thenAnswer(call -> activity);
    when(tasks.findByTaskKindAndStatusIn(eq(TaskKind.DEPLOY), anyList()))
      .thenAnswer(call -> activity.stream().filter(task -> task.getStatus() == TaskStatus.RUNNING).toList());
    when(artifacts.findAllById(any())).thenAnswer(call -> versions);
    when(probe.inspect(any(), any(), any())).thenReturn(ok(container(IMAGE, List.of("registry.example/team/ui@" + DIGEST), List.of())));
    overview = new RuntimeOverviewService(services, environments, servers, tasks, artifacts, probe, clock, executor);
  }

  @AfterEach void close() { overview.close(); }

  @Test void iapAliasIsNeverAdvertisedAsAServiceWebsite()throws Exception {
    first.setConnectionMode("SSH_CONFIG");first.setHost("example-prod-node");first.setBusinessUrl("https://shared-entry.example");
    when(probe.inspect(any(),any(),any())).thenReturn(ok(container(IMAGE,List.of(),List.of(new RuntimeProbe.PortBinding("0.0.0.0","8080","80","tcp")))));
    overview.requestCheck(8L,3L);executor.runAll();
    assertThat(row().accessUrls()).isEmpty();
  }

  @Test void snapshotNeverConnectsAndUnboundEnvironmentIsExplicit() {
    ServiceEnvironment prod = id(new ServiceEnvironment(), 9L); prod.setServiceId(35L); prod.setEnvironmentName("prod");
    when(environments.findAll()).thenReturn(List.of(prod, environment));
    var result = overview.snapshot();
    assertThat(result.checking()).isFalse();
    assertThat(result.rows()).extracting(RuntimeOverviewService.Row::environmentName).containsExactly("test", "prod");
    assertThat(result.rows()).extracting(RuntimeOverviewService.Row::checkState).containsExactly("UNCHECKED", "NOT_CONFIGURED");
    assertThat(result.rows()).allSatisfy(row -> { assertThat(row.comparison()).isEqualTo("UNKNOWN"); assertThat(row.accessUrls()).isEmpty(); });
    verifyNoInteractions(probe);
    assertThat(executor.queued()).isZero();
  }

  @Test void expectedVersionIsLastSuccessfulRealDeploymentForEachBoundNode() {
    environment.setServerIds(List.of(3L, 4L));
    ReleaseTask node4 = release(80L, 102L, TaskStatus.SUCCESS, List.of(4L), false, clock.instant().minusSeconds(1));
    ReleaseTask simulation = release(79L, 999L, TaskStatus.SUCCESS, List.of(3L), true, clock.instant().minusSeconds(2));
    ReleaseTask legacy = release(78L, 998L, TaskStatus.SUCCESS, List.of(), false, clock.instant().minusSeconds(3));
    ReleaseTask node3 = release(77L, 101L, TaskStatus.SUCCESS, List.of(3L), false, clock.instant().minusSeconds(4));
    history = List.of(node4, simulation, legacy, node3);
    versions = List.of(artifact(101L, "v1", IMAGE), artifact(102L, "v2", "registry.example/team/ui:v2"));
    var rows = overview.snapshot().rows();
    assertThat(rows).extracting(row -> row.expected().taskId()).containsExactly(77L, 80L);
    assertThat(rows).extracting(row -> row.expected().version()).containsExactly("v1", "v2");
    verifyNoInteractions(probe);
  }

  @Test void historyContinuesToLaterPagesWhenRecentSuccessesBelongToOtherNodes() {
    List<ReleaseTask> values = new ArrayList<>();
    for (long id = 400; id > 200; id--) values.add(release(id, 100L, TaskStatus.SUCCESS, List.of(99L), false, clock.instant()));
    values.add(release(199L, 101L, TaskStatus.SUCCESS, List.of(3L), false, clock.instant().minusSeconds(2)));
    history = values;
    assertThat(overview.snapshot().rows().getFirst().expected().taskId()).isEqualTo(199L);
    verify(tasks, times(2)).findByTaskKindAndStatusAndSimulateFalseOrderByFinishedAtDescIdDesc(eq(TaskKind.DEPLOY), eq(TaskStatus.SUCCESS), any(Pageable.class));
  }

  @Test void duplicateChecksAreCoalescedAndCooldownDoesNotStartAnotherProbe() throws Exception {
    overview.requestCheck(null, null);
    overview.requestCheck(8L, 3L);
    assertThat(executor.queued()).isEqualTo(1);
    assertThat(row().checkState()).isEqualTo("CHECKING");
    executor.runAll();
    assertThat(row().checkState()).isEqualTo("READY");
    overview.requestCheck(null, null);
    assertThat(executor.queued()).isZero();
    clock.advance(Duration.ofSeconds(16));
    overview.requestCheck(8L, 3L);
    executor.runAll();
    verify(probe, times(2)).inspect(first, app, environment);
  }

  @Test void successfulCacheExpiresAndAnAttemptAfterInspectionMakesVersionComparisonUnknown() {
    history = List.of(release(77L, 101L, TaskStatus.SUCCESS, List.of(3L), false, clock.instant().minusSeconds(60)));
    versions = List.of(artifact(101L, "v1", IMAGE));
    inspect();
    assertThat(row().comparison()).isEqualTo("MATCH");
    clock.advance(Duration.ofSeconds(1));
    activity = List.of(release(78L, 102L, TaskStatus.FAILED, List.of(3L), false, clock.instant()));
    assertThat(row().stale()).isTrue();
    assertThat(row().comparison()).isEqualTo("UNKNOWN");
    assertThat(row().message()).contains("发布");
    activity = List.of();
    clock.advance(Duration.ofMinutes(6));
    assertThat(row().stale()).isTrue();
    assertThat(row().comparison()).isEqualTo("UNKNOWN");
  }

  @Test void RunningDeploymentOnAnotherNodeDoesNotInvalidateOrBlockThisNode() {
    environment.setServerIds(List.of(3L, 4L));
    overview.requestCheck(8L, 3L); executor.runAll();
    clock.advance(Duration.ofSeconds(20));
    activity = List.of(release(78L, 102L, TaskStatus.RUNNING, List.of(4L), false, clock.instant()));
    assertThat(overview.snapshot().rows().getFirst().stale()).isFalse();
    overview.requestCheck(null, null);
    assertThat(executor.queued()).isEqualTo(1);
    assertThat(overview.snapshot().rows()).extracting(RuntimeOverviewService.Row::checkState).containsExactly("CHECKING", "UNCHECKED");
  }

  @Test void queuedCheckDoesNotUseChangedConfigurationOrStartDuringDeployment() throws Exception {
    overview.requestCheck(null, null);
    environment.setContainerName("new-container");
    executor.runAll();
    assertThat(row().checkState()).isEqualTo("UNCHECKED");
    verifyNoInteractions(probe);
    overview.requestCheck(null, null);
    activity = List.of(release(78L, 102L, TaskStatus.RUNNING, List.of(3L), false, clock.instant()));
    executor.runAll();
    assertThat(row().checkState()).isEqualTo("UNCHECKED");
    assertThat(overview.snapshot().checking()).isFalse();
    verifyNoInteractions(probe);
  }

  @Test void failedProbeIsCachedAsErrorWithoutRawExceptionOrSecrets() throws Exception {
    when(probe.inspect(any(), any(), any())).thenThrow(new IllegalStateException("permission denied password=do-not-return-this-value"));
    inspect();
    assertThat(row().checkState()).isEqualTo("ERROR");
    assertThat(row().comparison()).isEqualTo("UNKNOWN");
    assertThat(row().message()).contains("权限").doesNotContain("password", "do-not-return-this-value");
    assertThat(row().containers()).isEmpty();
    assertThat(overview.snapshot().checking()).isFalse();
  }

  @Test void failedBindingReloadClearsPendingAndLeavesAnExplicitSafeError() {
    overview.requestCheck(null, null);
    when(services.findAll()).thenThrow(new IllegalStateException("database credential=private-test-value"));
    executor.runAll();
    doReturn(List.of(app)).when(services).findAll();
    assertThat(overview.snapshot().checking()).isFalse();
    assertThat(row().checkState()).isEqualTo("ERROR");
    assertThat(row().message()).doesNotContain("credential", "private-test-value");
    verifyNoInteractions(probe);
  }
  @Test void unknownOrMixedDigestsCannotShowMatch() {
    var expected = new RuntimeOverviewService.Expected(1L, 2L, IMAGE, DIGEST, "abc", "v1", null);
    var matching = container(IMAGE, List.of("registry.example/team/ui@" + DIGEST), List.of());
    var different = container(IMAGE, List.of("registry.example/team/ui@" + OTHER_DIGEST), List.of());
    var missing = container(IMAGE, List.of(), List.of());
    var malformed = container(IMAGE, List.of("not-a-digest"), List.of());
    assertThat(RuntimeOverviewService.compare(expected, List.of())).isEqualTo("UNKNOWN");
    assertThat(RuntimeOverviewService.compare(expected, List.of(matching, missing))).isEqualTo("UNKNOWN");
    assertThat(RuntimeOverviewService.compare(expected, List.of(malformed))).isEqualTo("UNKNOWN");
    assertThat(RuntimeOverviewService.compare(expected, List.of(matching, different))).isEqualTo("DIFFERENT");
    assertThat(RuntimeOverviewService.compare(expected, List.of(matching, matching))).isEqualTo("MATCH");
    assertThat(RuntimeOverviewService.compare(null, List.of(matching))).isEqualTo("UNKNOWN");
  }

  @Test void accessAddressOnlyUsesObservedPublicHttpMappingAndNeverConfiguredPortOrHttps() throws Exception {
    environment.setHostPort(443); environment.setHealthCheckUrl("https://unverified.example/health");
    var ports = List.of(
      new RuntimeProbe.PortBinding("127.0.0.1", "8081", "80", "tcp"),
      new RuntimeProbe.PortBinding("0.0.0.0", "443", "443", "tcp"),
      new RuntimeProbe.PortBinding("0.0.0.0", "8082", "80", "udp"),
      new RuntimeProbe.PortBinding("0.0.0.0", "8080", "80", "tcp"),
      new RuntimeProbe.PortBinding("::", "8080", "80", "tcp"));
    when(probe.inspect(any(), any(), any())).thenReturn(ok(container(IMAGE, List.of(), ports)));
    inspect();
    assertThat(row().accessUrls()).containsExactly("http://203.0.113.10:8080/");
    app.setServiceType(ServiceType.BACKEND);
    assertThat(row().accessUrls()).isEmpty();
  }

  @Test void missingContainerIsNotReportedAsHealthyOrMatching() throws Exception {
    when(probe.inspect(any(), any(), any())).thenReturn(new RuntimeProbe.Result("NOT_FOUND", "未找到该服务的容器。", List.of()));
    inspect();
    assertThat(row().containers()).isEmpty();
    assertThat(row().comparison()).isEqualTo("UNKNOWN");
    assertThat(row().message()).contains("未找到");
  }

  @Test void independentNodesRunSequentiallyOnTheProductionExecutor() throws Exception {
    overview.close();
    overview = new RuntimeOverviewService(services, environments, servers, tasks, artifacts, probe);
    environment.setServerIds(List.of(3L, 4L));
    CountDownLatch firstStarted = new CountDownLatch(1), unblock = new CountDownLatch(1), bothFinished = new CountDownLatch(2);
    AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
    when(probe.inspect(any(), any(), any())).thenAnswer(call -> {
      int concurrent = active.incrementAndGet(); maximum.accumulateAndGet(concurrent, Math::max);
      try {
        if (((DeploymentServer)call.getArgument(0)).getId().equals(3L)) {
          firstStarted.countDown();
          if (!unblock.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        }
        return ok(container(IMAGE, List.of(), List.of()));
      } finally { active.decrementAndGet(); bothFinished.countDown(); }
    });
    try {
      overview.requestCheck(null, null);
      assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
      overview.requestCheck(null, null);
      verify(probe, times(1)).inspect(any(), any(), any());
    } finally { unblock.countDown(); }
    assertThat(bothFinished.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(maximum.get()).isEqualTo(1);
    verify(probe, times(2)).inspect(any(), any(), any());
  }

  private void inspect() { overview.requestCheck(null, null); executor.runAll(); }
  private RuntimeOverviewService.Row row() { return overview.snapshot().rows().getFirst(); }
  private static RuntimeProbe.Result ok(RuntimeProbe.ContainerInfo container) { return new RuntimeProbe.Result("OK", "只读检查完成。", List.of(container)); }
  private static RuntimeProbe.ContainerInfo container(String image, List<String> digests, List<RuntimeProbe.PortBinding> ports) {
    return new RuntimeProbe.ContainerInfo("c".repeat(64), "example-ui", "running", "healthy", image, "sha256:" + "d".repeat(64), digests, ports, 268435456L, 10485760L, List.of(), "未读取到可展示的 Java 启动配置");
  }
  private static DeploymentServer node(Long id, String host) { DeploymentServer node = id(new DeploymentServer(), id); node.setName("节点" + id); node.setHost(host); return node; }
  private static Artifact artifact(Long id, String version, String image) { return id(new Artifact(35L, id, version, "main", "abc123", image), id); }
  private static ReleaseTask release(Long id, Long artifactId, TaskStatus status, List<Long> targets, boolean simulate, Instant at) {
    ReleaseTask task = id(new ReleaseTask(35L, TaskKind.DEPLOY, artifactId, "test", "main", "test only", simulate), id);
    task.setStatus(status); task.setTargetServerIds(targets); task.setStartedAt(LocalDateTime.ofInstant(at.minusSeconds(1), ZoneId.systemDefault()));
    if (status != TaskStatus.RUNNING) task.setFinishedAt(LocalDateTime.ofInstant(at, ZoneId.systemDefault()));
    return task;
  }
  private static <T> T id(T entity, Long id) { ReflectionTestUtils.setField(entity, "id", id); return entity; }
  private static class MutableClock extends Clock {
    private Instant value;
    MutableClock(Instant value) { this.value = value; }
    void advance(Duration elapsed) { value = value.plus(elapsed); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return value; }
  }
  private static class ManualExecutor extends AbstractExecutorService {
    private final Deque<Runnable> jobs = new ArrayDeque<>();
    private boolean stopped;
    int queued() { return jobs.size(); }
    void runAll() { while (!jobs.isEmpty()) jobs.removeFirst().run(); }
    @Override public void execute(Runnable runnable) { if (stopped) throw new RejectedExecutionException(); jobs.addLast(runnable); }
    @Override public void shutdown() { stopped = true; }
    @Override public List<Runnable> shutdownNow() { stopped = true; var remaining = List.copyOf(jobs); jobs.clear(); return remaining; }
    @Override public boolean isShutdown() { return stopped; }
    @Override public boolean isTerminated() { return stopped && jobs.isEmpty(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
  }
}
