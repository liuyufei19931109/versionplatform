package com.local.release.repo;
import com.local.release.domain.MobileCloudBuild;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface MobileCloudBuildRepository extends JpaRepository<MobileCloudBuild,Long> {
  List<MobileCloudBuild> findTop20ByStateOrderByDispatchedAtAsc(String state);
}
