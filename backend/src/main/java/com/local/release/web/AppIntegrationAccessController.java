package com.local.release.web;

import com.local.release.service.AppIntegrationAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/apps/services/{serviceId}/integration-access")
public class AppIntegrationAccessController {
  private final AppIntegrationAccessService service;
  public AppIntegrationAccessController(AppIntegrationAccessService service){this.service=service;}
  public record Issue(@NotBlank @Size(max=100) String name,@NotEmpty @Size(max=3) List<@NotBlank String> environments){}
  @GetMapping public ResponseEntity<AppIntegrationAccessService.Status> status(@PathVariable Long serviceId){
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(serviceId));
  }
  @PostMapping public ResponseEntity<AppIntegrationAccessService.Issued> issue(@PathVariable Long serviceId,@Valid @RequestBody Issue request,Authentication authentication){
    admin(authentication);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.issue(serviceId,request.name(),request.environments(),authentication.getName()));
  }
  @DeleteMapping public ResponseEntity<AppIntegrationAccessService.Status> revoke(@PathVariable Long serviceId,Authentication authentication){
    admin(authentication);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.revoke(serviceId,authentication.getName()));
  }
  private static void admin(Authentication authentication){
    if(authentication==null||!authentication.isAuthenticated()||authentication.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN")))throw new AccessDeniedException("仅管理员可以签发、轮换或撤销 App 对接凭据");
  }
}
