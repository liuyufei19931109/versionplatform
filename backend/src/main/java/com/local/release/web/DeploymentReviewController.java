package com.local.release.web;

import com.local.release.service.DeploymentReview;
import com.local.release.service.ReleaseExecutor;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** Server deployments only. App delivery has a separate publication confirmation lifecycle. */
@RestController
@RequestMapping("/api/releases")
public class DeploymentReviewController {
  private final ReleaseExecutor executor;
  public DeploymentReviewController(ReleaseExecutor executor) { this.executor = executor; }

  @GetMapping("/artifact-history")
  public List<DeploymentReview.History> history(@RequestParam Long serviceId) {
    return executor.deploymentHistory(serviceId);
  }

  @GetMapping("/preflight")
  public DeploymentReview.Result preflight(@RequestParam Long serviceId, @RequestParam Long artifactId,
      @RequestParam String environment, @RequestParam List<Long> serverIds) {
    if (serverIds.isEmpty()) throw new IllegalArgumentException("请选择发布节点");
    return executor.deploymentReview(serviceId, artifactId, environment, serverIds);
  }
}
