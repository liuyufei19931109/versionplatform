package com.local.release.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name="wecom_notification",indexes={@Index(name="idx_wecom_due",columnList="status,nextAttemptAt"),@Index(name="idx_wecom_task",columnList="taskId")})
public class WecomNotification extends BaseEntity {
  public enum Status { PENDING, SENDING, SENT, FAILED }
  private Long taskId;
  @Column(length=64,nullable=false,unique=true) private String dedupKey;
  @Column(length=64,nullable=false) private String targetHash;
  @JsonIgnore @Column(length=2000,nullable=false) private String encryptedWebhook;
  @JsonIgnore @Lob @Column(nullable=false,columnDefinition="TEXT") private String payload;
  @Enumerated(EnumType.STRING) @Column(length=16,nullable=false) private Status status=Status.PENDING;
  @Column(nullable=false) private int attempts;
  @Column(nullable=false) private int cycleAttempts;
  private LocalDateTime nextAttemptAt;
  private LocalDateTime lastAttemptAt;
  private LocalDateTime sentAt;
  @Column(length=250) private String lastError;
  public Long getTaskId(){return taskId;} public void setTaskId(Long v){taskId=v;}
  public String getDedupKey(){return dedupKey;} public void setDedupKey(String v){dedupKey=v;}
  public String getTargetHash(){return targetHash;} public void setTargetHash(String v){targetHash=v;}
  public String getEncryptedWebhook(){return encryptedWebhook;} public void setEncryptedWebhook(String v){encryptedWebhook=v;}
  public String getPayload(){return payload;} public void setPayload(String v){payload=v;}
  public Status getStatus(){return status;} public void setStatus(Status v){status=v;}
  public int getAttempts(){return attempts;} public void setAttempts(int v){attempts=v;}
  public int getCycleAttempts(){return cycleAttempts;} public void setCycleAttempts(int v){cycleAttempts=v;}
  public LocalDateTime getNextAttemptAt(){return nextAttemptAt;} public void setNextAttemptAt(LocalDateTime v){nextAttemptAt=v;}
  public LocalDateTime getLastAttemptAt(){return lastAttemptAt;} public void setLastAttemptAt(LocalDateTime v){lastAttemptAt=v;}
  public LocalDateTime getSentAt(){return sentAt;} public void setSentAt(LocalDateTime v){sentAt=v;}
  public String getLastError(){return lastError;} public void setLastError(String v){lastError=v;}
}
