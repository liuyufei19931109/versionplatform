package com.local.release.repo;
import com.local.release.domain.VersionMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
public interface VersionMemberRepository extends JpaRepository<VersionMember,Long> {
  List<VersionMember> findByVersionIdOrderByIdAsc(Long versionId);
  Optional<VersionMember> findByVersionIdAndServiceId(Long versionId,Long serviceId);
  // Bulk updates intentionally preserve the historical member's updatedAt and all original columns.
  @Modifying @Transactional
  @Query("update VersionMember m set m.codeWatchBaselineSha=:sha, m.codeWatchStartedAt=:startedAt where m.id=:id and m.codeWatchBaselineSha is null")
  int initializeCodeWatchBaseline(@Param("id") Long id,@Param("sha") String sha,@Param("startedAt") java.time.Instant startedAt);
  @Modifying @Transactional
  @Query("update VersionMember m set m.codeWatchBaselineSha=:sha where m.id=:id and m.codeWatchBaselineSha=:expected")
  int advanceCodeWatchBaseline(@Param("id") Long id,@Param("expected") String expected,@Param("sha") String sha);
}
