package com.local.release.web;

import com.local.release.domain.AdminUser;
import com.local.release.repo.AdminUserRepository;
import com.local.release.security.MenuPermissions;
import com.local.release.security.AppApiScope;
import com.local.release.service.WecomNotificationService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
public class WecomNotificationController {
  private final WecomNotificationService service;private final AdminUserRepository users;
  public WecomNotificationController(WecomNotificationService service,AdminUserRepository users){this.service=service;this.users=users;}
  @GetMapping("/api/settings/notifications/wecom") public WecomNotificationService.SettingsView get(Principal principal){admin(principal);return service.getSettings();}
  @PutMapping("/api/settings/notifications/wecom") public WecomNotificationService.SettingsView save(@RequestBody WecomNotificationService.SettingsRequest request,Principal principal){admin(principal);return service.saveSettings(request);}
  @PostMapping("/api/settings/notifications/wecom/test") public Map<String,Object> test(Principal principal){admin(principal);return Map.of("notification",service.enqueueTest(),"message","测试通知已加入发送队列，请到企业微信群查看");}
  @GetMapping({"/api/releases/{taskId}/notifications","/api/apps/releases/{taskId}/notifications"}) public List<WecomNotificationService.NotificationView> list(@PathVariable Long taskId,Principal principal){permission(principal,false);return service.list(taskId);}
  @PostMapping({"/api/releases/{taskId}/notifications/{notificationId}/retry","/api/apps/releases/{taskId}/notifications/{notificationId}/retry"}) public WecomNotificationService.NotificationView retry(@PathVariable Long taskId,@PathVariable Long notificationId,Principal principal){permission(principal,true);return service.retry(taskId,notificationId);}
  private AdminUser user(Principal p){if(p==null)throw new AccessDeniedException("未登录");return users.findByUsername(p.getName()).filter(AdminUser::isEnabled).orElseThrow(()->new AccessDeniedException("账号不可用"));}
  private void admin(Principal p){if(!user(p).isAdmin())throw new AccessDeniedException("仅管理员可配置群通知");}
  private void permission(Principal p,boolean write){AdminUser u=user(p);Set<String> grants=MenuPermissions.effective(u.menuPermissionSet());if(!u.isAdmin()&&(AppApiScope.appRequest()?!grants.contains("apps"):!grants.contains("release")&&(write||!grants.contains("releases"))))throw new AccessDeniedException("无发版权限");}
}
