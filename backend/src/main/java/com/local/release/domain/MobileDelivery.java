package com.local.release.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/** One immutable package handed to an external publisher for one environment. */
@Entity @Table(name="mobile_delivery")
public class MobileDelivery extends BaseEntity {
  @Column(nullable=false,unique=true) private Long releaseTaskId;
  @Column(nullable=false) private Long artifactId;
  @Column(nullable=false,length=64) private String sha256;
  private LocalDateTime downloadedAt;
  @Column(length=120) private String downloadedBy;
  private LocalDateTime confirmedAt;
  @Column(length=120) private String confirmedBy;
  @Column(length=20) private String confirmationStatus="WAITING";
  @Column(length=500) private String confirmationMessage;
  @JsonIgnore @Column(length=100) private String confirmationRequestId;
  @JsonIgnore @Column(length=64) private String confirmationFingerprint;
  @JsonIgnore @Column(length=64) private String fetchTokenHash;
  @JsonIgnore @Column(length=64) private String confirmTokenHash;
  private LocalDateTime accessExpiresAt;
  protected MobileDelivery(){}
  public MobileDelivery(Long taskId,Long artifactId,String sha256){this.releaseTaskId=taskId;this.artifactId=artifactId;this.sha256=sha256;}
  public Long getReleaseTaskId(){return releaseTaskId;}
  public Long getArtifactId(){return artifactId;}
  public String getSha256(){return sha256;}
  public LocalDateTime getDownloadedAt(){return downloadedAt;} public void setDownloadedAt(LocalDateTime v){downloadedAt=v;}
  public String getDownloadedBy(){return downloadedBy;} public void setDownloadedBy(String v){downloadedBy=v;}
  public LocalDateTime getConfirmedAt(){return confirmedAt;} public void setConfirmedAt(LocalDateTime v){confirmedAt=v;}
  public String getConfirmedBy(){return confirmedBy;} public void setConfirmedBy(String v){confirmedBy=v;}
  public String getConfirmationStatus(){return confirmationStatus;} public void setConfirmationStatus(String v){confirmationStatus=v;}
  public String getConfirmationMessage(){return confirmationMessage;} public void setConfirmationMessage(String v){confirmationMessage=v;}
  public String getConfirmationRequestId(){return confirmationRequestId;} public void setConfirmationRequestId(String v){confirmationRequestId=v;}
  public String getConfirmationFingerprint(){return confirmationFingerprint;} public void setConfirmationFingerprint(String v){confirmationFingerprint=v;}
  public String getFetchTokenHash(){return fetchTokenHash;} public void setFetchTokenHash(String v){fetchTokenHash=v;}
  public String getConfirmTokenHash(){return confirmTokenHash;} public void setConfirmTokenHash(String v){confirmTokenHash=v;}
  public LocalDateTime getAccessExpiresAt(){return accessExpiresAt;} public void setAccessExpiresAt(LocalDateTime v){accessExpiresAt=v;}
}
