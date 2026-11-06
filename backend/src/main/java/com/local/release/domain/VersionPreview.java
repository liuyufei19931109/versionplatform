package com.local.release.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="version_preview",uniqueConstraints=@UniqueConstraint(name="uk_version_preview_token",columnNames="token"))
public class VersionPreview extends BaseEntity {
  @Column(nullable=false,length=36) private String token;
  @Column(nullable=false,length=64) private String operator;
  @Column(nullable=false) private Instant expiresAt;
  @Lob @Column(nullable=false,columnDefinition="TEXT") private String payload;
  private Long consumedVersionId;
  protected VersionPreview() {}
  public VersionPreview(String token,String operator,Instant expiresAt,String payload){this.token=token;this.operator=operator;this.expiresAt=expiresAt;this.payload=payload;}
  public String getToken(){return token;} public String getOperator(){return operator;}
  public Instant getExpiresAt(){return expiresAt;} public String getPayload(){return payload;}
  public Long getConsumedVersionId(){return consumedVersionId;} public void consume(Long versionId){consumedVersionId=versionId;}
}
