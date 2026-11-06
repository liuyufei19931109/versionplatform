package com.local.release.web;

import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.security.MenuPermissions;
import com.local.release.security.AppApiScope;
import com.local.release.service.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
public class BranchPolicyController {
  private final BranchPolicyService branches;private final ProductionMergeService merges;private final AdminUserRepository users;private final ReleaseTaskRepository tasks;
  public BranchPolicyController(BranchPolicyService branches,ProductionMergeService merges,AdminUserRepository users,ReleaseTaskRepository tasks){this.branches=branches;this.merges=merges;this.users=users;this.tasks=tasks;}
  @GetMapping({"/api/tasks/{taskId}/branch-checks","/api/apps/tasks/{taskId}/branch-checks"}) public List<BranchCheck> checks(@PathVariable Long taskId,Principal principal){
    permission(principal,"builds","release","releases");ReleaseTask task=tasks.findById(taskId).orElseThrow(()->new IllegalArgumentException("打包任务不存在"));
    if(task.getTaskKind()!=ModelEnums.TaskKind.BUILD)throw new IllegalArgumentException("此记录不是打包任务");return branches.list(taskId);
  }
  @GetMapping({"/api/releases/{taskId}/merge","/api/apps/releases/{taskId}/merge"}) public ProductionMergeService.MergeView get(@PathVariable Long taskId,Principal principal){permission(principal,"release","releases");return merges.get(taskId);}
  @PostMapping({"/api/releases/{taskId}/merge/retry","/api/apps/releases/{taskId}/merge/retry"}) public ProductionMergeService.MergeView retry(@PathVariable Long taskId,Principal principal){permission(principal,"release");return merges.retry(taskId);}
  private void permission(Principal principal,String... menus){
    if(principal==null)throw new AccessDeniedException("未登录");AdminUser user=users.findByUsername(principal.getName()).filter(AdminUser::isEnabled).orElseThrow(()->new AccessDeniedException("账号不可用"));
    if(user.isAdmin())return;Set<String> allowed=MenuPermissions.effective(user.menuPermissionSet());if(AppApiScope.appRequest()?!allowed.contains("apps"):Arrays.stream(menus).noneMatch(allowed::contains))throw new AccessDeniedException("没有此操作权限");
  }
}
