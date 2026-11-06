package com.local.release.repo;
import com.local.release.domain.BuildAutoPublishPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface BuildAutoPublishPlanRepository extends JpaRepository<BuildAutoPublishPlan,Long> {
  Optional<BuildAutoPublishPlan> findByBuildTaskId(Long id);
  Optional<BuildAutoPublishPlan> findByDeploymentTaskId(Long id);
  List<BuildAutoPublishPlan> findByStatusInOrderByIdAsc(Collection<String> statuses);
}
