package com.local.release.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import static com.local.release.domain.ModelEnums.TaskStatus;

@Entity
@Table(name="runtime_operation", indexes=@Index(name="ix_runtime_operation_target", columnList="environmentId,serverId,id"))
public class RuntimeOperation extends BaseEntity {
  @Column(nullable=false) private Long environmentId;
  @Column(nullable=false) private Long serviceId;
  @Column(nullable=false) private Long serverId;
  @Column(nullable=false,length=100) private String serviceName;
  @Column(nullable=false,length=100) private String serviceKey;
  @Column(nullable=false,length=20) private String environmentName;
  @Column(nullable=false,length=100) private String serverName;
  @Column(nullable=false,length=200) private String host;
  @Column(nullable=false,length=64) private String createdBy;
  @Column(nullable=false,unique=true,length=64) private String requestKey;
  @Column(nullable=false,length=64) private String requestFingerprint;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private TaskStatus status=TaskStatus.PENDING;
  @Column(length=40) private String currentStage="WAITING";
  @Lob @Column(columnDefinition="TEXT") private String logText="";
  @Column(length=500) private String errorMessage;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  protected RuntimeOperation() {}

  public RuntimeOperation(Long environmentId,Long serviceId,Long serverId,String serviceName,String serviceKey,
      String environmentName,String serverName,String host,String createdBy,String requestKey,String requestFingerprint) {
    this.environmentId=environmentId;this.serviceId=serviceId;this.serverId=serverId;this.serviceName=serviceName;
    this.serviceKey=serviceKey;this.environmentName=environmentName;this.serverName=serverName;this.host=host;
    this.createdBy=createdBy;this.requestKey=requestKey;this.requestFingerprint=requestFingerprint;
  }
  public Long getEnvironmentId(){return environmentId;} public Long getServiceId(){return serviceId;} public Long getServerId(){return serverId;}
  public String getServiceName(){return serviceName;} public String getServiceKey(){return serviceKey;}
  public String getEnvironmentName(){return environmentName;} public String getServerName(){return serverName;} public String getHost(){return host;}
  public String getCreatedBy(){return createdBy;}
  @JsonIgnore public String getRequestKey(){return requestKey;} @JsonIgnore public String getRequestFingerprint(){return requestFingerprint;}
  public TaskStatus getStatus(){return status;} public void setStatus(TaskStatus v){status=v;}
  public String getCurrentStage(){return currentStage;} public void setCurrentStage(String v){currentStage=v;}
  public String getLogText(){return logText;}
  public void appendLog(String line){logText=(logText==null?"":logText)+line+"\n";if(logText.length()>24000)logText="[早期日志已截断]\n"+logText.substring(logText.length()-23000);}
  public String getErrorMessage(){return errorMessage;} public void setErrorMessage(String v){errorMessage=v;}
  public LocalDateTime getStartedAt(){return startedAt;} public void setStartedAt(LocalDateTime v){startedAt=v;}
  public LocalDateTime getFinishedAt(){return finishedAt;} public void setFinishedAt(LocalDateTime v){finishedAt=v;}
}
