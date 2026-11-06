package com.local.release.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

/** One latest, independently verified remote observation per repository/ref; unrelated to branch creation status. */
@Entity @Table(name="github_branch_observation",uniqueConstraints=@UniqueConstraint(name="uk_github_observation",columnNames={"repositoryIdentity","branch"}))
public class GitHubBranchObservation extends BaseEntity {
  @Column(nullable=false,length=64) private String repositoryIdentity;
  @Column(nullable=false,length=120) private String branch;
  @Column(length=64) private String headSha;
  @Column(nullable=false,length=20) private String syncStatus="PENDING";
  @Column(length=400) private String errorMessage;
  @Column(nullable=false) private long requestedRevision=1;
  @Column(nullable=false) private long checkedRevision;
  private Instant checkedAt;
  private Instant headUpdatedAt;
  protected GitHubBranchObservation(){}
  public GitHubBranchObservation(String identity,String branch){this.repositoryIdentity=identity;this.branch=branch;}
  public void request(){requestedRevision++;syncStatus="PENDING";}
  public void verified(String sha,long revision,Instant time){if(!Objects.equals(headSha,sha)||headUpdatedAt==null)headUpdatedAt=time;headSha=sha;checkedRevision=revision;checkedAt=time;syncStatus=sha==null?"DELETED":"SYNCED";errorMessage=null;}
  public void failed(long revision,Instant time,String message){checkedRevision=revision;checkedAt=time;syncStatus="ERROR";errorMessage=message;}
  public String getRepositoryIdentity(){return repositoryIdentity;} public String getBranch(){return branch;} public String getHeadSha(){return headSha;} public String getSyncStatus(){return syncStatus;}
  public String getErrorMessage(){return errorMessage;} public long getRequestedRevision(){return requestedRevision;} public long getCheckedRevision(){return checkedRevision;}
  public Instant getCheckedAt(){return checkedAt;} public Instant getHeadUpdatedAt(){return headUpdatedAt;} public boolean isPending(){return requestedRevision>checkedRevision;}
}
