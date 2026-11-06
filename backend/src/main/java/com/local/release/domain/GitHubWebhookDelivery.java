package com.local.release.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Bounded delivery audit. Raw payloads, signatures, credentials and clone URLs are never stored. */
@Entity @Table(name="github_webhook_delivery",uniqueConstraints=@UniqueConstraint(name="uk_github_delivery",columnNames="deliveryId"),indexes=@Index(name="ix_github_delivery_branch",columnList="repositoryIdentity,branch,eventSha"))
public class GitHubWebhookDelivery extends BaseEntity {
  @Column(nullable=false,length=80) private String deliveryId;
  @Column(nullable=false,length=64) private String bodySha256;
  @Column(nullable=false,length=40) private String eventType;
  @Column(nullable=false,length=30) private String status;
  @Column(length=64) private String repositoryIdentity;
  @Column(length=120) private String branch;
  @Column(length=64) private String beforeSha;
  @Column(length=64) private String eventSha;
  @Column(length=300) private String title;
  @Column(length=80) private String actor;
  @Column(length=500) private String eventUrl;
  private Instant eventTime;
  private Long platformReleaseTaskId;
  protected GitHubWebhookDelivery(){}
  public GitHubWebhookDelivery(String deliveryId,String bodySha256,String eventType,String status){this.deliveryId=deliveryId;this.bodySha256=bodySha256;this.eventType=eventType;this.status=status;}
  public void details(String identity,String branch,String before,String sha,String title,String actor,String url,Instant eventTime){this.repositoryIdentity=identity;this.branch=branch;this.beforeSha=before;this.eventSha=sha;this.title=title;this.actor=actor;this.eventUrl=url;this.eventTime=eventTime;}
  public void platformMerge(Long taskId){status="PLATFORM_MERGE";platformReleaseTaskId=taskId;}
  public String getDeliveryId(){return deliveryId;} public String getBodySha256(){return bodySha256;} public String getEventType(){return eventType;} public String getStatus(){return status;}
  public String getRepositoryIdentity(){return repositoryIdentity;} public String getBranch(){return branch;} public String getBeforeSha(){return beforeSha;} public String getEventSha(){return eventSha;}
  public String getTitle(){return title;} public String getActor(){return actor;} public String getEventUrl(){return eventUrl;} public Instant getEventTime(){return eventTime;} public Long getPlatformReleaseTaskId(){return platformReleaseTaskId;}
}
