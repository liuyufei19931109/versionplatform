package com.local.release.web;

import com.local.release.service.TaskActivityService;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** A browser-session-only, read-only view of the shared executor. */
@RestController
@RequestMapping("/api/activity")
public class TaskActivityController {
  private final TaskActivityService activity;
  public TaskActivityController(TaskActivityService activity){this.activity=activity;}

  @GetMapping public ResponseEntity<TaskActivityService.Snapshot> get(Authentication authentication){
    if(authentication==null||!authentication.isAuthenticated()||authentication.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN")||a.getAuthority().equals("ROLE_USER")))
      throw new AccessDeniedException("请使用平台登录账号查看任务状态");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(activity.snapshot(authentication.getName()));
  }
}
