package com.local.release.repo;

import com.local.release.domain.WecomNotification;
import com.local.release.domain.WecomNotification.Status;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.*;

public interface WecomNotificationRepository extends JpaRepository<WecomNotification,Long> {
  boolean existsByDedupKey(String key);
  List<WecomNotification> findByTaskIdOrderByIdDesc(Long taskId);
  List<WecomNotification> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderById(Status status,LocalDateTime now);
  List<WecomNotification> findByStatus(Status status);
  Optional<WecomNotification> findFirstByTargetHashAndLastAttemptAtNotNullOrderByLastAttemptAtDesc(String targetHash);
  long countByTargetHashAndLastAttemptAtAfter(String targetHash,LocalDateTime after);
  @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select n from WecomNotification n where n.id=:id")
  Optional<WecomNotification> lockById(@Param("id") Long id);
}
