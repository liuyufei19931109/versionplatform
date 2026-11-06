package com.local.release.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Append-only business closure audit. Existing version, Git and deployment records are untouched. */
@Entity
@Table(name="version_completion_event",indexes=@Index(name="ix_completion_version",columnList="versionId,id"))
public class VersionCompletionEvent extends BaseEntity {
  @Column(nullable=false) private Long versionId;
  @Column(nullable=false,length=20) private String eventType;
  @Column(nullable=false,length=64) private String actor;
  @Column(length=64) private String completedBy;
  private LocalDateTime completedAt;
  @Column(length=64,unique=true) private String requestHash;
  @Lob @Column(nullable=false,columnDefinition="TEXT") private String contentJson;
  protected VersionCompletionEvent() {}
  public VersionCompletionEvent(Long versionId,String eventType,String actor,String completedBy,LocalDateTime completedAt,String requestHash,String contentJson){
    this.versionId=versionId;this.eventType=eventType;this.actor=actor;this.completedBy=completedBy;this.completedAt=completedAt;this.requestHash=requestHash;this.contentJson=contentJson;
  }
  public Long getVersionId(){return versionId;} public String getEventType(){return eventType;} public String getActor(){return actor;}
  public String getCompletedBy(){return completedBy;} public LocalDateTime getCompletedAt(){return completedAt;}
  public String getRequestHash(){return requestHash;} public String getContentJson(){return contentJson;}
}
