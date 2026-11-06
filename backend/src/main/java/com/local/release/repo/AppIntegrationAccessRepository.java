package com.local.release.repo;

import com.local.release.domain.AppIntegrationAccess;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface AppIntegrationAccessRepository extends JpaRepository<AppIntegrationAccess,Long> {
  Optional<AppIntegrationAccess> findByServiceId(Long serviceId);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from AppIntegrationAccess a where a.serviceId=:serviceId")
  Optional<AppIntegrationAccess> locked(@Param("serviceId") Long serviceId);
}
