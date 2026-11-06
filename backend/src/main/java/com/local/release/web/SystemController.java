package com.local.release.web;

import com.local.release.domain.ReleaseTask;
import com.local.release.domain.ModelEnums.TaskKind;
import com.local.release.domain.ModelEnums.TaskStatus;
import com.local.release.repo.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class SystemController {
  private final AppServiceRepository services;
  private final ReleaseTaskRepository releases;
  private final ArtifactRepository artifacts;
  public SystemController(AppServiceRepository services, ReleaseTaskRepository releases, ArtifactRepository artifacts) {
    this.services = services; this.releases = releases; this.artifacts = artifacts;
  }

  @GetMapping("/health")
  public Map<String, Object> health() { return Map.of("status", "UP", "version", "0.1.0-preview.1", "time", LocalDateTime.now()); }

  @GetMapping("/dashboard")
  public Map<String, Object> dashboard() {
    List<ReleaseTask> all = releases.findAllByOrderByCreatedAtDesc();
    List<ReleaseTask> deploys = all.stream().filter(task -> task.getTaskKind() == TaskKind.DEPLOY).toList();
    LocalDateTime today = LocalDate.now().atStartOfDay(), tomorrow = today.plusDays(1);
    List<ReleaseTask> todays = all.stream().filter(task -> task.getCreatedAt() != null
      && !task.getCreatedAt().isBefore(today) && task.getCreatedAt().isBefore(tomorrow)).toList();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("serviceCount", services.count());
    result.put("releaseCount", deploys.size());
    result.put("buildCount", all.stream().filter(task -> task.getTaskKind() == TaskKind.BUILD).count());
    result.put("todayReleaseCount", todays.stream().filter(task -> task.getTaskKind() == TaskKind.DEPLOY).count());
    result.put("todayBuildCount", todays.stream().filter(task -> task.getTaskKind() == TaskKind.BUILD).count());
    result.put("runningCount", all.stream().filter(task -> task.getStatus() == TaskStatus.RUNNING).count());
    result.put("queuedCount", all.stream().filter(task -> task.getStatus() == TaskStatus.PENDING).count());
    result.put("failedCount", all.stream().filter(task -> task.getStatus() == TaskStatus.FAILED).count());
    result.put("todayFailedCount", todays.stream().filter(task -> task.getStatus() == TaskStatus.FAILED).count());
    result.put("artifactCount", artifacts.count());
    result.put("recentReleases", ReleaseTaskView.list(deploys.stream().limit(8).toList(), services));
    return result;
  }
}
