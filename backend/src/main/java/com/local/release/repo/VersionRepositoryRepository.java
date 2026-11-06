package com.local.release.repo;
import com.local.release.domain.VersionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface VersionRepositoryRepository extends JpaRepository<VersionRepository,Long> {
  Optional<VersionRepository> findByVersionIdAndRepositoryIdentity(Long versionId,String identity);
  List<VersionRepository> findByVersionId(Long versionId);
  List<VersionRepository> findByStatusIn(Collection<String> statuses);
}
