package com.local.release.repo;
import com.local.release.domain.BranchCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface BranchCheckRepository extends JpaRepository<BranchCheck,Long> {
  List<BranchCheck> findByTaskIdOrderByIdDesc(Long taskId);
  Optional<BranchCheck> findFirstByTaskIdOrderByIdDesc(Long taskId);
  Optional<BranchCheck> findFirstByTaskIdAndStatusOrderByIdAsc(Long taskId,BranchCheck.Status status);
}
