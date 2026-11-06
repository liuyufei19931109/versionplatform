package com.local.release.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.web.ReleaseHistoryView;
import com.local.release.web.ReleaseHistoryView.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReleaseHistoryServiceTest {
  private final ReleaseTaskRepository tasks = mock(ReleaseTaskRepository.class);
  private final AppServiceRepository services = mock(AppServiceRepository.class);
  private final ServiceEnvironmentRepository environments = mock(ServiceEnvironmentRepository.class);
  private final ArtifactRepository artifacts = mock(ArtifactRepository.class);
  private final ReleaseHistoryService history = new ReleaseHistoryService(tasks, services, environments, artifacts);
  private final LocalDateTime morning = LocalDateTime.of(2026, 9, 14, 9, 0);

  @BeforeEach void emptyRepositories() {
    when(tasks.findByTaskKindAndEnvironmentNameAndSimulateFalse(any(), any())).thenReturn(List.of());
    when(services.findAll()).thenReturn(List.of());
    when(services.findAllById(any())).thenReturn(List.of());
    when(environments.findHistoryConfigurations(any())).thenReturn(List.of());
    when(artifacts.findAllById(any())).thenReturn(List.of());
  }

  @Test void isolatesSelectedEnvironmentAndRealDeploymentsWithoutHidingFailuresOrCancellation() {
    List<ReleaseTask> supplied = List.of(task(1, 35, 10L, "test", TaskStatus.SUCCESS, false),
      task(2, 35, 10L, "prod", TaskStatus.SUCCESS, false), task(3, 35, 10L, "test", TaskStatus.FAILED, false),
      task(4, 35, 10L, "test", TaskStatus.SUCCESS, true), task(5, 35, 10L, "test", TaskStatus.CANCELLED, false));
    ReleaseTask build = new ReleaseTask(35L, TaskKind.BUILD, 10L, "test", "main", "", false);
    List<ReleaseTask> withBuild = new ArrayList<>(supplied); withBuild.add(build);
    when(tasks.findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind.DEPLOY, "test")).thenReturn(withBuild);

    ReleaseHistoryView result = history.history(null);
    assertThat(result.environment()).isEqualTo("test");
    assertThat(result.records()).extracting(entry -> entry.task().id()).containsExactly(5L, 3L, 1L);
    assertThat(result.services().getFirst().latestSuccessful().task().id()).isEqualTo(1L);
    assertThat(result.services().getFirst().latestAttempt().task().id()).isEqualTo(5L);
    verify(tasks).findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind.DEPLOY, "test");
    verify(artifacts).findAllById(Set.of(10L));
    verifyNoMoreInteractions(tasks, artifacts);
  }

  @Test void returningToAnOldArtifactAndRetryingAnOldTaskUseExecutionTime() {
    currentServices(service(35, "前端", true));
    Artifact older = artifact(10, 35, 40, "v1"), newer = artifact(20, 35, 50, "v2");
    when(artifacts.findAllById(any())).thenReturn(List.of(older, newer));
    ReleaseTask earlierIdRetriedLater = task(60, 35, 10L, "test", TaskStatus.SUCCESS, false);
    earlierIdRetriedLater.setFinishedAt(morning.plusHours(3));
    ReleaseTask newerTask = task(70, 35, 20L, "test", TaskStatus.SUCCESS, false);
    newerTask.setFinishedAt(morning.plusHours(1));
    ReleaseTask newerFailure = task(80, 35, 20L, "test", TaskStatus.FAILED, false);
    newerFailure.setFinishedAt(morning.plusHours(4));
    when(tasks.findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind.DEPLOY, "test"))
      .thenReturn(List.of(newerTask, earlierIdRetriedLater, newerFailure));

    ReleaseHistoryView result = history.history("test");
    assertThat(result.records()).extracting(entry -> entry.task().id()).containsExactly(80L, 60L, 70L);
    ServiceSummary summary = result.services().getFirst();
    assertThat(summary.latestSuccessful().task().id()).isEqualTo(60L);
    assertThat(summary.latestSuccessful().artifactVersion()).isEqualTo("v1");
    assertThat(summary.latestSuccessful().buildTaskId()).isEqualTo(40L);
    assertThat(summary.latestAttempt().task().id()).isEqualTo(80L);
  }

  @Test void aQueuedRetryUsesUpdatedTimeAndDoesNotReplaceLastSuccess() {
    ReleaseTask retry = task(1, 35, 10L, "test", TaskStatus.PENDING, false);
    retry.setStartedAt(null); retry.setFinishedAt(null);
    ReflectionTestUtils.setField(retry, "updatedAt", morning.plusDays(1));
    ReleaseTask success = task(90, 35, 10L, "test", TaskStatus.SUCCESS, false);
    when(tasks.findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind.DEPLOY, "test")).thenReturn(List.of(success, retry));
    ReleaseHistoryView result = history.history("test");
    assertThat(result.records().getFirst().task().id()).isEqualTo(1L);
    assertThat(result.services().getFirst().latestAttempt().task().id()).isEqualTo(1L);
    assertThat(result.services().getFirst().latestSuccessful().task().id()).isEqualTo(90L);
  }

  @Test void aLegacySuccessWithoutCompletionDoesNotUseUnrelatedLaterUpdateAsSuccessfulReleaseTime() {
    ReleaseTask legacy = task(1, 35, 10L, "test", TaskStatus.SUCCESS, false);
    legacy.setFinishedAt(null);
    ReflectionTestUtils.setField(legacy, "updatedAt", morning.plusDays(1));
    ReleaseTask recent = task(2, 35, 10L, "test", TaskStatus.SUCCESS, false);
    when(tasks.findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind.DEPLOY, "test")).thenReturn(List.of(legacy, recent));
    assertThat(history.history("test").services().getFirst().latestSuccessful().task().id()).isEqualTo(2L);
  }

  @Test void distinguishesExplicitPartialTargetsFromUnknownLegacyScopeAndMalformedScope() {
    currentServices(service(35, "前端", true));
    when(environments.findHistoryConfigurations("test")).thenReturn(List.of(configuration(35, "test", true, 1L, 2L, 3L)));
    ReleaseTask partial = task(1, 35, 10L, "test", TaskStatus.SUCCESS, false);
    partial.setTargetServerIds(List.of(1L, 1L, 2L));
    ReleaseTask legacy = task(2, 35, 10L, "test", TaskStatus.SUCCESS, false);
    ReleaseTask malformed = task(3, 35, 10L, "test", TaskStatus.FAILED, false);
    ReflectionTestUtils.setField(malformed, "targetServerIds", "1,invalid");
    when(tasks.findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind.DEPLOY, "test")).thenReturn(List.of(partial, legacy, malformed));
    ReleaseHistoryView result = history.history("test");
    assertThat(result.services().getFirst().nodeCount()).isEqualTo(3);
    assertThat(result.services().getFirst().configured()).isTrue();
    assertThat(result.records().get(2).targetScopeKnown()).isTrue();
    assertThat(result.records().get(2).targetNodeCount()).isEqualTo(2);
    assertThat(result.records().get(1).targetScopeKnown()).isFalse();
    assertThat(result.records().get(1).targetNodeCount()).isZero();
    assertThat(result.records().getFirst().targetScopeKnown()).isFalse();
  }

  @Test void includesUnconfiguredDisabledAndDeletedServicesWithTheirHonestState() {
    currentServices(service(1, "已配置", true), service(2, "未配置", true), service(3, "停用服务", false), service(4, "停用环境", true));
    when(environments.findHistoryConfigurations("test")).thenReturn(List.of(configuration(1, "test", true, 3L, 3L),
      configuration(3, "test", true, 3L), configuration(4, "test", false, 3L)));
    when(tasks.findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind.DEPLOY, "test"))
      .thenReturn(List.of(task(1, 99, null, "test", TaskStatus.FAILED, false)));
    List<ServiceSummary> result = history.history("test").services();
    assertThat(result).extracting(ServiceSummary::serviceId).containsExactly(1L, 2L, 3L, 4L, 99L);
    assertThat(result).extracting(ServiceSummary::configured).containsExactly(true, false, true, false, false);
    assertThat(result).extracting(ServiceSummary::enabled).containsExactly(true, true, false, true, false);
    assertThat(result.getFirst().nodeCount()).isEqualTo(1);
    assertThat(result.get(4).serviceName()).isEqualTo("已删除服务 #99");
    assertThat(result.get(4).latestAttempt().artifactVersion()).isNull();
  }

  @Test void missingOrMismatchedArtifactDoesNotHideHistoryOrBorrowAnotherServiceVersion() {
    when(artifacts.findAllById(any())).thenReturn(List.of(artifact(10, 99, 40, "private-other-service-version")));
    when(tasks.findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind.DEPLOY, "test"))
      .thenReturn(List.of(task(1, 35, 10L, "test", TaskStatus.SUCCESS, false), task(2, 35, 20L, "test", TaskStatus.SUCCESS, false)));
    assertThat(history.history("test").records()).hasSize(2).allSatisfy(entry -> {
      assertThat(entry.artifactVersion()).isNull(); assertThat(entry.buildTaskId()).isNull(); assertThat(entry.imageReference()).isNull();
    });
  }

  @Test void serializationOnlyIncludesApprovedFieldsAndReadsRepositoriesInBatches() throws Exception {
    AppService service = service(35, "前端", true);
    service.setGitRepo("git-secret"); service.setConfigJson("{\"token\":\"config-secret\",\"displayIcon\":\"website\"}"); service.setEnvJson("service-env-secret");
    ServiceEnvironment config = configuration(35, "test", true, 3L);
    config.setEnvJson("environment-secret"); config.setStartCommand("command-secret"); config.setEnvFilePath("path-secret");
    currentServices(service);
    when(environments.findHistoryConfigurations("test")).thenReturn(List.of(config));
    Artifact artifact = artifact(10, 35, 40, "v1"); artifact.setJarPath("jar-secret");
    when(artifacts.findAllById(any())).thenReturn(List.of(artifact));
    ReleaseTask task = task(1, 35, 10L, "test", TaskStatus.SUCCESS, false);
    task.setRequestKey("request-secret"); task.setLastRetryRequestKey("retry-secret");
    when(tasks.findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind.DEPLOY, "test")).thenReturn(List.of(task));
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    String json = mapper.writeValueAsString(history.history("test"));
    assertThat(json).doesNotContain("git-secret", "config-secret", "service-env-secret", "environment-secret", "command-secret", "path-secret", "jar-secret", "request-secret", "retry-secret");
    JsonNode root = mapper.readTree(json);
    assertThat(root.path("services").get(0).path("displayIcon").asText()).isEqualTo("website");
    assertThat(fieldNames(root)).containsExactlyInAnyOrder("environment", "services", "records");
    assertThat(fieldNames(root.get("services").get(0))).containsExactlyInAnyOrder("serviceId", "serviceName", "serviceKey", "serviceType", "configured", "enabled", "nodeCount", "latestSuccessful", "latestAttempt", "displayIcon");
    assertThat(fieldNames(root.get("records").get(0))).containsExactlyInAnyOrder("task", "artifactVersion", "buildTaskId", "imageReference", "targetNodeCount", "targetScopeKnown");
    verify(tasks).findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind.DEPLOY, "test");
    verify(services).findAll(); verify(services).findAllById(Set.of(35L));
    verify(environments).findHistoryConfigurations("test"); verify(artifacts).findAllById(Set.of(10L));
    verifyNoMoreInteractions(tasks, services, environments, artifacts);
  }

  @Test void invalidEnvironmentFailsBeforeAnyDataRead() {
    assertThatThrownBy(() -> history.history("staging")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("环境");
    assertThatThrownBy(() -> history.history("")).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(tasks, services, environments, artifacts);
  }

  private static Set<String> fieldNames(JsonNode node) { Set<String> names = new HashSet<>(); node.fieldNames().forEachRemaining(names::add); return names; }
  private void currentServices(AppService... values) { when(services.findAll()).thenReturn(List.of(values)); when(services.findAllById(any())).thenReturn(List.of(values)); }
  private AppService service(long id, String name, boolean enabled) {
    AppService result = new AppService(); ReflectionTestUtils.setField(result, "id", id);
    result.setName(name); result.setServiceKey("service-" + id); result.setServiceType(ServiceType.FRONTEND); result.setEnabled(enabled); return result;
  }
  private ServiceEnvironment configuration(long serviceId, String environment, boolean enabled, Long... ids) {
    ServiceEnvironment result = new ServiceEnvironment(); result.setServiceId(serviceId); result.setEnvironmentName(environment); result.setEnabled(enabled); result.setServerIds(List.of(ids)); return result;
  }
  private Artifact artifact(long id, long serviceId, long buildId, String version) {
    Artifact result = new Artifact(serviceId, buildId, version, "main", "commit", "registry.example/team/ui:" + version);
    ReflectionTestUtils.setField(result, "id", id); return result;
  }
  private ReleaseTask task(long id, long serviceId, Long artifactId, String environment, TaskStatus status, boolean simulate) {
    ReleaseTask result = new ReleaseTask(serviceId, TaskKind.DEPLOY, artifactId, environment, "main", "", simulate);
    ReflectionTestUtils.setField(result, "id", id); ReflectionTestUtils.setField(result, "createdAt", morning.minusDays(1).plusMinutes(id));
    ReflectionTestUtils.setField(result, "updatedAt", morning.plusMinutes(id)); result.setStatus(status);
    result.setStartedAt(morning.plusMinutes(id).minusSeconds(5));
    if (status != TaskStatus.PENDING && status != TaskStatus.RUNNING) result.setFinishedAt(morning.plusMinutes(id));
    return result;
  }
}
