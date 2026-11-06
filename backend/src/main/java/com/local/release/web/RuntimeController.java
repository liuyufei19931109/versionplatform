package com.local.release.web;

import com.local.release.service.RuntimeOverviewService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {
  private final RuntimeOverviewService overview;
  public RuntimeController(RuntimeOverviewService overview) { this.overview=overview; }
  public record CheckRequest(Long environmentId, Long serverId) {}
  @GetMapping public RuntimeOverviewService.Snapshot snapshot() { return overview.snapshot(); }
  @PostMapping("/check") public RuntimeOverviewService.Snapshot check(@RequestBody(required=false) CheckRequest request) {
    return overview.requestCheck(request==null?null:request.environmentId(),request==null?null:request.serverId());
  }
}
