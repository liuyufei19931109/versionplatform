package com.local.release.repo;
import com.local.release.domain.DeploymentServer; import org.springframework.data.jpa.repository.JpaRepository;
public interface DeploymentServerRepository extends JpaRepository<DeploymentServer,Long>{}
