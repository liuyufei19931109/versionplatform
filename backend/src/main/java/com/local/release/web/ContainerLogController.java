package com.local.release.web;

import com.local.release.service.ContainerLogService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Read-only container logs; covered by the runtime overview permission like the rest of /api/runtime. */
@RestController
@RequestMapping("/api/runtime/logs")
public class ContainerLogController {
  private final ContainerLogService logs;
  public ContainerLogController(ContainerLogService logs) { this.logs = logs; }

  @GetMapping
  public ContainerLogService.Snapshot snapshot(@RequestParam Long environmentId, @RequestParam Long serverId,
      @RequestParam(required = false) String containerId, @RequestParam(required = false) Integer tail,
      @RequestParam(required = false) String since, @RequestParam(required = false) String until,
      @RequestParam(required = false) String source, @RequestParam(required = false) String fileId,
      HttpServletResponse response) throws Exception {
    response.setHeader("Cache-Control", "no-store");
    return logs.snapshot(new ContainerLogService.Request(environmentId, serverId, containerId, tail, since, until, source, fileId));
  }

  /** Server-sent events: header, lines, end. Validation errors are returned as JSON before the stream opens. */
  @GetMapping("/stream")
  public SseEmitter stream(@RequestParam Long environmentId, @RequestParam Long serverId,
      @RequestParam(required = false) String containerId, @RequestParam(required = false) Integer tail,
      @RequestParam(required = false) String since, @RequestParam(required = false) String source,
      @RequestParam(required = false) String fileId, Principal principal, HttpServletResponse response) {
    ContainerLogService.FollowSession session = logs.prepareFollow(
      new ContainerLogService.Request(environmentId, serverId, containerId, tail, since, null, source, fileId), principal == null ? null : principal.getName());
    SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(31));
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Accel-Buffering", "no");
    emitter.onCompletion(session::cancel);
    emitter.onTimeout(session::cancel);
    emitter.onError(error -> session.cancel());
    Thread.ofVirtual().name("container-log-follow").start(() -> {
      try { session.run(new Listener(emitter, session)); }
      finally { try { emitter.complete(); } catch (RuntimeException ignored) {} }
    });
    return emitter;
  }

  private record Listener(SseEmitter emitter, ContainerLogService.FollowSession session) implements ContainerLogService.FollowListener {
    private void send(SseEmitter.SseEventBuilder event) {
      if (session.cancelled()) return;
      // A failed write means the viewer went away; stop the remote command instead of reading into nowhere.
      try { emitter.send(event); } catch (Exception error) { session.cancel(); }
    }
    @Override public void header(ContainerLogService.Header header) { send(SseEmitter.event().name("header").data(header, MediaType.APPLICATION_JSON)); }
    @Override public void lines(List<ContainerLogService.Line> lines, int dropped) {
      send(SseEmitter.event().name("lines").data(Map.of("lines", lines, "dropped", dropped), MediaType.APPLICATION_JSON));
    }
    @Override public void heartbeat() { send(SseEmitter.event().comment("keepalive")); }
    @Override public void end(String reason, String message) {
      send(SseEmitter.event().name("end").data(Map.of("reason", reason, "message", message == null ? "" : message), MediaType.APPLICATION_JSON));
    }
  }
}
