package com.local.release.web;

import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.security.TotpService;
import com.local.release.service.ReleaseExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping({"/api/releases","/api/apps/releases"})
public class ReleaseController {
  private final ReleaseTaskRepository tasks;
  private final ReleaseStepRepository steps;
  private final ReleaseExecutor executor;
  private final TotpService totp;
  private final AppServiceRepository services;

  public ReleaseController(ReleaseTaskRepository tasks, ReleaseStepRepository steps, ReleaseExecutor executor, TotpService totp, AppServiceRepository services) {
    this.tasks = tasks; this.steps = steps; this.executor = executor; this.totp = totp; this.services = services;
  }

  public record CreateRelease(@NotNull Long serviceId, @NotNull Long artifactId, String environment,
    List<Long> serverIds, @Size(max = 500) String releaseNote, Boolean simulate, String totpCode,
    @NotBlank @Size(max = 80) String requestId, @Size(max = 64) String confirmationToken) {
    public CreateRelease(Long serviceId, Long artifactId, String environment, List<Long> serverIds,
        String releaseNote, Boolean simulate, String totpCode, String requestId) {
      this(serviceId,artifactId,environment,serverIds,releaseNote,simulate,totpCode,requestId,null);
    }
  }
  public record RetryRequest(String totpCode, @NotBlank @Size(max = 80) String requestId) {}
  public record ReleaseDetail(ReleaseTaskView task, List<ReleaseStep> steps) {}
  public record ProductionApproval(String approver, boolean enabled, Instant serverTime,
    Instant nextCodeAvailableAt, long retryAfterSeconds) {}
  /** Latest successful pre-production and production deployment of one artifact; either one makes it eligible for production. */
  public record ProductionReadiness(Long artifactId, Long preTaskId, LocalDateTime preVerifiedAt, String preVerifiedBy,
    Long prodTaskId, LocalDateTime prodReleasedAt) {}

  @GetMapping
  public List<ReleaseTaskView> list(@RequestParam(required = false) Long serviceId) {
    List<ReleaseTask> result = serviceId == null ? tasks.findByTaskKindOrderByCreatedAtDesc(ModelEnums.TaskKind.DEPLOY)
      : tasks.findByServiceIdAndTaskKindOrderByCreatedAtDesc(serviceId, ModelEnums.TaskKind.DEPLOY);
    return ReleaseTaskView.list(result, services);
  }

  @GetMapping("/production-approval")
  public ProductionApproval productionApproval(Principal principal) {
    String approver = principal.getName();
    TotpService.Availability availability = totp.availability(approver);
    return new ProductionApproval(approver, availability.enabled(), availability.serverTime(),
      availability.nextCodeAvailableAt(), availability.retryAfterSeconds());
  }

  @GetMapping("/production-readiness")
  public List<ProductionReadiness> productionReadiness(@RequestParam Long serviceId) {
    Map<Long, ReleaseTask> pre = new HashMap<>(), prod = new HashMap<>();
    for (ReleaseTask task : tasks.findByServiceIdAndTaskKindAndStatusAndSimulateFalseAndEnvironmentNameIn(serviceId,
        ModelEnums.TaskKind.DEPLOY, ModelEnums.TaskStatus.SUCCESS, ReleaseExecutor.PRODUCTION_VERIFIED_ENVIRONMENTS)) {
      if (task.getArtifactId() == null || "MOBILE".equals(task.getDeliveryType())) continue;
      ("prod".equals(task.getEnvironmentName()) ? prod : pre).merge(task.getArtifactId(), task, ReleaseController::later);
    }
    Set<Long> ids = new TreeSet<>(Comparator.reverseOrder());
    ids.addAll(pre.keySet()); ids.addAll(prod.keySet());
    List<ProductionReadiness> result = new ArrayList<>();
    for (Long id : ids) {
      ReleaseTask p = pre.get(id), r = prod.get(id);
      result.add(new ProductionReadiness(id, p == null ? null : p.getId(), p == null ? null : p.getFinishedAt(),
        p == null ? null : p.getCreatedBy(), r == null ? null : r.getId(), r == null ? null : r.getFinishedAt()));
    }
    return result;
  }

