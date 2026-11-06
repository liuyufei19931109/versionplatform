package com.local.release.repo;
import com.local.release.domain.Artifact; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List; import java.util.Optional;
public interface ArtifactRepository extends JpaRepository<Artifact,Long>{ List<Artifact> findByServiceIdOrderByCreatedAtDesc(Long serviceId); boolean existsByBuildTaskId(Long buildTaskId); Optional<Artifact> findByBuildTaskId(Long buildTaskId); }
