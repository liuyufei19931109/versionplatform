package com.local.release.repo;
import com.local.release.domain.MobileArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface MobileArtifactRepository extends JpaRepository<MobileArtifact,Long>{
  java.util.List<MobileArtifact> findByApplicationId(String applicationId);
  java.util.List<MobileArtifact> findByServiceIdAndBuildEnvironmentName(Long serviceId,String environmentName);
  Optional<MobileArtifact> findByArtifactId(Long artifactId);
  Optional<MobileArtifact> findByBuildTaskId(Long buildTaskId);
  boolean existsByArtifactId(Long artifactId);
}
