package com.local.release.web;

import com.local.release.service.GitHubEventService;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/github/events")
public class GitHubEventController {
  private final GitHubEventService events;
  public GitHubEventController(GitHubEventService events){this.events=events;}
  public record ReadRequest(Long throughEventId,java.util.List<Long> eventIds){}
  @GetMapping public ResponseEntity<GitHubEventService.Feed> list(Authentication authentication){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(events.feed(operator(authentication)));}
  @PostMapping("/read") public ResponseEntity<Void> read(@RequestBody ReadRequest body,Authentication authentication){events.markRead(operator(authentication),body.throughEventId(),body.eventIds());return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
  private static String operator(Authentication authentication){
    if(authentication==null||!authentication.isAuthenticated()||authentication.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN")||a.getAuthority().equals("ROLE_USER")))throw new AccessDeniedException("请使用平台登录账号查看 Git 动态");
    return authentication.getName();
  }
}
