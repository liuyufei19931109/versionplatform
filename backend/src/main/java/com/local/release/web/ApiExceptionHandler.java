package com.local.release.web;

import com.local.release.security.TotpChallengeException;
import com.local.release.service.SshExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ServerCheckException.class)
  ResponseEntity<?> serverCheck(ServerCheckException error) {
    SshExecutionException ssh=findSshFailure(error);
    String code=ssh==null?"REMOTE_CHECK_FAILED":ssh.getCode().name();
    String detail=ssh==null?(error.isSshConnected()?"请检查 Compose 文件、Docker 及 sudo 权限":"请检查地址和 SSH 认证配置"):ssh.getMessage();
    return ResponseEntity.status(code.contains("TIMEOUT")?HttpStatus.GATEWAY_TIMEOUT:HttpStatus.BAD_GATEWAY).body(Map.of(
      "code",code,"message",error.getMessage()+"："+detail,
      "sshStatus",error.isSshConnected()?"SUCCESS":"FAILED","composeStatus",error.isSshConnected()?"FAILED":"NOT_RUN"));
  }

  @ExceptionHandler(SshExecutionException.class)
  ResponseEntity<?> sshFailure(SshExecutionException error) {
    return response(error.getCode().name().contains("TIMEOUT")?HttpStatus.GATEWAY_TIMEOUT:HttpStatus.BAD_GATEWAY,error.getCode().name(),error.getMessage());
  }
  private SshExecutionException findSshFailure(Throwable error){
    for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof SshExecutionException ssh)return ssh;
    return null;
  }

  @ExceptionHandler(TotpChallengeException.class)
  ResponseEntity<?> totp(TotpChallengeException error) {
    HttpStatus status = "TOTP_LOCKED".equals(error.getCode()) ? HttpStatus.TOO_MANY_REQUESTS
      : "TOTP_REUSED".equals(error.getCode()) ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
    return ResponseEntity.status(status).body(Map.of(
      "code", error.getCode(), "message", error.getMessage(),
      "serverTime", error.getServerTime(), "nextCodeAvailableAt", error.getNextCodeAvailableAt(),
      "retryAfterSeconds", error.getRetryAfterSeconds()));
  }

  @ExceptionHandler(com.local.release.service.DeploymentReview.Conflict.class)
  ResponseEntity<?> deploymentReview(com.local.release.service.DeploymentReview.Conflict conflict) {
    var review=conflict.result();
    String code=review.action()==com.local.release.service.DeploymentReview.Action.ACTIVE?"DEPLOY_ACTIVE":"DEPLOY_REVIEW_REQUIRED";
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code",code,"message",conflict.getMessage(),"preflight",review));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<?> bad(IllegalArgumentException error) {
    return response(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", safeArgumentMessage(error));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> validation(MethodArgumentNotValidException error) {
    String message = error.getBindingResult().getFieldErrors().stream().findFirst()
      .map(field -> switch (field.getField()) {
        case "requestId" -> "请求标识缺失或不正确，请刷新页面后重新提交";
        case "username", "password" -> "请输入账号和密码";
        case "serviceId" -> "请选择服务";
        case "artifactId" -> "请选择成功的包版本";
        case "code", "totpCode" -> "请输入 6 位动态码";
        default -> "提交参数不完整或格式不正确，请检查表单";
      }).orElse("提交参数不正确，请检查表单");
    return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
  ResponseEntity<?> malformed(Exception error) {
    return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "提交内容格式不正确，请刷新页面后重试");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<?> denied(AccessDeniedException error) {
    return response(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "没有执行当前操作的权限");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<?> unsupportedMethod(org.springframework.web.HttpRequestMethodNotSupportedException error) {
    return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "当前接口不支持此操作");
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database(DataAccessException error) {
    return response(HttpStatus.CONFLICT, "DATA_CONFLICT", "数据正在被其他操作修改或存在重复记录，请刷新后重试");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> failure(Exception error) {
    // Classify command output without echoing credentials or a stack trace to clients.
    String raw = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
    String context = raw.startsWith("ssh 已连接；compose 配置检查失败") ? "SSH 已连接；Compose 配置检查失败："
      : raw.startsWith("ssh 连接失败") ? "SSH 连接失败："
      : raw.startsWith("读取 git 仓库") ? "读取 Git 仓库失败：" : "";
    if (raw.contains("auth fail") || raw.contains("authentication failed") || raw.contains("publickey"))
      return response(HttpStatus.BAD_GATEWAY, "REMOTE_AUTH_FAILED", context + "远程认证失败，请检查配置的账号、密码或 SSH 密钥");
    if (raw.contains("permission denied") || raw.contains("access denied") || raw.contains("not in the sudoers")
        || raw.contains("a password is required") || raw.contains("incorrect password attempt"))
      return response(HttpStatus.BAD_GATEWAY, "REMOTE_PERMISSION_DENIED", context + "文件或目录权限不足，请检查 SSH 用户、配置文件读取权限及 sudo 设置");
    if (raw.contains("timed out") || raw.contains("timeout") || raw.contains("超时"))
      return response(HttpStatus.GATEWAY_TIMEOUT, "OPERATION_TIMEOUT", context + "操作超时，请检查网络及目标服务状态后重试");
    if (raw.contains("connection refused") || raw.contains("no route to host") || raw.contains("unknownhost"))
      return response(HttpStatus.BAD_GATEWAY, "REMOTE_UNREACHABLE", context + "无法连接目标服务，请检查地址、端口及网络设置");
    if (!context.isBlank()) {
      String suggestion = raw.contains("no such file") || raw.contains("cannot find") ? "文件或目录不存在，请检查配置路径"
        : raw.contains("command not found") || raw.contains("docker: not found") ? "目标服务器缺少所需命令，请检查 Docker 与 Compose 安装"
        : raw.contains("repository not found") ? "仓库不存在或当前账号无权访问，请检查地址和仓库授权"
        : context.startsWith("SSH 已连接") ? "请检查 Compose 文件、环境变量与 Docker 服务状态"
        : context.startsWith("读取 Git") ? "请检查仓库地址、网络连接及 SSH 密钥授权"
        : "请检查地址、端口与 SSH 认证配置";
      return response(HttpStatus.BAD_GATEWAY, "REMOTE_CHECK_FAILED", context + suggestion);
    }
    String reference = UUID.randomUUID().toString().substring(0, 8);
    log.warn("API request failed; reference={}, exceptionType={}", reference, error.getClass().getName());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
      "code", "INTERNAL_ERROR", "message", "操作未完成，请稍后重试；如持续出现，请联系管理员", "reference", reference));
  }

  private ResponseEntity<?> response(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
  }

  private String safeArgumentMessage(IllegalArgumentException error) {
    String message = error.getMessage();
    if (message == null || message.isBlank() || error.getClass() != IllegalArgumentException.class)
      return "参数格式不正确，请检查提交内容";
    String first = message.split("[\\r\\n]", 2)[0];
    if (first.contains("-----BEGIN") || first.matches("(?is).*(password|passwd|secret|token|authorization)\\s*[:=].*"))
      return "参数或认证配置不正确，请检查设置";
    first = first.replaceAll("(?i)(https?://)[^\\s/@]+:[^\\s/@]+@", "$1[已隐藏]@");
    return first.length() > 200 ? first.substring(0, 200) + "…" : first;
  }
}
