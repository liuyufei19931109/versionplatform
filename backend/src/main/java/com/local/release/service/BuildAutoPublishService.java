package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.security.MenuPermissions;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;

@Service
public class BuildAutoPublishService {
  public record Target(String environment,List<Long> serverIds){}
  public record View(String mode,String environment,List<Long> serverIds,String status,Long deploymentTaskId,String message){}
  private final BuildAutoPublishPlanRepository plans;
  private final AdminUserRepository users;
  public BuildAutoPublishService(BuildAutoPublishPlanRepository plans,AdminUserRepository users){this.plans=plans;this.users=users;}

  public Target normalize(Target target){
    if(target==null)return null;
    if(!Set.of("dev","test").contains(String.valueOf(target.environment())))throw new IllegalArgumentException("仅开发和预生产支持打包后自动发布；生产环境仍需当前登录账号的新动态码确认");
    List<Long> ids=target.serverIds();
    if(ids==null||ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(id->id==null||id<=0)||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("自动发布必须明确选择有效且不重复的服务器");
    return new Target(target.environment(),ids.stream().sorted().toList());
  }
  public void authorize(String actor){
    AdminUser user=users.findByUsername(actor).orElseThrow(()->new AccessDeniedException("自动发布发起人不存在"));
    if(!user.isEnabled()||!user.isAdmin()&&!MenuPermissions.effective(user.menuPermissionSet()).contains("release"))throw new AccessDeniedException("发起人已停用或没有发布权限，自动发布已停止");
  }
  public void create(ReleaseTask task,Target target){if(target!=null)plans.save(new BuildAutoPublishPlan(task.getId(),target.environment(),target.serverIds(),task.getCreatedBy()));}
  public List<BuildAutoPublishPlan> pending(){return plans.findByStatusInOrderByIdAsc(List.of("WAITING_BUILD","READY"));}
  public void save(BuildAutoPublishPlan plan){plans.save(plan);}
  public View view(Long buildId){return plans.findByBuildTaskId(buildId).map(plan->new View("AUTO",plan.getEnvironmentName(),plan.serverIdList(),plan.getStatus(),plan.getDeploymentTaskId(),plan.getMessage())).orElse(null);}
  public void stopAfterBuild(ReleaseTask task){
    if(task.getTaskKind()!=TaskKind.BUILD||!Set.of(TaskStatus.FAILED,TaskStatus.CANCELLED).contains(task.getStatus()))return;
    plans.findByBuildTaskId(task.getId()).filter(BuildAutoPublishPlan::pending).ifPresent(plan->{
      plan.stop(task.getStatus()==TaskStatus.CANCELLED?"CANCELLED":"BLOCKED",task.getStatus()==TaskStatus.CANCELLED?"打包已取消，自动发布已停止":"打包失败，自动发布已停止；重试仅打包，成功后请手动发布");plans.save(plan);
    });
  }
  public void validateDeploymentActor(ReleaseTask task){
    // The unique task request commits before the convenience plan link. Cover that recovery gap too.
    if(task.getRequestKey()!=null&&task.getRequestKey().matches("DEPLOY:[^:]+:auto_build_[0-9]+_followup")){authorize(task.getCreatedBy());return;}
    // A manually submitted task can satisfy a later automatic intent. Linking for display
    // must not replace that task's original authorization with the plan owner's identity.
    plans.findByDeploymentTaskId(task.getId()).filter(plan->!"LINKED_EXISTING".equals(plan.getStatus()))
        .ifPresent(plan->authorize(plan.getActor()));
  }
}
