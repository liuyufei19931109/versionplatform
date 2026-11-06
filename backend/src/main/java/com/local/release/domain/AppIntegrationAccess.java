package com.local.release.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/** One revocable integration credential per App. Only the token digest is persisted. */
@Entity @Table(name="app_integration_access")
public class AppIntegrationAccess extends BaseEntity {
  @Column(nullable=false,unique=true) private Long serviceId;
  @Column(nullable=false,length=100) private String name;
  @Column(nullable=false,length=40) private String environments;
  @JsonIgnore @Column(length=64) private String tokenHash;
  @Column(length=60) private String tokenPrefix;
  private LocalDateTime revokedAt;
  @Column(nullable=false,length=120) private String createdBy;
  @Column(nullable=false,length=120) private String updatedBy;
  protected AppIntegrationAccess(){}
  public AppIntegrationAccess(Long serviceId,String name,String environments,String createdBy){
    this.serviceId=serviceId;this.name=name;this.environments=environments;this.createdBy=createdBy;this.updatedBy=createdBy;
  }
  public Long getServiceId(){return serviceId;}
  public String getName(){return name;} public void setName(String value){name=value;}
  public String getEnvironments(){return environments;} public void setEnvironments(String value){environments=value;}
  @JsonIgnore public String getTokenHash(){return tokenHash;} public void setTokenHash(String value){tokenHash=value;}
  public String getTokenPrefix(){return tokenPrefix;} public void setTokenPrefix(String value){tokenPrefix=value;}
  public LocalDateTime getRevokedAt(){return revokedAt;} public void setRevokedAt(LocalDateTime value){revokedAt=value;}
  public String getCreatedBy(){return createdBy;}
  public String getUpdatedBy(){return updatedBy;} public void setUpdatedBy(String value){updatedBy=value;}
}
