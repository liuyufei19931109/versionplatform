package com.local.release.domain;

import jakarta.persistence.*;

@Entity @Table(name="artifact")
public class Artifact extends BaseEntity {
  @Column(nullable=false) private Long serviceId;
  @Column(name="release_task_id",nullable=false) private Long buildTaskId;
  @Column(nullable=false,length=120) private String version;
  @Column(length=120) private String branch;
  @Column(length=64) private String commitId;
  @Column(length=500) private String jarPath;
  @Column(length=500) private String imageUrl;
  @Column(length=200) private String imageDigest;
  @Column(length=64) private String createdBy;
  protected Artifact(){}
  public Artifact(Long serviceId,Long buildTaskId,String version,String branch,String commitId,String imageUrl){this.serviceId=serviceId;this.buildTaskId=buildTaskId;this.version=version;this.branch=branch;this.commitId=commitId;this.imageUrl=imageUrl;}
  public Long getServiceId(){return serviceId;} public Long getBuildTaskId(){return buildTaskId;}
  public String getVersion(){return version;} public String getBranch(){return branch;} public String getCommitId(){return commitId;}
  public String getJarPath(){return jarPath;} public void setJarPath(String v){jarPath=v;}
  public String getImageUrl(){return imageUrl;} public String getImageDigest(){return imageDigest;} public void setImageDigest(String v){imageDigest=v;}
  public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
}
