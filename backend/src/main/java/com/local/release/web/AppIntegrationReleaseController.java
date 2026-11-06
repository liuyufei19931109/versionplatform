package com.local.release.web;

import com.local.release.service.AppIntegrationAccessService;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/mobile/apps/{serviceId}/releases")
public class AppIntegrationReleaseController {
  private final AppIntegrationAccessService service;
  public AppIntegrationReleaseController(AppIntegrationAccessService service){this.service=service;}
  @GetMapping public ResponseEntity<AppIntegrationAccessService.ReleasePage> list(@PathVariable Long serviceId,
      @RequestParam(required=false) String environmentName,@RequestParam(required=false) Long beforeId,
      @RequestParam(defaultValue="20") Integer limit,Authentication authentication){
    if(authentication==null||!authentication.isAuthenticated()||!(authentication.getPrincipal() instanceof AppIntegrationAccessService.TokenPrincipal principal)
        ||principal.serviceId()!=serviceId||authentication.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_APP_INTEGRATION")))
      throw new AccessDeniedException("请使用此 App 的对接凭据访问版本列表");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.listReleases(principal,environmentName,beforeId,limit));
  }
}
