package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.*;
import com.local.release.web.ReleaseHistoryView;
import com.local.release.web.ReleaseHistoryView.*;
import com.local.release.web.ReleaseTaskView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReleaseHistoryService {
  private final ReleaseTaskRepository tasks;
  private final AppServiceRepository services;
  private final ServiceEnvironmentRepository environments;
  private final ArtifactRepository artifacts;

  public ReleaseHistoryService(ReleaseTaskRepository tasks, AppServiceRepository services,
      ServiceEnvironmentRepository environments, ArtifactRepository artifacts) {
    this.tasks = tasks; this.services = services; this.environments = environments; this.artifacts = artifacts;
  }

  @Transactional(readOnly = true)
  public ReleaseHistoryView history(String requestedEnvironment) {
    String environment = normalizeEnvironment(requestedEnvironment);
    List<ReleaseTask> selected = tasks.findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind.DEPLOY, environment);
    // Keep defensive isolation at the projection boundary as well as in the repository query.
    selected = selected.stream().filter(task -> task.getTaskKind() == TaskKind.DEPLOY && !task.isSimulate()
      && environment.equals(task.getEnvironmentName())).toList();
    List<AppService> currentServices = services.findAll();
    Map<Long, AppService> serviceMap = currentServices.stream().collect(Collectors.toMap(AppService::getId, Function.identity()));
    Map<Long, ServiceEnvironment> configurationMap = environments.findHistoryConfigurations(environment).stream()
      .filter(config -> environment.equals(config.getEnvironmentName()))
      .collect(Collectors.toMap(ServiceEnvironment::getServiceId, Function.identity()));
    Set<Long> artifactIds = selected.stream().map(ReleaseTask::getArtifactId).filter(Objects::nonNull).collect(Collectors.toSet());
    Map<Long, Artifact> artifactMap = new HashMap<>();
    if (!artifactIds.isEmpty()) artifacts.findAllById(artifactIds).forEach(artifact -> artifactMap.put(artifact.getId(), artifact));

    List<HistoryEntry> records = ReleaseTaskView.list(selected, services).stream().map(task -> {
      Artifact artifact = artifactMap.get(task.artifactId());
      // A broken historic association must not borrow another service's version metadata.
      if (artifact != null && !Objects.equals(task.serviceId(), artifact.getServiceId())) artifact = null;
      TargetScope scope = targetScope(task.targetServerIds());
      return new HistoryEntry(task, artifact == null ? null : artifact.getVersion(),
        artifact == null ? null : artifact.getBuildTaskId(), artifact == null ? null : artifact.getImageUrl(),
        scope.count(), scope.known());
    }).sorted(Comparator.comparing((HistoryEntry entry) -> activityTime(entry.task()))
      .thenComparing(entry -> entry.task().id(), Comparator.nullsFirst(Long::compareTo)).reversed()).toList();

    Map<Long, HistoryEntry> latestAttempts = new HashMap<>();
    Map<Long, HistoryEntry> latestSuccesses = new HashMap<>();
    for (HistoryEntry entry : records) {
      latestAttempts.putIfAbsent(entry.task().serviceId(), entry);
      if (entry.task().status() == TaskStatus.SUCCESS) latestSuccesses.merge(entry.task().serviceId(), entry,
        (previous, candidate) -> successOrder(candidate, previous) > 0 ? candidate : previous);
    }
    Set<Long> serviceIds = new LinkedHashSet<>(serviceMap.keySet());
    records.forEach(entry -> serviceIds.add(entry.task().serviceId()));
    List<ServiceSummary> summaries = serviceIds.stream().sorted(Comparator.nullsLast(Long::compareTo)).map(id -> {
      AppService service = serviceMap.get(id);
      ServiceEnvironment config = configurationMap.get(id);
      int nodeCount = config == null ? 0 : (int) config.getServerIds().stream().filter(Objects::nonNull).distinct().count();
      String name = service == null ? "已删除服务 #" + id : service.getName();
      if (name == null || name.isBlank()) name = "未命名服务 #" + id;
      return new ServiceSummary(id, name, service == null ? null : service.getServiceKey(),
        service == null ? null : service.getServiceType(), service != null && (service.getServiceType()==ServiceType.FLUTTER_ANDROID || config != null && config.isEnabled() && nodeCount > 0),
        service != null && service.isEnabled(), nodeCount, latestSuccesses.get(id), latestAttempts.get(id),service==null?null:service.getDisplayIcon());
    }).toList();
    return new ReleaseHistoryView(environment, summaries, records);
  }

  private static String normalizeEnvironment(String value) {
    String environment = value == null ? "test" : value.trim().toLowerCase(Locale.ROOT);
    if (!Set.of("dev", "test", "prod").contains(environment)) throw new IllegalArgumentException("请选择开发、预生产或生产环境");
    return environment;
  }

  private static LocalDateTime activityTime(ReleaseTaskView task) {
    if (task.finishedAt() != null && task.status() != TaskStatus.PENDING && task.status() != TaskStatus.RUNNING)
      return task.finishedAt();
    return maxTime(task.createdAt(), task.startedAt(), task.updatedAt());
  }

  private static int successOrder(HistoryEntry left, HistoryEntry right) {
    int comparison = successTime(left.task()).compareTo(successTime(right.task()));
    return comparison != 0 ? comparison : Comparator.nullsFirst(Long::compareTo).compare(left.task().id(), right.task().id());
  }

  private static LocalDateTime successTime(ReleaseTaskView task) {
    // A later generic update is not proof of a later successful deployment.
    return task.finishedAt() == null ? maxTime(task.startedAt(), task.createdAt()) : task.finishedAt();
  }

  private static LocalDateTime maxTime(LocalDateTime... values) {
    return Arrays.stream(values).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(LocalDateTime.MIN);
  }

  private record TargetScope(int count, boolean known) {}
  private static TargetScope targetScope(String value) {
    if (value == null || value.isBlank()) return new TargetScope(0, false);
    try {
      Set<Long> ids = new HashSet<>();
      for (String token : value.split(",", -1)) {
        long id = Long.parseLong(token.trim());
        if (id <= 0) return new TargetScope(0, false);
        ids.add(id);
      }
      return new TargetScope(ids.size(), !ids.isEmpty());
    } catch (NumberFormatException invalidHistoricValue) {
      return new TargetScope(0, false);
    }
  }
}
