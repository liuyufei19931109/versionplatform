package com.local.release.repo;

import com.local.release.domain.VersionCompletionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VersionCompletionEventRepository extends JpaRepository<VersionCompletionEvent,Long> {
  List<VersionCompletionEvent> findByVersionIdOrderByIdDesc(Long versionId);
  List<VersionCompletionEvent> findAllByOrderByIdDesc();
}
