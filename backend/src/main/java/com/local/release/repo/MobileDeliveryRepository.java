package com.local.release.repo;

import com.local.release.domain.MobileDelivery;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface MobileDeliveryRepository extends JpaRepository<MobileDelivery,Long> {
  Optional<MobileDelivery> findByReleaseTaskId(Long id);
  boolean existsByArtifactId(Long id);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from MobileDelivery d where d.releaseTaskId=:id")
  Optional<MobileDelivery> locked(@Param("id") Long id);
}
