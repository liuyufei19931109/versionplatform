package com.local.release.repo;
import com.local.release.domain.ReleaseTask; import com.local.release.domain.ModelEnums.TaskKind; import com.local.release.domain.ModelEnums.TaskStatus; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List;
public interface ReleaseTaskRepository extends JpaRepository<ReleaseTask,Long>{
  @org.springframework.data.jpa.repository.Query("""
      select new com.local.release.repo.DeploymentEvent(t.id, t.artifactId, a.buildTaskId,
        t.environmentName, t.targetServerIds, t.createdBy, t.status, t.currentStep,
        t.createdAt, t.startedAt, t.finishedAt)
      from ReleaseTask t left join Artifact a on a.id=t.artifactId and a.serviceId=t.serviceId
      where t.serviceId=:serviceId and t.taskKind=:kind and t.simulate=false
        and (t.deliveryType is null or t.deliveryType <> 'MOBILE')
        and t.environmentName in ('dev','test','prod')
      """)
  List<DeploymentEvent> deploymentEvents(Long serviceId, TaskKind kind);
  List<ReleaseTask> findTop50ByCreatedByAndStatusInAndFinishedAtAfterOrderByFinishedAtDescIdDesc(String createdBy,java.util.Collection<TaskStatus> statuses,java.time.LocalDateTime since);
  List<ReleaseTask> findByServiceIdAndTaskKindAndBranchAndCommitIdAndSimulateFalseOrderByIdDesc(Long serviceId,TaskKind kind,String branch,String commitId);
  List<ReleaseTask> findTop50ByMergeEnqueuePendingTrueOrderByIdAsc();
  List<ReleaseTask> findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind kind,String environmentName);
  org.springframework.data.domain.Slice<ReleaseTask> findByTaskKindAndStatusAndSimulateFalseOrderByFinishedAtDescIdDesc(TaskKind kind,TaskStatus status,org.springframework.data.domain.Pageable pageable);
  List<ReleaseTask> findByTaskKindAndStatusIn(TaskKind kind,List<TaskStatus> statuses);
  boolean existsByArtifactIdAndTaskKindAndStatusAndSimulateFalseAndEnvironmentNameIn(Long artifactId,TaskKind kind,TaskStatus status,java.util.Collection<String> environments);
  List<ReleaseTask> findByServiceIdAndTaskKindAndStatusAndSimulateFalseAndEnvironmentNameIn(Long serviceId,TaskKind kind,TaskStatus status,java.util.Collection<String> environments);
  @org.springframework.data.jpa.repository.Query("select t from ReleaseTask t where t.taskKind = :kind and (t.status = :running or t.startedAt >= :since or t.finishedAt >= :since)")
  List<ReleaseTask> findRuntimeActivity(TaskKind kind, TaskStatus running, java.time.LocalDateTime since);
  List<ReleaseTask> findAllByOrderByCreatedAtDesc();
  List<ReleaseTask> findByStatusIn(List<TaskStatus> statuses);
  java.util.Optional<ReleaseTask> findByRequestKey(String requestKey);
  List<ReleaseTask> findByServiceIdOrderByCreatedAtDesc(Long serviceId);
  List<ReleaseTask> findByTaskKindOrderByCreatedAtDesc(TaskKind taskKind);
  List<ReleaseTask> findByServiceIdAndTaskKindOrderByCreatedAtDesc(Long serviceId,TaskKind taskKind);
  @org.springframework.data.jpa.repository.Query("select t from ReleaseTask t where t.serviceId=:serviceId and t.deliveryType='MOBILE' and t.taskKind=:kind and t.simulate=false and t.environmentName in :environments and (:beforeId is null or t.id<:beforeId) and exists (select d.id from MobileDelivery d where d.releaseTaskId=t.id and d.artifactId=t.artifactId) order by t.id desc")
  org.springframework.data.domain.Slice<ReleaseTask> findIntegrationDeliveries(Long serviceId,TaskKind kind,java.util.Collection<String> environments,Long beforeId,org.springframework.data.domain.Pageable pageable);
}
