package com.local.release.web;
import com.local.release.service.MobileVersionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.List;
@RestController @RequestMapping("/api/apps/services/{serviceId}")
public class MobileVersionController {
  private final MobileVersionService versions;
  public MobileVersionController(MobileVersionService versions){this.versions=versions;}
  @GetMapping("/build-version-preview") public ResponseEntity<MobileVersionService.Preview> preview(@PathVariable long serviceId,@RequestParam String environmentName,@RequestParam(required=false) String branch,@RequestParam(required=false) String commitId,@RequestParam(required=false) Long preBuildTaskId){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(versions.preview(serviceId,environmentName,branch,commitId,preBuildTaskId));}
  @GetMapping("/pre-build-candidates") public ResponseEntity<List<MobileVersionService.Candidate>> candidates(@PathVariable long serviceId,@RequestParam(required=false) String branch,@RequestParam(required=false) String commitId){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(versions.candidates(serviceId,branch,commitId));}
}
