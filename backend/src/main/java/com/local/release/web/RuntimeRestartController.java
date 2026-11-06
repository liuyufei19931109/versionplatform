package com.local.release.web;

import com.local.release.domain.RuntimeOperation;
import com.local.release.service.*;
import com.local.release.security.TotpService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeRestartController {
  private final RuntimeRestartService restarts;
  private final ReleaseExecutor executor;
  private final TotpService totp;
  public RuntimeRestartController(RuntimeRestartService restarts,ReleaseExecutor executor,TotpService totp){this.restarts=restarts;this.executor=executor;this.totp=totp;}
  public record PreviewRequest(@NotNull @Positive Long environmentId,@NotNull @Positive Long serverId) {}
  public record RestartRequest(@NotBlank @Size(max=80) String token,@NotBlank @Size(max=80) String requestId,String totpCode) {}

  @PostMapping("/restart-preview") public RuntimeRestartService.Preview preview(@Valid @RequestBody PreviewRequest request,Principal principal)throws Exception {
    return restarts.preview(request.environmentId(),request.serverId(),principal.getName());
  }
  @PostMapping("/restarts") public RuntimeOperation restart(@Valid @RequestBody RestartRequest request,Principal principal) {
    return executor.withSubmissionLock(()->{
      RuntimeOperation existing=restarts.existing(request.token(),request.requestId(),principal.getName());if(existing!=null)return existing;
      var confirmation=restarts.precheck(request.token(),principal.getName());
      if("prod".equals(confirmation.environmentName()))totp.verifyAndConsume(principal.getName(),request.totpCode());
      return restarts.create(request.token(),request.requestId(),principal.getName());
    });
  }
  @GetMapping("/restarts") public List<RuntimeOperation> history(@RequestParam Long environmentId,@RequestParam Long serverId){return restarts.history(environmentId,serverId);}
  @GetMapping("/restarts/active") public List<RuntimeOperation> active(){return restarts.activeOperations();}
  @GetMapping("/restarts/{id}") public RuntimeOperation detail(@PathVariable Long id){return restarts.detail(id);}
  /** Stopping further work does not initiate a new production mutation or consume another TOTP. */
  @PostMapping("/restarts/{id}/cancel") public Map<String,String> cancel(@PathVariable Long id,Principal principal){
    return Map.of("message",restarts.cancel(id,principal.getName()));
  }
}
