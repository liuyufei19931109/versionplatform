package com.local.release.repo;

import com.local.release.domain.ModelEnums.StepType;
import com.local.release.domain.ModelEnums.TaskStatus;
import java.time.LocalDateTime;

/** Only public deployment facts; deliberately excludes settings snapshots and logs. */
public record DeploymentEvent(Long taskId, Long artifactId, Long buildTaskId,
    String environment, String targets, String actor, TaskStatus status, StepType step,
    LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt) {}
