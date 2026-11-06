package com.local.release.repo;
import com.local.release.domain.MobileVersionAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface MobileVersionAllocationRepository extends JpaRepository<MobileVersionAllocation,Long>{
  Optional<MobileVersionAllocation> findByBuildTaskId(Long id);
  List<MobileVersionAllocation> findByApplicationId(String id);
  List<MobileVersionAllocation> findByServiceIdAndEnvironmentName(Long id,String env);
}
