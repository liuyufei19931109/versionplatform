package com.local.release.web;

import com.local.release.security.TotpService;
import com.local.release.security.TokenService;
import com.local.release.repo.AdminUserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/totp")
public class TotpController {
  private final TotpService totp;
  private final TokenService tokens;
  private final AdminUserRepository users;

  public TotpController(TotpService totp,TokenService tokens,AdminUserRepository users) { this.totp = totp; this.tokens=tokens; this.users=users; }

  public record SetupRequest(String currentCode) {}
  public record CodeRequest(@NotBlank @Pattern(regexp = "\\d{6}", message = "请输入 6 位动态码") String code) {}

  @GetMapping("/status")
  public Map<String, Object> status(Principal principal) {
    TotpService.Availability availability = totp.availability(principal.getName());
    return Map.of("enabled", availability.enabled(), "username", principal.getName(), "issuer", "发版平台",
      "serverTime", availability.serverTime(), "nextCodeAvailableAt", availability.nextCodeAvailableAt(),
      "retryAfterSeconds", availability.retryAfterSeconds());
  }

  @PostMapping("/setup")
  public TotpService.SetupResult setup(@RequestBody(required = false) SetupRequest request, Principal principal) {
    return totp.beginSetup(principal.getName(), request == null ? null : request.currentCode());
  }

  @PostMapping("/confirm")
  public Map<String, Object> confirm(@Valid @RequestBody CodeRequest request, Principal principal) {
    totp.confirmSetup(principal.getName(), request.code());
    users.findByUsername(principal.getName()).ifPresent(user->{user.setLastLoginAt(LocalDateTime.now());users.save(user);});
    return Map.of("enabled", true, "message", "Google Authenticator 绑定成功", "token", tokens.issue(principal.getName()));
  }
}
