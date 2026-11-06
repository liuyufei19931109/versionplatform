package com.local.release.web;

import com.local.release.service.VersionManagementService;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

@RestController @RequestMapping("/api/versions")
public class VersionController {
  private final VersionManagementService versions;
  public VersionController(VersionManagementService versions){this.versions=versions;}
  public record PreviewRequest(List<Long> serviceIds,Long versionId,LocalDate versionDate) {
    public PreviewRequest(List<Long> serviceIds,Long versionId){this(serviceIds,versionId,null);}
  }
  public record CreateRequest(String previewId) {}
  @GetMapping public List<VersionManagementService.View> list(Principal principal){return versions.list(principal.getName());}
  @GetMapping("/latest") public VersionManagementService.View latest(Principal principal){return versions.latest(principal.getName());}
  @GetMapping("/{id}") public VersionManagementService.View detail(@PathVariable Long id,Principal principal){return versions.detail(id,principal.getName());}
  @GetMapping("/candidates") public List<VersionManagementService.Candidate> candidates(Principal principal){return versions.candidates(principal.getName());}
  @PostMapping("/preview") public VersionManagementService.Preview preview(@RequestBody PreviewRequest body,Principal principal)throws Exception{return versions.preview(body.serviceIds(),body.versionId(),body.versionDate(),principal.getName());}
  @PostMapping public VersionManagementService.View create(@RequestBody CreateRequest body,Principal principal){return versions.create(body.previewId(),principal.getName());}
  @PostMapping("/{id}/services/{serviceId}/link-preview") public VersionManagementService.LinkPreview previewLink(@PathVariable Long id,@PathVariable Long serviceId,Principal principal)throws Exception{return versions.previewLink(id,serviceId,principal.getName());}
  @PostMapping("/{id}/link-existing") public VersionManagementService.View linkExisting(@PathVariable Long id,@RequestBody CreateRequest body,Principal principal)throws Exception{return versions.linkExisting(id,body.previewId(),principal.getName());}
}
