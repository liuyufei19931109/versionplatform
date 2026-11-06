package com.local.release.web;

import com.local.release.service.VersionCompletionService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
public class VersionCompletionController {
  private final VersionCompletionService completions;
  public VersionCompletionController(VersionCompletionService completions){this.completions=completions;}
  @GetMapping("/api/versions/completion-states")
  public java.util.List<VersionCompletionService.CompletionState> states(Principal principal,HttpServletResponse response){return completions.states(actor(principal,response,false));}
  @GetMapping("/api/versions/{id}/completion")
  public VersionCompletionService.Detail detail(@PathVariable Long id,Principal principal,HttpServletResponse response){return completions.detail(id,actor(principal,response,false));}
  @GetMapping("/api/versions/{id}/completion/options")
  public VersionCompletionService.Options options(@PathVariable Long id,@RequestParam(defaultValue="prod") String environment,Principal principal,HttpServletResponse response){return completions.options(id,environment,actor(principal,response,true));}
  @PostMapping("/api/versions/{id}/completion/preview")
  public VersionCompletionService.Preview preview(@PathVariable Long id,@RequestBody VersionCompletionService.Input request,Principal principal,HttpServletResponse response){return completions.preview(id,request,actor(principal,response,true));}
  @PostMapping("/api/versions/{id}/completion")
  public VersionCompletionService.Detail complete(@PathVariable Long id,@RequestBody VersionCompletionService.Confirm request,Principal principal,HttpServletResponse response){return completions.complete(id,request,actor(principal,response,true));}
  @PutMapping("/api/versions/{id}/completion")
  public VersionCompletionService.Detail edit(@PathVariable Long id,@RequestBody VersionCompletionService.Edit request,Principal principal,HttpServletResponse response){return completions.edit(id,request,actor(principal,response,true));}
  @PostMapping("/api/versions/{id}/completion/reopen")
  public VersionCompletionService.Detail reopen(@PathVariable Long id,@RequestBody VersionCompletionService.Reopen request,Principal principal,HttpServletResponse response){return completions.reopen(id,request,actor(principal,response,true));}
  @GetMapping("/api/version-summaries/latest")
  public VersionCompletionService.PublicSummary latest(Principal principal,HttpServletResponse response){return completions.latest(actor(principal,response,false));}
  @GetMapping("/api/version-summaries/{id}")
  public VersionCompletionService.PublicSummary summary(@PathVariable Long id,Principal principal,HttpServletResponse response){return completions.publicDetail(id,actor(principal,response,false));}
  private String actor(Principal principal,HttpServletResponse response,boolean admin){
    response.setHeader("Cache-Control","no-store");
    if(!(principal instanceof Authentication authentication)||!authentication.isAuthenticated()||authentication.getAuthorities().stream().noneMatch(role->role.getAuthority().equals("ROLE_ADMIN")||!admin&&role.getAuthority().equals("ROLE_USER")))throw new AccessDeniedException("仅平台用户可访问版本小结");
    return principal.getName();
  }
}
