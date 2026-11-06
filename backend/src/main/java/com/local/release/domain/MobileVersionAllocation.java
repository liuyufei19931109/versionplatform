package com.local.release.domain;

import jakarta.persistence.*;

@Entity @Table(name="mobile_version_allocation",uniqueConstraints={@UniqueConstraint(columnNames="build_task_id"),@UniqueConstraint(columnNames={"application_id","version_code"})},indexes=@Index(columnList="service_id,environment_name"))
public class MobileVersionAllocation extends BaseEntity {
  @Column(nullable=false) private Long serviceId;
  @Column(unique=true) private Long buildTaskId;
  @Column(nullable=false,length=255) private String applicationId;
  @Column(nullable=false,length=20) private String environmentName;
  @Column(nullable=false,length=120) private String versionName;
  @Column(nullable=false,length=100) private String baseVersion;
  @Column(nullable=false) private long versionCode;
  private Long preBuildTaskId;
  @Column(nullable=false,length=120) private String branch;
  @Column(nullable=false,length=64) private String commitId;
  @Column(nullable=false,length=64) private String createdBy;
  protected MobileVersionAllocation(){}
  public MobileVersionAllocation(Long serviceId,String app,String env,String name,String base,long code,Long pre,String branch,String commit,String operator){this.serviceId=serviceId;applicationId=app;environmentName=env;versionName=name;baseVersion=base;versionCode=code;preBuildTaskId=pre;this.branch=branch;commitId=commit;createdBy=operator;}
  public void bind(long taskId){if(buildTaskId!=null&&buildTaskId!=taskId)throw new IllegalStateException("版本分配不能改绑任务");buildTaskId=taskId;}
  public Long getServiceId(){return serviceId;} public Long getBuildTaskId(){return buildTaskId;}
  public String getApplicationId(){return applicationId;} public String getEnvironmentName(){return environmentName;}
  public String getVersionName(){return versionName;} public String getBaseVersion(){return baseVersion;}
  public long getVersionCode(){return versionCode;} public Long getPreBuildTaskId(){return preBuildTaskId;}
  public String getBranch(){return branch;} public String getCommitId(){return commitId;} public String getCreatedBy(){return createdBy;}
}
