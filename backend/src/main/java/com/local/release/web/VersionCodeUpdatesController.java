package com.local.release.web;
import com.local.release.service.GitHubCodeUpdateService;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
@RestController
public class VersionCodeUpdatesController {
  private final GitHubCodeUpdateService updates;
  public VersionCodeUpdatesController(GitHubCodeUpdateService updates){this.updates=updates;}
  @GetMapping("/api/versions/{versionId}/code-updates") public GitHubCodeUpdateService.Updates get(@PathVariable Long versionId,Principal principal){return updates.updates(versionId,principal.getName());}
}
