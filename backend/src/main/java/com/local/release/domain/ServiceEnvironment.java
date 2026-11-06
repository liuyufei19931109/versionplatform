package com.local.release.domain;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name="service_environment", uniqueConstraints=@UniqueConstraint(name="uk_service_environment", columnNames={"serviceId","environmentName"}))
public class ServiceEnvironment extends BaseEntity {
  @Column(nullable=false) private Long serviceId;
  @Column(nullable=false,length=20) private String environmentName;
  @ElementCollection(fetch=FetchType.EAGER)
  @CollectionTable(name="service_environment_server",joinColumns=@JoinColumn(name="environment_id"))
  @Column(name="server_id",nullable=false)
  private List<Long> serverIds=new ArrayList<>();
  @Column(nullable=false) private int hostPort=8080;
  @Column(nullable=false) private int containerPort=8080;
  @Column(length=120) private String containerName;
  @Column(length=500) private String envFilePath;
  @Column(length=120) private String dockerNetwork;
  @Column(nullable=false,length=30,columnDefinition="varchar(30) default 'UNLESS_STOPPED'") private String restartPolicy="UNLESS_STOPPED";
  @Column(nullable=false,length=20,columnDefinition="varchar(20) default 'DOCKER_COMPOSE'") private String deploymentMode="DOCKER_COMPOSE";
  @Column(length=120) private String composeService;
  @Column(length=1000) private String startCommand;
  @Column(length=500) private String healthCheckUrl;
  @Lob @Column(columnDefinition="TEXT") private String envJson="{}";
  private boolean enabled=true;

  public Long getServiceId(){return serviceId;} public void setServiceId(Long v){serviceId=v;}
  public String getEnvironmentName(){return environmentName;} public void setEnvironmentName(String v){environmentName=v;}
  public List<Long> getServerIds(){return serverIds;} public void setServerIds(List<Long> v){serverIds=v==null?new ArrayList<>():new ArrayList<>(new LinkedHashSet<>(v));}
  public int getHostPort(){return hostPort;} public void setHostPort(int v){hostPort=v;}
  public int getContainerPort(){return containerPort;} public void setContainerPort(int v){containerPort=v;}
  public String getContainerName(){return containerName;} public void setContainerName(String v){containerName=v;}
  public String getEnvFilePath(){return envFilePath;} public void setEnvFilePath(String v){envFilePath=v;}
  public String getDockerNetwork(){return dockerNetwork;} public void setDockerNetwork(String v){dockerNetwork=v;}
  public String getRestartPolicy(){return restartPolicy==null?"UNLESS_STOPPED":restartPolicy;} public void setRestartPolicy(String v){restartPolicy=v==null||v.isBlank()?"UNLESS_STOPPED":v;}
  public String getDeploymentMode(){return deploymentMode==null?"DOCKER_COMPOSE":deploymentMode;} public void setDeploymentMode(String v){deploymentMode=v==null||v.isBlank()?"DOCKER_COMPOSE":v;}
  public String getComposeService(){return composeService;} public void setComposeService(String v){composeService=v;}
  public String getStartCommand(){return startCommand;} public void setStartCommand(String v){startCommand=v;}
  public String getHealthCheckUrl(){return healthCheckUrl;} public void setHealthCheckUrl(String v){healthCheckUrl=v;}
  public String getEnvJson(){return envJson;} public void setEnvJson(String v){envJson=v;}
  public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
}