  private static ReleaseTask later(ReleaseTask a, ReleaseTask b) {
    LocalDateTime x = a.getFinishedAt(), y = b.getFinishedAt();
    if (x == null) return b;
    if (y == null) return a;
    return y.isAfter(x) ? b : a;
  }

  @PostMapping
  public synchronized ReleaseTask create(@Valid @RequestBody CreateRelease request, Principal principal) {
    if (Boolean.TRUE.equals(request.simulate())) throw new IllegalArgumentException("模拟发布已停用，请创建正式发版任务");
    return executor.withSubmissionLock(() -> {
      ReleaseTask existing = executor.existingDeployment(request.serviceId(), request.artifactId(), request.environment(),
        request.serverIds(), request.releaseNote(), request.requestId(), principal.getName());
      if (existing != null) return existing;
      executor.requireDeploymentReview(request.serviceId(),request.artifactId(),request.environment(),request.serverIds(),request.confirmationToken());
      executor.precheckDeployment(request.serviceId(), request.artifactId(), request.environment(), request.serverIds());
      if (isProduction(request.environment())) totp.verifyAndConsume(principal.getName(), request.totpCode());
      return executor.createDeployment(request.serviceId(), request.artifactId(), request.environment(), request.serverIds(),
        request.releaseNote(), false, principal.getName(), request.requestId());
    });
  }

  @GetMapping("/{id}")
  public ReleaseDetail detail(@PathVariable Long id) {
    return new ReleaseDetail(ReleaseTaskView.one(findDeployment(id), services), steps.findByReleaseTaskIdOrderById(id));
  }

  @GetMapping("/{id}/steps")
  public List<ReleaseStep> taskSteps(@PathVariable Long id) {
    findDeployment(id);
    return steps.findByReleaseTaskIdOrderById(id);
  }

  @PostMapping("/{id}/retry")
  public synchronized Map<String, String> retry(@PathVariable Long id, @Valid @RequestBody RetryRequest request, Principal principal) {
    return executor.withSubmissionLock(() -> {
      ReleaseTask task = findDeployment(id);
      if (executor.existingDeploymentRetry(id, request.requestId(), principal.getName()))
        return Map.of("message", "该重试请求已处理，请查看当前任务状态");
      executor.precheckDeploymentRetry(id);
      if (isProduction(task.getEnvironmentName())) totp.verifyAndConsume(principal.getName(), request.totpCode());
      executor.retryDeployment(id, request.requestId(), principal.getName());
      return Map.of("message", "发版任务已重新加入执行队列");
    });
  }

  @PostMapping("/{id}/cancel")
  public synchronized Map<String, String> cancel(@PathVariable Long id,Principal principal) {
    ReleaseTask task=findDeployment(id);
    if(principal==null||(!"admin".equalsIgnoreCase(principal.getName())&&!Objects.equals(task.getCreatedBy(),principal.getName())))
      throw new org.springframework.security.access.AccessDeniedException("仅发起人或管理员可以取消发版任务");
    boolean running=task.getStatus()==ModelEnums.TaskStatus.RUNNING;
    executor.cancelDeployment(id);
    return Map.of("message",running?"已请求停止后续步骤，正在清理连接；请等待任务结束并核对远端状态":"排队中的发版任务已取消");
  }

  private ReleaseTask findDeployment(Long id) {
    ReleaseTask task = tasks.findById(id).orElseThrow(() -> new IllegalArgumentException("发版任务不存在"));
    if (task.getTaskKind() != ModelEnums.TaskKind.DEPLOY) throw new IllegalArgumentException("不是发版任务");
    return task;
  }

  private boolean isProduction(String environment) {
    String value = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    return Set.of("prod", "production", "生产").contains(value);
  }
}
