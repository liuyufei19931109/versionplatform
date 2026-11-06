package com.local.release.repo;

import com.local.release.domain.RegistryCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RegistryCredentialRepository extends JpaRepository<RegistryCredential,Long> {
  Optional<RegistryCredential> findByRegistryServer(String registryServer);
}
