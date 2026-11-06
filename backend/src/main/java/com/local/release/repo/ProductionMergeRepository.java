package com.local.release.repo;
import com.local.release.domain.ProductionMerge;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ProductionMergeRepository extends JpaRepository<ProductionMerge,Long> {
  @org.springframework.data.jpa.repository.Query("select m from ProductionMerge m where m.repositoryIdentity = ?1 and m.baselineBefore = ?2 and m.baselineAfter = ?3")
  List<ProductionMerge> findByRepositoryIdentityAndBaselineBeforeAndBaselineAfter(String identity,String before,String after);
  Optional<ProductionMerge> findByReleaseTaskId(Long taskId);
  Optional<ProductionMerge> findFirstByStatusOrderByIdAsc(ProductionMerge.Status status);
  List<ProductionMerge> findByStatus(ProductionMerge.Status status);
}
