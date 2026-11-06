package com.local.release.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Verified push metadata only: never persist webhook bodies, clone credentials or signatures. */
@Entity @Table(name="github_push_event",uniqueConstraints=@UniqueConstraint(name="uk_github_push_delivery",columnNames="deliveryId"),indexes={
  @Index(name="ix_github_push_feed",columnList="repositoryFullName,receivedAt,id"),
  @Index(name="ix_github_push_merge",columnList="repositoryIdentity,beforeSha,commitId")})
public class GitHubPushEvent extends BaseEntity {
  @Column(nullable=false,length=80) private String deliveryId;
  @Column(nullable=false,length=220) private String repositoryFullName;
  @Column(nullable=false,length=64) private String repositoryIdentity;
  @Column(nullable=false,length=255) private String branch;
  @Column(length=64) private String beforeSha;
  @Column(nullable=false,length=64) private String commitId;
  @Column(length=300) private String summary;
  @Column(length=80) private String actor;
  @Column(nullable=false) private Instant receivedAt;
  protected GitHubPushEvent(){}
  public GitHubPushEvent(String deliveryId,String repositoryFullName,String identity,String branch,String beforeSha,String commitId,String summary,String actor,Instant receivedAt){
    this.deliveryId=deliveryId;this.repositoryFullName=repositoryFullName;this.repositoryIdentity=identity;this.branch=branch;this.beforeSha=beforeSha;this.commitId=commitId;this.summary=summary;this.actor=actor;this.receivedAt=receivedAt;
  }
  public String getDeliveryId(){return deliveryId;} public String getRepositoryFullName(){return repositoryFullName;} public String getRepositoryIdentity(){return repositoryIdentity;}
  public String getBranch(){return branch;} public String getBeforeSha(){return beforeSha;} public String getCommitId(){return commitId;} public String getSummary(){return summary;} public String getActor(){return actor;} public Instant getReceivedAt(){return receivedAt;}
}
