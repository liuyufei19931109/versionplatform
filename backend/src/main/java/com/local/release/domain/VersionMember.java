package com.local.release.domain;

import jakarta.persistence.*;
import com.local.release.domain.ModelEnums.ServiceType;

@Entity @Table(name="version_member",uniqueConstraints=@UniqueConstraint(name="uk_version_member",columnNames={"versionId","serviceId"}))
public class VersionMember extends BaseEntity {
  @Column(nullable=false) private Long versionId;
  @Column(nullable=false) private Long repositoryId;
  @Column(nullable=false) private Long serviceId;
  @Column(nullable=false,length=100) private String serviceName;
  @Column(nullable=false,length=100) private String serviceKey;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private ServiceType serviceType;
  /** Last acknowledged version-branch HEAD; separate from the repository's master creation baseline. */
  @Column(length=64) private String codeWatchBaselineSha;
  private java.time.Instant codeWatchStartedAt;
  protected VersionMember() {}
  public VersionMember(Long versionId,Long repositoryId,AppService service){this.versionId=versionId;this.repositoryId=repositoryId;serviceId=service.getId();serviceName=service.getName();serviceKey=service.getServiceKey();serviceType=service.getServiceType();}
  public Long getVersionId(){return versionId;} public Long getRepositoryId(){return repositoryId;}
  public Long getServiceId(){return serviceId;} public String getServiceName(){return serviceName;}
  public String getServiceKey(){return serviceKey;} public ServiceType getServiceType(){return serviceType;}
  public String getCodeWatchBaselineSha(){return codeWatchBaselineSha;}
  public java.time.Instant getCodeWatchStartedAt(){return codeWatchStartedAt;}
}
