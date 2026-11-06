package com.local.release.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/** One global channel. Existing deployment and environment rows are untouched. */
@Entity @Table(name="wecom_notification_settings")
public class WecomNotificationSettings {
  @Id private Long id=1L;
  @Column(nullable=false) private boolean enabled;
  @JsonIgnore @Column(length=2000) private String encryptedWebhook;
  @Column(length=80,nullable=false) private String groupName="发版通知群";
  @Column(length=500,nullable=false) private String publicBaseUrl="";
  @Column(length=40,nullable=false) private String environments="test,prod";
  @Column(nullable=false) private boolean notifySuccess=true;
  @Column(nullable=false) private boolean notifyFailure=true;
  public Long getId(){return id;}
  public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
  public String getEncryptedWebhook(){return encryptedWebhook;} public void setEncryptedWebhook(String v){encryptedWebhook=v;}
  public String getGroupName(){return groupName;} public void setGroupName(String v){groupName=v;}
  public String getPublicBaseUrl(){return publicBaseUrl;} public void setPublicBaseUrl(String v){publicBaseUrl=v;}
  public String getEnvironments(){return environments;} public void setEnvironments(String v){environments=v;}
  public boolean isNotifySuccess(){return notifySuccess;} public void setNotifySuccess(boolean v){notifySuccess=v;}
  public boolean isNotifyFailure(){return notifyFailure;} public void setNotifyFailure(boolean v){notifyFailure=v;}
}
