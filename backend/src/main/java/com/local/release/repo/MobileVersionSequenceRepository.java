package com.local.release.repo;
import com.local.release.domain.MobileVersionSequence;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface MobileVersionSequenceRepository extends JpaRepository<MobileVersionSequence,Long>{
  Optional<MobileVersionSequence> findBySequenceKey(String key);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from MobileVersionSequence s where s.sequenceKey=?1")
  Optional<MobileVersionSequence> lockByKey(String key);
}
