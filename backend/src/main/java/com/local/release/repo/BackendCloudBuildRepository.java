package com.local.release.repo;
import com.local.release.domain.BackendCloudBuild;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface BackendCloudBuildRepository extends JpaRepository<BackendCloudBuild,Long> {
  List<BackendCloudBuild> findTop20ByStateOrderByDispatchedAtAsc(String state);
}
