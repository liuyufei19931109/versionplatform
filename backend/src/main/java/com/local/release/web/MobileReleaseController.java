package com.local.release.web;

import com.local.release.domain.MobileArtifact;
import com.local.release.service.MobileReleaseService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.security.Principal;

@RestController @RequestMapping({"/api/mobile","/api/apps/mobile"})
public class MobileReleaseController {
  private final MobileReleaseService service;
  public MobileReleaseController(MobileReleaseService service){this.service=service;}
  public record Create(@NotNull Long serviceId,@NotNull Long artifactId,@NotBlank String environmentName,
      @Size(max=500) String releaseNote,@NotBlank @Size(max=80) String requestId,String totpCode){}
  public record Confirm(@NotNull Long artifactId,@NotBlank String sha256,@NotNull Boolean success,
      @NotBlank @Size(max=80) String requestId,@Size(max=500) String message){}
  @PostMapping("/releases") public ReleaseTaskView create(@Valid @RequestBody Create r,Principal principal){
    return service.create(new MobileReleaseService.Create(r.serviceId(),r.artifactId(),r.environmentName(),r.releaseNote(),r.requestId(),r.totpCode()),principal.getName());
  }
  @GetMapping("/artifacts/{id}") public MobileArtifact artifact(@PathVariable Long id){return service.metadata(id);}
  @GetMapping("/releases/{id}") public MobileReleaseService.Detail detail(@PathVariable Long id){return service.detail(id);}
  @PostMapping("/releases/{id}/confirm") public MobileReleaseService.Detail confirm(@PathVariable Long id,@Valid @RequestBody Confirm r,Principal principal){
    return service.confirm(id,new MobileReleaseService.Confirm(r.artifactId(),r.sha256(),r.success(),r.requestId(),r.message()),principal.getName());
  }
  @GetMapping("/releases/{id}/download") public void download(@PathVariable Long id,Principal principal,HttpServletResponse response)throws IOException{
    Path file=service.downloadFile(id);MobileArtifact a=service.detail(id).artifact();
    response.setContentType("application/octet-stream");response.setContentLengthLong(a.getSizeBytes());
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(downloadName(a)).build().toString());
    response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");response.setHeader("X-Artifact-SHA256",a.getSha256());
    response.setHeader("X-Content-Type-Options","nosniff");
    try(InputStream input=Files.newInputStream(file)){input.transferTo(response.getOutputStream());response.getOutputStream().flush();}
    // Successful transfer is evidence of retrieval, never evidence of store publication.
    service.downloaded(id,principal.getName());
  }
  @PostMapping("/releases/{id}/access-token") public ResponseEntity<MobileReleaseService.Access> access(@PathVariable Long id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.issueAccess(id));}
  static String downloadName(MobileArtifact artifact){return "app-"+artifact.getArtifactId()+switch(artifact.getFormat()){case "APK" -> ".apk";case "AAB" -> ".aab";default -> throw new IllegalStateException("未知安装包格式");};}
  @DeleteMapping("/releases/{id}/access-token") public void revoke(@PathVariable Long id){service.revokeAccess(id);}
}
