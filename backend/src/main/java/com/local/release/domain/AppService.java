package com.local.release.domain;

import jakarta.persistence.*;
import static com.local.release.domain.ModelEnums.ServiceType;

@Entity @Table(name="app_service")
public class AppService extends BaseEntity {
  @Column(nullable=false,length=100) private String name;
  @Column(nullable=false,unique=true,length=100) private String serviceKey;
  @Enumerated(EnumType.STRING) @Column(length=20) private ServiceType serviceType=ServiceType.BACKEND;
  @Column(length=500) private String description;
  @Column(nullable=false) private boolean enabled=true;
  @Column(length=500) private String gitRepo;
  @Column(length=120) private String defaultBranch="main";
  @Column(length=500) private String buildCommand="./mvnw clean package -DskipTests";
  @Column(length=16) private String buildEngine="GITHUB";
  @Column(length=300) private String workDirectory;
  @Column(length=300) private String jarPath="target/*.jar";
  @Column(length=300) private String dockerfilePath="Dockerfile";
  @Column(length=500) private String imageRepository;
  @Column(length=120) private String targetHost;
  @Column(length=80) private String targetUser="root";
  @Column(length=500) private String startCommand;
  @Column(length=500) private String healthCheckUrl;
  @Lob @Column(columnDefinition="TEXT") private String envJson="{}";
  @Lob @Column(columnDefinition="TEXT") private String configJson="{}";
  public String getName(){return name;} public void setName(String v){name=v;}
  public String getServiceKey(){return serviceKey;} public void setServiceKey(String v){serviceKey=v;}
  public ServiceType getServiceType(){return serviceType==null?ServiceType.BACKEND:serviceType;} public void setServiceType(ServiceType v){serviceType=v==null?ServiceType.BACKEND:v;}
  public String getDescription(){return description;} public void setDescription(String v){description=v;}
  public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
  public String getGitRepo(){return gitRepo;} public void setGitRepo(String v){gitRepo=v;}
  public String getDefaultBranch(){return defaultBranch;} public void setDefaultBranch(String v){defaultBranch=v;}
  public String getBuildCommand(){return buildCommand;} public void setBuildCommand(String v){buildCommand=v;}
  public String getBuildEngine(){return buildEngine==null?"GITHUB":buildEngine;}
  public void setBuildEngine(String v){String value=v==null?"":v.trim().toUpperCase(java.util.Locale.ROOT);if(!value.equals("GITHUB")&&!value.equals("LOCAL"))throw new IllegalArgumentException("构建方式只能是 GITHUB 或 LOCAL");buildEngine=value;}
  public String getWorkDirectory(){return workDirectory;} public void setWorkDirectory(String v){workDirectory=v;}
  public String getJarPath(){return jarPath;} public void setJarPath(String v){jarPath=v;}
  public String getDockerfilePath(){return dockerfilePath;} public void setDockerfilePath(String v){dockerfilePath=v;}
  public String getImageRepository(){return imageRepository;} public void setImageRepository(String v){imageRepository=v;}
  public String getTargetHost(){return targetHost;} public void setTargetHost(String v){targetHost=v;}
  public String getTargetUser(){return targetUser;} public void setTargetUser(String v){targetUser=v;}
  public String getStartCommand(){return startCommand;} public void setStartCommand(String v){startCommand=v;}
  public String getHealthCheckUrl(){return healthCheckUrl;} public void setHealthCheckUrl(String v){healthCheckUrl=v;}
  public String getEnvJson(){return envJson;} public void setEnvJson(String v){envJson=v;}
  @Transient public String getDisplayIcon(){return ServiceAppearance.icon(configJson);}
  public String getConfigJson(){return configJson;} public void setConfigJson(String v){configJson=v;}
}
