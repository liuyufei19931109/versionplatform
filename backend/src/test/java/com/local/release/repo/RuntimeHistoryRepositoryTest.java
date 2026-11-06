package com.local.release.repo;

import com.local.release.domain.ReleaseTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import java.time.LocalDateTime;
import java.util.List;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class RuntimeHistoryRepositoryTest {
  @Autowired ReleaseTaskRepository tasks;

  @Test void successPaginationUsesCompletionTimeAndFiltersBuildSimulationAndFailure() {
    LocalDateTime now = LocalDateTime.of(2026, 9, 6, 14, 0);
    ReleaseTask olderIdRetriedLater = save(TaskKind.DEPLOY, TaskStatus.SUCCESS, false, now.plusMinutes(1));
    ReleaseTask next = save(TaskKind.DEPLOY, TaskStatus.SUCCESS, false, now);
    save(TaskKind.DEPLOY, TaskStatus.SUCCESS, true, now.plusMinutes(3));
    save(TaskKind.BUILD, TaskStatus.SUCCESS, false, now.plusMinutes(3));
    save(TaskKind.DEPLOY, TaskStatus.FAILED, false, now.plusMinutes(3));
    var first = tasks.findByTaskKindAndStatusAndSimulateFalseOrderByFinishedAtDescIdDesc(TaskKind.DEPLOY, TaskStatus.SUCCESS, PageRequest.of(0, 1));
    assertThat(first.getContent()).extracting(ReleaseTask::getId).containsExactly(olderIdRetriedLater.getId());
    assertThat(first.hasNext()).isTrue();
    var second = tasks.findByTaskKindAndStatusAndSimulateFalseOrderByFinishedAtDescIdDesc(TaskKind.DEPLOY, TaskStatus.SUCCESS, first.nextPageable());
    assertThat(second.getContent()).extracting(ReleaseTask::getId).containsExactly(next.getId());
    assertThat(second.hasNext()).isFalse();
  }

  @Test void activityQueryIncludesOldRunningAndRecentAttemptButExcludesOldFinishedAndBuilds() {
    LocalDateTime now = LocalDateTime.of(2026, 9, 6, 14, 0);
    ReleaseTask running = save(TaskKind.DEPLOY, TaskStatus.RUNNING, false, null);
    running.setStartedAt(now.minusDays(1)); tasks.saveAndFlush(running);
    ReleaseTask recentFailed = save(TaskKind.DEPLOY, TaskStatus.FAILED, false, now.minusMinutes(1));
    ReleaseTask recentSuccess = save(TaskKind.DEPLOY, TaskStatus.SUCCESS, false, now.minusMinutes(2));
    save(TaskKind.DEPLOY, TaskStatus.SUCCESS, false, now.minusDays(1));
    save(TaskKind.BUILD, TaskStatus.RUNNING, false, null);
    assertThat(tasks.findRuntimeActivity(TaskKind.DEPLOY, TaskStatus.RUNNING, now.minusMinutes(5)))
      .extracting(ReleaseTask::getId).containsExactlyInAnyOrder(running.getId(), recentFailed.getId(), recentSuccess.getId());
    assertThat(tasks.findByTaskKindAndStatusIn(TaskKind.DEPLOY, List.of(TaskStatus.RUNNING)))
      .extracting(ReleaseTask::getId).containsExactly(running.getId());
  }

  private ReleaseTask save(TaskKind kind, TaskStatus status, boolean simulate, LocalDateTime finished) {
    ReleaseTask task = new ReleaseTask(35L, kind, 10L, "test", "main", "in-memory test only", simulate);
    task.setStatus(status); task.setTargetServerIds(List.of(3L)); task.setFinishedAt(finished);
    if (finished != null) task.setStartedAt(finished.minusSeconds(5));
    return tasks.saveAndFlush(task);
  }
}