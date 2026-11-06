package com.local.release.domain;

import jakarta.persistence.*;

/** One remote creation attempt per daily version and normalized Git repository. */
@Entity @Table(name="version_repository",uniqueConstraints=@UniqueConstraint(name="uk_version_repository",columnNames={"versionId","repositoryIdentity"}))
public class VersionRepository extends BaseEntity {
  @Column(nullable=false) private Long versionId;
  @Column(nullable=false,length=64) private String repositoryIdentity;
  @Lob @Column(nullable=false,columnDefinition="TEXT") private String encryptedRepository;
  @Column(length=64) private String baselineSha;
  @Column(length=300) private String baselineMessage;
  @Column(nullable=false,length=20) private String status="PENDING";
  @Column(length=64) private String errorCode;
  @Column(length=600) private String message;
  @Column(length=36) private String attemptId;
  @Column(nullable=false,length=64) private String requestedBy;
  protected VersionRepository() {}
  public VersionRepository(Long versionId,String identity,String encryptedRepository,String operator){this.versionId=versionId;this.repositoryIdentity=identity;this.encryptedRepository=encryptedRepository;this.requestedBy=operator;}
  public Long getVersionId(){return versionId;}
  public String getRepositoryIdentity(){return repositoryIdentity;}
  public String getEncryptedRepository(){return encryptedRepository;}
  public String getBaselineSha(){return baselineSha;}
  public String getBaselineMessage(){return baselineMessage;}
  public String getStatus(){return status;}
  public String getErrorCode(){return errorCode;}
  public String getMessage(){return message;}
  public String getAttemptId(){return attemptId;}
  public String getRequestedBy(){return requestedBy;}
  public void prepare(String repository,String sha,String summary,String operator){encryptedRepository=repository;baselineSha=sha;baselineMessage=summary;requestedBy=operator;attemptId=java.util.UUID.randomUUID().toString();result("PENDING",null,"等待创建分支");}
  public void result(String status,String code,String message){this.status=status;this.errorCode=code;this.message=message;}
}
