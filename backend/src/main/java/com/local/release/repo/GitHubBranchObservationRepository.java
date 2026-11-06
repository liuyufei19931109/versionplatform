package com.local.release.repo;
import com.local.release.domain.GitHubBranchObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface GitHubBranchObservationRepository extends JpaRepository<GitHubBranchObservation,Long> {
  Optional<GitHubBranchObservation> findByRepositoryIdentityAndBranch(String identity,String branch);
}
