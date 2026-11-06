package com.local.release.miniprogram;

import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController @RequestMapping("/api/miniprograms")
public class MiniProgramController {
  private final MiniProgramService service;
  private final MiniProgramSettings settings;
  public MiniProgramController(MiniProgramService service,MiniProgramSettings settings){this.service=service;this.settings=settings;}
  private String actor(Principal principal){
    if(!settings.enabled())throw new Disabled();
    if(!(principal instanceof Authentication auth)||!auth.isAuthenticated()||auth.getAuthorities().stream().noneMatch(r->Set.of("ROLE_ADMIN","ROLE_USER").contains(r.getAuthority())))throw new AccessDeniedException("请使用平台登录账号");
    return principal.getName();
  }
  @GetMapping public List<MiniProgramProject> projects(Principal p){return service.projects(actor(p));}
  @PostMapping public MiniProgramProject create(@RequestBody MiniProgramConfiguration config,Principal p){return service.save(null,config,actor(p));}
  @PutMapping("/{id}") public MiniProgramProject update(@PathVariable Long id,@RequestBody MiniProgramConfiguration config,Principal p){return service.save(id,config,actor(p));}
  @GetMapping("/{id}/branches") public List<String> branches(@PathVariable Long id,Principal p)throws Exception{return service.branches(id,actor(p));}
  @GetMapping("/{id}/commit") public MiniProgramGit.Commit commit(@PathVariable Long id,@RequestParam String branch,Principal p)throws Exception{return service.latest(id,branch,actor(p));}
  @GetMapping("/{id}/jobs") public List<MiniProgramService.JobSummary> jobs(@PathVariable Long id,Principal p){return service.list(id,actor(p));}
  @GetMapping("/{id}/readiness") public MiniProgramReadiness.Report readiness(@PathVariable Long id,Principal p)throws Exception{return service.readiness(id,actor(p));}
  @GetMapping("/{id}/history") public MiniProgramService.History history(@PathVariable Long id,@RequestParam(required=false) MiniProgramJob.Action action,
      @RequestParam(required=false) MiniProgramJob.Status status,@RequestParam(defaultValue="false") boolean unresolved,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,Principal p){return service.history(id,action,status,unresolved,page,size,actor(p));}
  @PostMapping("/jobs") public MiniProgramJob submit(@RequestBody MiniProgramService.Request request,Principal p)throws Exception{return service.create(request,actor(p));}
  @GetMapping("/jobs/{id}") public MiniProgramJob detail(@PathVariable Long id,Principal p){return service.detail(id,actor(p));}
  @PostMapping("/jobs/{id}/cancel") public MiniProgramJob cancel(@PathVariable Long id,Principal p){return service.cancel(id,actor(p));}
  @PostMapping("/jobs/{id}/reconciliation") public MiniProgramJob reconcile(@PathVariable Long id,@RequestBody MiniProgramService.Reconciliation input,Principal p){return service.reconcile(id,input,actor(p));}
  @GetMapping("/jobs/{id}/preview") public ResponseEntity<byte[]> preview(@PathVariable Long id,Principal p)throws Exception{
    byte[] image=service.preview(id,actor(p));
    return ResponseEntity.ok().contentType(image[0]==(byte)255?MediaType.IMAGE_JPEG:MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(image);
  }
  static class Disabled extends RuntimeException {}
  @ExceptionHandler(Disabled.class) ResponseEntity<?> disabled(){return ResponseEntity.status(404).body(Map.of("code","FEATURE_DISABLED","message","小程序发布尚未启用"));}
  @ExceptionHandler(MiniProgramService.Conflict.class) ResponseEntity<?> conflict(MiniProgramService.Conflict e){return ResponseEntity.status(409).body(Map.of("code","MINIPROGRAM_CONFLICT","message",e.getMessage()));}
}
