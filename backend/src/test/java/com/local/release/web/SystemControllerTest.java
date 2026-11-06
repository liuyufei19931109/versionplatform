package com.local.release.web;

import com.local.release.domain.ModelEnums.*;
import com.local.release.domain.ReleaseTask;
import com.local.release.repo.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemControllerTest {
  @Test
  void dashboardUsesCalendarDayAndSeparatesQueuedFromRunning() {
    ReleaseTaskRepository tasks = mock(ReleaseTaskRepository.class);
    AppServiceRepository services = mock(AppServiceRepository.class);
    ArtifactRepository artifacts = mock(ArtifactRepository.class);
    LocalDateTime today = LocalDate.now().atStartOfDay();
    List<ReleaseTask> fixtures = List.of(
      task(TaskKind.DEPLOY, TaskStatus.RUNNING, today),
      task(TaskKind.BUILD, TaskStatus.PENDING, today.plusHours(1)),
      task(TaskKind.DEPLOY, TaskStatus.FAILED, today.minusNanos(1)),
      task(TaskKind.DEPLOY, TaskStatus.SUCCESS, today.plusDays(1)));
    when(tasks.findAllByOrderByCreatedAtDesc()).thenReturn(fixtures);
    Map<String, Object> result = new SystemController(services, tasks, artifacts).dashboard();
    assertThat(result).containsEntry("todayReleaseCount", 1L).containsEntry("todayBuildCount", 1L)
      .containsEntry("runningCount", 1L).containsEntry("queuedCount", 1L)
      .containsEntry("failedCount", 1L).containsEntry("todayFailedCount", 0L);
  }

  private ReleaseTask task(TaskKind kind, TaskStatus status, LocalDateTime created) {
    ReleaseTask task = mock(ReleaseTask.class);
    when(task.getTaskKind()).thenReturn(kind);
    when(task.getStatus()).thenReturn(status);
    when(task.getCreatedAt()).thenReturn(created);
    return task;
  }
}
