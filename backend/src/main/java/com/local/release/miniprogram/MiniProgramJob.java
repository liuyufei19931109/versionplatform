package com.local.release.miniprogram;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.local.release.domain.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name="mini_program_job",indexes=@Index(name="mini_job_project_date",columnList="projectId,createdAt"))
public class MiniProgramJob extends BaseEntity {
  public enum Action { PREVIEW, UPLOAD }
  public enum Status { PENDING, RUNNING, SUCCESS, FAILED, CANCELLED, UNKNOWN }
  public enum Resolution { CONFIRMED_UPLOADED, CONFIRMED_NOT_UPLOADED }
  @Column(nullable=false) private Long projectId;
  @Column(nullable=false,length=100) private String projectName;
  @Column(nullable=false,length=18) private String appId;
  @Column(nullable=false,length=120) private String branch;
  @Column(nullable=false,length=64) private String commitId;
  @Column(nullable=false,length=64) private String version;
  @Column(length=500) private String note;
  @Column(nullable=false,length=64) private String createdBy;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private Action action;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private Status status=Status.PENDING;
  @Column(length=32) private String stage="QUEUED";
  @Column(length=64) private String baselineCommit;
  @Column(length=1000) private String errorMessage;
  @Lob @Column(columnDefinition="TEXT") private String logText="";
  @JsonIgnore @Lob @Column(columnDefinition="TEXT",nullable=false) private String configurationSnapshot;
  @JsonIgnore @Column(nullable=false,unique=true,length=160) private String requestKey;
  @JsonIgnore @Column(nullable=false,length=64) private String fingerprint;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  @Enumerated(EnumType.STRING) @Column(length=32) private Resolution resolution;
  @Column(length=64) private String resolvedBy;
  private LocalDateTime resolvedAt;
  @Column(length=500) private String resolutionNote;
  protected MiniProgramJob(){}
  public MiniProgramJob(MiniProgramProject p,Action a,String branch,String sha,String version,String note,String actor,String snapshot,String requestKey,String fingerprint){
    projectId=p.getId();projectName=p.getName();appId=p.getAppId();action=a;this.branch=branch;commitId=sha;this.version=version;
    this.note=note;createdBy=actor;configurationSnapshot=snapshot;this.requestKey=requestKey;this.fingerprint=fingerprint;
  }
  public Long getProjectId(){return projectId;} public String getProjectName(){return projectName;}
  public String getAppId(){return appId;} public String getBranch(){return branch;} public String getCommitId(){return commitId;}
  public String getVersion(){return version;} public String getNote(){return note;} public String getCreatedBy(){return createdBy;}
  public Action getAction(){return action;} public Status getStatus(){return status;} public String getStage(){return stage;}
  public String getBaselineCommit(){return baselineCommit;} public String getErrorMessage(){return errorMessage;}
  public String getLogText(){return logText;} public LocalDateTime getStartedAt(){return startedAt;} public LocalDateTime getFinishedAt(){return finishedAt;}
  public Resolution getResolution(){return resolution;} public String getResolvedBy(){return resolvedBy;}
  public LocalDateTime getResolvedAt(){return resolvedAt;} public String getResolutionNote(){return resolutionNote;}
  public void reconcile(Resolution result,String actor,String note){resolution=result;resolvedBy=actor;resolutionNote=note;resolvedAt=LocalDateTime.now();}
  @JsonIgnore public String getConfigurationSnapshot(){return configurationSnapshot;}
  @JsonIgnore public String getFingerprint(){return fingerprint;}
  public void start(){status=Status.RUNNING;startedAt=LocalDateTime.now();}
  public void stage(String value){stage=value;}
  public void baseline(String value){baselineCommit=value;}
  public void finish(Status value,String message){status=value;errorMessage=message;finishedAt=LocalDateTime.now();}
  public void log(String line){String value=logText+line+"\n";logText=value.length()>100_000?value.substring(value.length()-100_000):value;}
}
