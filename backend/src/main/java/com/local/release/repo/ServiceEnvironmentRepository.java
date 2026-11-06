package com.local.release.repo;

import com.local.release.domain.ServiceEnvironment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ServiceEnvironmentRepository extends JpaRepository<ServiceEnvironment,Long> {
  @org.springframework.data.jpa.repository.Query("select distinct e from ServiceEnvironment e left join fetch e.serverIds where e.environmentName = :environmentName")
  List<ServiceEnvironment> findHistoryConfigurations(String environmentName);
  List<ServiceEnvironment> findByServiceIdOrderByEnvironmentName(Long serviceId);
  Optional<ServiceEnvironment> findByServiceIdAndEnvironmentName(Long serviceId,String environmentName);
}
