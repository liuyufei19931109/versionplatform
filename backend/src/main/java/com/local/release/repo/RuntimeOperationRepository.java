package com.local.release.repo;

import com.local.release.domain.RuntimeOperation;
import com.local.release.domain.ModelEnums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RuntimeOperationRepository extends JpaRepository<RuntimeOperation,Long> {
  List<RuntimeOperation> findTop50ByCreatedByAndStatusInAndFinishedAtAfterOrderByFinishedAtDescIdDesc(String createdBy,Collection<TaskStatus> statuses,java.time.LocalDateTime since);
  Optional<RuntimeOperation> findByRequestKey(String key);
  List<RuntimeOperation> findTop50ByEnvironmentIdAndServerIdOrderByIdDesc(Long environmentId,Long serverId);
  List<RuntimeOperation> findByStatusIn(Collection<TaskStatus> states);
}
