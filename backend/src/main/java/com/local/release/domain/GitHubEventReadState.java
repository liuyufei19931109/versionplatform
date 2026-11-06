package com.local.release.domain;

import jakarta.persistence.*;

@Entity @Table(name="github_event_read_state",uniqueConstraints=@UniqueConstraint(name="uk_github_read_user_event",columnNames={"userId","eventId"}))
public class GitHubEventReadState extends BaseEntity {
  @Column(nullable=false) private Long userId;
  @Column(nullable=false) private Long eventId;
  protected GitHubEventReadState(){}
  public GitHubEventReadState(Long userId,Long eventId){this.userId=userId;this.eventId=eventId;}
  public Long getUserId(){return userId;} public Long getEventId(){return eventId;}
}
