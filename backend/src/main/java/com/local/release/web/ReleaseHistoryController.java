package com.local.release.web;

import com.local.release.service.ReleaseHistoryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/releases/history","/api/apps/releases/history"})
public class ReleaseHistoryController {
  private final ReleaseHistoryService history;
  public ReleaseHistoryController(ReleaseHistoryService history) { this.history = history; }

  @GetMapping
  public ReleaseHistoryView history(@RequestParam(required = false) String environment) {
    return history.history(environment);
  }
}
