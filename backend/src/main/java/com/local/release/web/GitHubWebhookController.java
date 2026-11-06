package com.local.release.web;

import com.local.release.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

@RestController
public class GitHubWebhookController {
  private final GitHubCodeUpdateService updates;
  public GitHubWebhookController(GitHubCodeUpdateService updates){this.updates=updates;}
  @PostMapping("/api/integrations/github/webhook")
  public GitHubCodeUpdateService.Receipt receive(HttpServletRequest request)throws IOException{
    String type=Objects.toString(request.getContentType(),"").toLowerCase(Locale.ROOT);
    if(!type.matches("application/json(?:\\s*;.*)?"))throw new GitHubWebhookException(415,"GITHUB_CONTENT_TYPE","Webhook 必须使用 application/json");
    String encoding=request.getHeader("Content-Encoding");if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new GitHubWebhookException(415,"GITHUB_CONTENT_ENCODING","不支持压缩的 Webhook 请求");
    if(request.getContentLengthLong()>GitHubWebhookSettings.MAX_BODY_BYTES)throw new GitHubWebhookException(413,"GITHUB_BODY_LIMIT","Webhook 请求超过大小限制");
    String delivery=single(request,"X-GitHub-Delivery"),event=single(request,"X-GitHub-Event"),signature=single(request,"X-Hub-Signature-256");
    byte[] body=request.getInputStream().readNBytes(GitHubWebhookSettings.MAX_BODY_BYTES+1);
    if(body.length>GitHubWebhookSettings.MAX_BODY_BYTES)throw new GitHubWebhookException(413,"GITHUB_BODY_LIMIT","Webhook 请求超过大小限制");
    return updates.receive(delivery,event,signature,body);
  }
  private static String single(HttpServletRequest request,String name){List<String> values=Collections.list(request.getHeaders(name));if(values.size()!=1||values.getFirst().length()>120)throw new GitHubWebhookException(400,"GITHUB_HEADER_INVALID","Webhook 请求头缺失或重复");return values.getFirst();}
  @ExceptionHandler(GitHubWebhookException.class) ResponseEntity<?> invalid(GitHubWebhookException failure){return ResponseEntity.status(failure.status()).header("Cache-Control","no-store").body(Map.of("code",failure.code(),"message",failure.getMessage()));}
}
