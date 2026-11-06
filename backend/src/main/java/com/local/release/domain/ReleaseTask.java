package com.local.release.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;

@Entity @Table(name="release_task")
public class ReleaseTask extends BaseEntity {
  @Column(nullable=false) private Long serviceId;
  @Enumerated(EnumType.STRING) @Column(length=20) private TaskKind taskKind=TaskKind.DEPLOY;
  private Long artifactId;
  @Column(length=20) private String deliveryType;
  @Column(length=16) private String mobileBuildEngine;
  @Column(length=16) private String frontendBuildEngine;
  @Column(length=16) private String backendBuildEngine;
  @com.fasterxml.jackson.annotation.JsonIgnore @Lob @Column(name="mobile_build_config_snapshot",columnDefinition="CLOB") private String mobileBuildConfigSnapshot;
  @com.fasterxml.jackson.annotation.JsonIgnore private Boolean mergeEnqueuePending;
  private Boolean productionScopeComplete;
  @Column(nullable=false,length=80) private String environmentName;
  @Column(nullable=false,length=120) private String branch;
  @Column(length=64) private String commitId;
  @com.fasterxml.jackson.annotation.JsonIgnore @Column(length=180,unique=true) private String requestKey;
  @com.fasterxml.jackson.annotation.JsonIgnore @Column(length=64) private String requestFingerprint;
  @com.fasterxml.jackson.annotation.JsonIgnore @Column(length=180) private String lastRetryRequestKey;
  @com.fasterxml.jackson.annotation.JsonIgnore @Lob @Column(columnDefinition="TEXT") private String retryRequestKeys;
  @com.fasterxml.jackson.annotation.JsonIgnore private Boolean imageTagUpgraded;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private TaskStatus status=TaskStatus.PENDING;
  @Enumerated(EnumType.STRING) @Column(length=30) private StepType currentStep;
  @Column(length=1000) private String errorMessage;
  @Column(length=500) private String releaseNote;
  @Column(length=64) private String createdBy;
  @Column(length=1000) private String targetServerIds;
  @Column(nullable=false) private boolean simulate=false;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  protected ReleaseTask(){}
  public ReleaseTask(Long serviceId,TaskKind taskKind,Long artifactId,String environmentName,String branch,String releaseNote,boolean simulate){this.serviceId=serviceId;this.taskKind=taskKind;this.artifactId=artifactId;this.environmentName=environmentName;this.branch=branch;this.releaseNote=releaseNote;this.simulate=simulate;}
  public Long getServiceId(){return serviceId;} public String getEnvironmentName(){return environmentName;} public String getBranch(){return branch;}
  public TaskKind getTaskKind(){return taskKind;} public Long getArtifactId(){return artifactId;}
  public String getDeliveryType(){return deliveryType==null?"SERVER":deliveryType;} public void setDeliveryType(String v){deliveryType=v;}
  /** Historical tasks remain local, irrespective of the current server switch. */
  public String getBackendBuildEngine(){return backendBuildEngine==null?"LOCAL":backendBuildEngine;}
  public void setBackendBuildEngine(String value){if(!Set.of("LOCAL","GITHUB").contains(value))throw new IllegalArgumentException("后端构建方式无效");if(backendBuildEngine!=null&&!backendBuildEngine.equals(value))throw new IllegalStateException("任务构建方式不可修改");backendBuildEngine=value;}
  public String getFrontendBuildEngine(){return frontendBuildEngine==null?"LOCAL":frontendBuildEngine;}
  public void setFrontendBuildEngine(String value){if(!Set.of("LOCAL","GITHUB").contains(value))throw new IllegalArgumentException("前端构建方式无效");if(frontendBuildEngine!=null&&!frontendBuildEngine.equals(value))throw new IllegalStateException("任务构建方式不可修改");frontendBuildEngine=value;}
  public String getMobileBuildEngine(){return mobileBuildEngine==null?"LOCAL":mobileBuildEngine;}
  public void setMobileBuildEngine(String value){if(!Set.of("LOCAL","GITHUB").contains(value))throw new IllegalArgumentException("App 构建方式无效");if(mobileBuildEngine!=null&&!mobileBuildEngine.equals(value))throw new IllegalStateException("任务构建方式不可修改");mobileBuildEngine=value;}
  @com.fasterxml.jackson.annotation.JsonIgnore public String getMobileBuildConfigSnapshot(){return mobileBuildConfigSnapshot;}
  public void setMobileBuildConfigSnapshot(String value){if(mobileBuildConfigSnapshot!=null&&!Objects.equals(mobileBuildConfigSnapshot,value))throw new IllegalStateException("App 构建快照不可修改");mobileBuildConfigSnapshot=value;}
  public boolean getMergeEnqueuePending(){return Boolean.TRUE.equals(mergeEnqueuePending);} public void setMergeEnqueuePending(boolean v){mergeEnqueuePending=v;}
  @com.fasterxml.jackson.annotation.JsonIgnore public boolean isMergeEnqueuePending(){return Boolean.TRUE.equals(mergeEnqueuePending);}
  public Boolean getProductionScopeComplete(){return productionScopeComplete;} public void setProductionScopeComplete(Boolean v){productionScopeComplete=v;}
  public String getCommitId(){return commitId;} public void setCommitId(String v){commitId=v;}
  public String getRequestKey(){return requestKey;} public void setRequestKey(String v){requestKey=v;}
  public String getRequestFingerprint(){return requestFingerprint;} public void setRequestFingerprint(String v){requestFingerprint=v;}
  public String getLastRetryRequestKey(){return lastRetryRequestKey;} public void setLastRetryRequestKey(String v){if(v!=null&&!hasRetryRequestKey(v))retryRequestKeys=(retryRequestKeys==null?"":retryRequestKeys)+v+"\n";lastRetryRequestKey=v;}
  public boolean hasRetryRequestKey(String value){return Objects.equals(lastRetryRequestKey,value)||(retryRequestKeys!=null&&retryRequestKeys.lines().anyMatch(value::equals));}
  public boolean isImageTagUpgraded(){return Boolean.TRUE.equals(imageTagUpgraded);} public void setImageTagUpgraded(boolean value){imageTagUpgraded=value;}
  public TaskStatus getStatus(){return status;} public void setStatus(TaskStatus v){status=v;}
  public StepType getCurrentStep(){return currentStep;} public void setCurrentStep(StepType v){currentStep=v;}
  public String getErrorMessage(){return errorMessage;} public void setErrorMessage(String v){errorMessage=v;}
  public String getReleaseNote(){return releaseNote;}
  public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
  public String getTargetServerIds(){return targetServerIds;} public void setTargetServerIds(Collection<Long> values){targetServerIds=values==null?null:values.stream().filter(Objects::nonNull).distinct().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));}
  public List<Long> targetServerIdList(){if(targetServerIds==null||targetServerIds.isBlank())return List.of();return Arrays.stream(targetServerIds.split(",")).filter(v->!v.isBlank()).map(Long::valueOf).toList();}
  public boolean isSimulate(){return simulate;}
  public LocalDateTime getStartedAt(){return startedAt;} public void setStartedAt(LocalDateTime v){startedAt=v;}
  public LocalDateTime getFinishedAt(){return finishedAt;} public void setFinishedAt(LocalDateTime v){finishedAt=v;}
}
