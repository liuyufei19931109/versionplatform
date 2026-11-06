package com.local.release.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/** One immutable baseline decision per build attempt. Never reuse a passing decision on retry. */
@Entity @Table(name="branch_check", indexes=@Index(name="idx_branch_check_task",columnList="taskId,id"))
public class BranchCheck extends BaseEntity {
  public enum Status { PASSED, BLOCKED, ERROR }
  @Column(nullable=false) private Long taskId;
  @Column(nullable=false) private Long serviceId;
  @Column(length=64) private String baselineSha;
  @Column(nullable=false,length=64) private String buildSha;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status;
  @Column(nullable=false,length=500) private String message;
  @Column(nullable=false) private LocalDateTime checkedAt;
  @JsonIgnore @Lob private String repositoryEncrypted;
  @JsonIgnore @Column(length=64) private String repositoryIdentity;
  protected BranchCheck(){}
  public BranchCheck(Long taskId,Long serviceId,String buildSha){this.taskId=taskId;this.serviceId=serviceId;this.buildSha=buildSha;this.checkedAt=LocalDateTime.now();}
  public void complete(String baselineSha,Status status,String message,String repositoryEncrypted,String repositoryIdentity){this.baselineSha=baselineSha;this.status=status;this.message=message;this.repositoryEncrypted=repositoryEncrypted;this.repositoryIdentity=repositoryIdentity;this.checkedAt=LocalDateTime.now();}
  public Long getTaskId(){return taskId;} public Long getServiceId(){return serviceId;}
  public String getBaselineSha(){return baselineSha;} public String getBuildSha(){return buildSha;}
  public Status getStatus(){return status;} public String getMessage(){return message;}
  public LocalDateTime getCheckedAt(){return checkedAt;}
  public String getRepositoryEncrypted(){return repositoryEncrypted;} public String getRepositoryIdentity(){return repositoryIdentity;}
}
