package com.local.release.miniprogram;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface MiniProgramJobRepository extends JpaRepository<MiniProgramJob,Long>,JpaSpecificationExecutor<MiniProgramJob> {
  Optional<MiniProgramJob> findByRequestKey(String requestKey);
  List<MiniProgramJob> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId,Pageable page);
  List<MiniProgramJob> findByStatusIn(Collection<MiniProgramJob.Status> statuses);
  List<MiniProgramJob> findTop20ByCreatedByOrderByCreatedAtDescIdDesc(String actor);
  Optional<MiniProgramJob> findFirstByProjectIdAndActionAndStatusAndResolutionIsNullOrderByCreatedAtAscIdAsc(Long projectId,MiniProgramJob.Action action,MiniProgramJob.Status status);
}
