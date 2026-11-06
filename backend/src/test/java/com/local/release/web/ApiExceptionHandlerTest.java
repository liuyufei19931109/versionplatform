package com.local.release.web;

import com.local.release.security.TotpChallengeException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class ApiExceptionHandlerTest {
  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void remoteCommandFailuresReturnUsefulCategoryWithoutRawSecretsOrOutput() {
    var response = handler.failure(new IllegalStateException("SSH failed: password=do-not-expose\npermission denied\n-----BEGIN " + "PRIVATE KEY-----"));
    assertThat(response.getBody().toString()).contains("REMOTE_PERMISSION_DENIED", "权限不足")
      .doesNotContain("do-not-expose", "PRIVATE KEY", "SSH failed");
  }

  @Test
  void unexpectedErrorsHaveSafeReferenceAndInvalidArgumentsMaskCredentials() {
    assertThat(handler.failure(new RuntimeException("secret=private-value")).getBody().toString())
      .contains("reference", "INTERNAL_ERROR").doesNotContain("private-value");
    assertThat(handler.bad(new IllegalArgumentException("password=private-value")).getBody().toString())
      .doesNotContain("private-value");
  }

  @Test
  void composeFailureKeepsSshSuccessContextWithoutEchoingConfiguration() {
    var response = handler.failure(new IllegalStateException("SSH 已连接；Compose 配置检查失败：open /project/.env permission denied\nDB_PASSWORD=private-value"));
    assertThat(response.getBody().toString()).contains("SSH 已连接", "Compose 配置检查失败", "文件或目录权限不足")
      .doesNotContain("private-value", "/project/.env");
  }

  @Test
  void publicKeyRejectionIsAuthenticationFailureInsteadOfFilePermissionFailure() {
    var response = handler.failure(new IllegalStateException("读取 Git 仓库失败，请检查仓库地址及授权：Permission denied (publickey)."));
    assertThat(response.getBody().toString()).contains("REMOTE_AUTH_FAILED", "远程认证失败")
      .doesNotContain("文件或目录权限不足");
  }

  @Test
  void replayErrorIncludesAuthoritativeWaitInformation() {
    Instant now = Instant.parse("2026-09-05T08:00:10Z");
    var response = handler.totp(new TotpChallengeException("TOTP_REUSED", "请等待新码", now, now.plusSeconds(20)));
    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody().toString()).contains("retryAfterSeconds=20", "nextCodeAvailableAt");
  }
}
