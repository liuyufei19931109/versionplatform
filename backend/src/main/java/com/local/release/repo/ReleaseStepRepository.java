package com.local.release.repo;
import com.local.release.domain.ReleaseStep; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List;
public interface ReleaseStepRepository extends JpaRepository<ReleaseStep,Long>{ List<ReleaseStep> findByReleaseTaskIdOrderById(Long taskId); }
