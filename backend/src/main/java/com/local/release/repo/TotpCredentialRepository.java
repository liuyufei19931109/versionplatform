package com.local.release.repo;

import com.local.release.domain.TotpCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface TotpCredentialRepository extends JpaRepository<TotpCredential, Long> {
  Optional<TotpCredential> findByUsername(String username);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select credential from TotpCredential credential where credential.username = :username")
  Optional<TotpCredential> findByUsernameForUpdate(@Param("username") String username);
}
