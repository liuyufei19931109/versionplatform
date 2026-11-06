package com.local.release.repo;
import com.local.release.domain.VersionBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDate;
import java.util.*;
public interface VersionBatchRepository extends JpaRepository<VersionBatch,Long> {
  Optional<VersionBatch> findByVersionDate(LocalDate date);
  List<VersionBatch> findAllByOrderByVersionDateDesc();
  Page<VersionBatch> findAllByOrderByVersionDateDesc(Pageable pageable);
  Optional<VersionBatch> findFirstByOrderByVersionDateDesc();
}
