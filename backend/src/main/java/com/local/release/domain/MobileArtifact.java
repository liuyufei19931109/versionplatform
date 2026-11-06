package com.local.release.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/** Immutable validated file metadata; the local filesystem location never leaves the server. */
@Entity @Table(name="mobile_artifact",uniqueConstraints={@UniqueConstraint(columnNames="artifact_id"),@UniqueConstraint(columnNames="build_task_id")})
public class MobileArtifact extends BaseEntity {
  @Column(nullable=false) private Long artifactId;
  @Column(nullable=false) private Long buildTaskId;
  @Column(nullable=false) private Long serviceId;
  @Column(nullable=false,length=20) private String platform="ANDROID";
  @Column(nullable=false,length=10) private String format="AAB";
  @Column(nullable=false,length=255) private String applicationId;
  @Column(nullable=false,length=120) private String versionName;
  @Column(nullable=false) private long versionCode;
  @Column(nullable=false,length=64) private String sha256;
  @Column(nullable=false) private long sizeBytes;
  @Column(nullable=false,length=64) private String certificateSha256;
  @Column(nullable=false,length=64) private String commitId;
  @Column(nullable=false,length=60) private String flutterVersion;
  @Column(length=80) private String flavor;
  @Column(length=300) private String target;
  @Column(length=20) private String buildEnvironmentName;
  @Column(length=20) private String buildMode;
  @JsonIgnore @Column(nullable=false,length=1000) private String archivePath;
  protected MobileArtifact(){}
  public MobileArtifact(Long artifactId,Long buildTaskId,Long serviceId,String applicationId,String versionName,long versionCode,
      String sha256,long sizeBytes,String certificateSha256,String commitId,String flutterVersion,String archivePath){
    this(artifactId,buildTaskId,serviceId,applicationId,versionName,versionCode,sha256,sizeBytes,certificateSha256,commitId,flutterVersion,archivePath,null,null);
  }
  public MobileArtifact(Long artifactId,Long buildTaskId,Long serviceId,String applicationId,String versionName,long versionCode,
      String sha256,long sizeBytes,String certificateSha256,String commitId,String flutterVersion,String archivePath,String flavor,String target){
    this(artifactId,buildTaskId,serviceId,applicationId,versionName,versionCode,sha256,sizeBytes,certificateSha256,commitId,flutterVersion,archivePath,flavor,target,"AAB");
  }
  public MobileArtifact(Long artifactId,Long buildTaskId,Long serviceId,String applicationId,String versionName,long versionCode,
      String sha256,long sizeBytes,String certificateSha256,String commitId,String flutterVersion,String archivePath,String flavor,String target,String format){
    this.artifactId=artifactId;this.buildTaskId=buildTaskId;this.serviceId=serviceId;this.applicationId=applicationId;this.versionName=versionName;
    this.versionCode=versionCode;this.sha256=sha256;this.sizeBytes=sizeBytes;this.certificateSha256=certificateSha256;this.commitId=commitId;
    this.flutterVersion=flutterVersion;this.archivePath=archivePath;
    this.flavor=flavor;this.target=target;
    if(!java.util.Set.of("APK","AAB").contains(format))throw new IllegalArgumentException("未知 Android 产物格式");this.format=format;
  }
  public Long getArtifactId(){return artifactId;} public Long getBuildTaskId(){return buildTaskId;} public Long getServiceId(){return serviceId;}
  public String getPlatform(){return platform;} public String getFormat(){return format;} public String getApplicationId(){return applicationId;}
  public String getVersionName(){return versionName;} public long getVersionCode(){return versionCode;} public String getSha256(){return sha256;}
  public long getSizeBytes(){return sizeBytes;} public String getCertificateSha256(){return certificateSha256;} public String getCommitId(){return commitId;}
  public String getFlutterVersion(){return flutterVersion;} @JsonIgnore public String getArchivePath(){return archivePath;}
  public String getFlavor(){return flavor;} public String getTarget(){return target;}
  public String getBuildEnvironmentName(){return buildEnvironmentName;} public void setBuildEnvironmentName(String value){buildEnvironmentName=value;}
  public String getBuildMode(){return buildMode;} public void setBuildMode(String value){buildMode=value;}
}
