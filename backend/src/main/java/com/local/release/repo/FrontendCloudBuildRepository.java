package com.local.release.repo;
import com.local.release.domain.FrontendCloudBuild;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface FrontendCloudBuildRepository extends JpaRepository<FrontendCloudBuild,Long> {
  List<FrontendCloudBuild> findTop20ByStateOrderByDispatchedAtAsc(String state);
}
