package com.local.release.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Independent from release status: a Git push must never turn a successful deployment into a failure. */
@Entity @Table(name="production_merge", uniqueConstraints=@UniqueConstraint(name="uk_production_merge_task",columnNames="releaseTaskId"))
public class ProductionMerge extends BaseEntity {
  public enum Status { QUEUED, RUNNING, MERGED, ALREADY_MERGED, NEEDS_ATTENTION }
  @Column(nullable=false) private Long releaseTaskId;
  @Column(nullable=false) private Long serviceId;
  private Long branchCheckId;
  @Column(length=64) private String commitId;
  @Column(length=64) private String baselineBefore;
  @Column(length=64) private String baselineAfter;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private Status status=Status.QUEUED;
  @Column(nullable=false,length=60) private String reason="QUEUED";
  @Column(nullable=false,length=500) private String message="生产发布成功，等待合回 master";
  @JsonIgnore @Lob private String repositoryEncrypted;
  @JsonIgnore @Column(length=64) private String repositoryIdentity;
  @Column(nullable=false) private int attempts;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  protected ProductionMerge(){}
  public ProductionMerge(Long releaseTaskId,Long serviceId,String commitId){this.releaseTaskId=releaseTaskId;this.serviceId=serviceId;this.commitId=commitId;}
  public void proof(BranchCheck check){branchCheckId=check.getId();repositoryEncrypted=check.getRepositoryEncrypted();repositoryIdentity=check.getRepositoryIdentity();}
  public void update(Status status,String reason,String message){this.status=status;this.reason=reason;this.message=message;if(status!=Status.RUNNING&&status!=Status.QUEUED)finishedAt=LocalDateTime.now();}
  public void start(){attempts++;startedAt=LocalDateTime.now();finishedAt=null;update(Status.RUNNING,"RUNNING","正在核对远程 master 并合回已发布的 Commit");}
  public void baselines(String before,String after){baselineBefore=before;baselineAfter=after;}
  public Long getReleaseTaskId(){return releaseTaskId;} public Long getServiceId(){return serviceId;} public Long getBranchCheckId(){return branchCheckId;}
  public String getCommitId(){return commitId;} public String getBaselineBefore(){return baselineBefore;} public String getBaselineAfter(){return baselineAfter;}
  public Status getStatus(){return status;} public String getReason(){return reason;} public String getMessage(){return message;}
  public String getRepositoryEncrypted(){return repositoryEncrypted;} public String getRepositoryIdentity(){return repositoryIdentity;}
  public int getAttempts(){return attempts;} public LocalDateTime getStartedAt(){return startedAt;} public LocalDateTime getFinishedAt(){return finishedAt;}
}
