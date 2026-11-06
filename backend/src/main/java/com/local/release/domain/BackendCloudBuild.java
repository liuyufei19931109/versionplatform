package com.local.release.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Durable external execution journal. No credentials or signing material are stored here. */
@Entity @Table(name="backend_cloud_build")
public class BackendCloudBuild {
  @Id private Long taskId;
  @Version private Long revision;
  @Column(nullable=false,length=200) private String repository;
  @Column(nullable=false,length=120) private String workflow;
  @Column(nullable=false,length=120) private String workflowRef;
  @Column(nullable=false,length=200) private String sourceRepository;
  @Column(nullable=false,length=36,unique=true) private String requestId;
  @Column(nullable=false,length=24) private String state="READY";
  @com.fasterxml.jackson.annotation.JsonIgnore @Lob @Column(nullable=false,columnDefinition="CLOB") private String buildConfigSnapshot;
  private Long runId;
  private Instant dispatchedAt;
  private Instant deadline;
  @Column(length=40) private String conclusion;
  protected BackendCloudBuild(){}
  public BackendCloudBuild(Long taskId,String repository,String workflow,String workflowRef,String sourceRepository,String requestId,String buildConfigSnapshot){this.taskId=taskId;this.repository=repository;this.workflow=workflow;this.workflowRef=workflowRef;this.sourceRepository=sourceRepository;this.requestId=requestId;this.buildConfigSnapshot=buildConfigSnapshot;}
  public String getBuildConfigSnapshot(){return buildConfigSnapshot;}
  public Long getTaskId(){return taskId;} public String getRepository(){return repository;}
  public String getWorkflow(){return workflow;} public String getWorkflowRef(){return workflowRef;}
  public String getSourceRepository(){return sourceRepository;} public String getRequestId(){return requestId;}
  public String getState(){return state;} public Long getRunId(){return runId;}
  public Instant getDispatchedAt(){return dispatchedAt;} public Instant getDeadline(){return deadline;}
  public String getConclusion(){return conclusion;}
  public void dispatching(Instant now,int seconds){if(!"READY".equals(state))throw new IllegalStateException("云端任务已提交");state="SUBMITTING";dispatchedAt=now;deadline=now.plusSeconds(seconds);}
  public void attach(long value){if(value<1||runId!=null&&runId!=value)throw new IllegalStateException("GitHub 运行编号不匹配");runId=value;if(!"CANCEL_REQUESTED".equals(state))state="TRACKING";}
  public void completed(String value){conclusion=value;state="success".equals(value)?"SUCCEEDED":"REMOTE_FAILED";}
  public void requestCancel(){if(!"SUCCEEDED".equals(state)&&!"REMOTE_FAILED".equals(state)&&!"CANCELLED".equals(state))state="READY".equals(state)?"CANCELLED":"CANCEL_REQUESTED";}
  public void cancelled(){state="CANCELLED";}
  public void retry(String id){if(!"REMOTE_FAILED".equals(state)&&!"CANCELLED".equals(state))throw new IllegalStateException("请先确认原 GitHub 构建已结束");requestId=id;state="READY";runId=null;dispatchedAt=null;deadline=null;conclusion=null;}
}
