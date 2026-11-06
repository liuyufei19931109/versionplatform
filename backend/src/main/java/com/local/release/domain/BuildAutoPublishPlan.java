package com.local.release.domain;

import jakarta.persistence.*;
import java.util.*;

/** Only newly submitted explicit AUTO choices enter this table. Historical browser intents never do. */
@Entity
@Table(name="build_auto_publish_plan", uniqueConstraints={
  @UniqueConstraint(name="uk_auto_publish_build",columnNames="buildTaskId"),
  @UniqueConstraint(name="uk_auto_publish_deploy",columnNames="deploymentTaskId")})
public class BuildAutoPublishPlan extends BaseEntity {
  @Column(nullable=false,updatable=false) private Long buildTaskId;
  @Column(nullable=false,length=20,updatable=false) private String environmentName;
  @Column(nullable=false,length=2000,updatable=false) private String serverIds;
  @Column(nullable=false,length=64,updatable=false) private String actor;
  @Column(nullable=false,length=30) private String status="WAITING_BUILD";
  private Long deploymentTaskId;
  @Column(length=500) private String message;
  protected BuildAutoPublishPlan(){}
  public BuildAutoPublishPlan(Long buildTaskId,String environment,List<Long> ids,String actor){
    this.buildTaskId=buildTaskId;this.environmentName=environment;this.actor=actor;
    this.serverIds=ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
  }
  public Long getBuildTaskId(){return buildTaskId;}
  public String getEnvironmentName(){return environmentName;}
  public List<Long> serverIdList(){return Arrays.stream(serverIds.split(",")).map(Long::valueOf).toList();}
  public String getActor(){return actor;}
  public String getStatus(){return status;}
  public Long getDeploymentTaskId(){return deploymentTaskId;}
  public String getMessage(){return message;}
  public boolean pending(){return Set.of("WAITING_BUILD","READY").contains(status)&&deploymentTaskId==null;}
  public void waitForEarlierBuild(){status="READY";message="打包已成功，等待同一服务器较早提交的构建结束后按顺序发布";}
  public void ready(){status="READY";message="打包已成功，等待加入发布队列";}
  public void linked(Long id){deploymentTaskId=id;status="QUEUED";message="已自动创建发布任务 #"+id;}
  public void linkedToExisting(Long id){deploymentTaskId=id;status="LINKED_EXISTING";message="已关联相同制品与节点的发布任务 #"+id;}
  public void stop(String state,String reason){status=state;message=reason==null?null:reason.substring(0,Math.min(500,reason.length()));}
}
