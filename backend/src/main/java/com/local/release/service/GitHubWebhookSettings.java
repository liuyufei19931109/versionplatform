package com.local.release.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
public class GitHubWebhookSettings {
  public static final int MAX_BODY_BYTES=2*1024*1024;
  private final boolean enabled;
  private final byte[] secret;
  private final String owner;
  public GitHubWebhookSettings(@Value("${release.github.webhook-enabled:false}") boolean enabled,@Value("${release.github.webhook-secret:}") String secret,@Value("${release.github.owner:}") String owner){
    this.owner=Objects.toString(owner,"").toLowerCase(Locale.ROOT);
    String value=Objects.toString(secret,"");this.secret=value.getBytes(StandardCharsets.UTF_8);
    this.enabled=enabled&&!value.isBlank()&&value.chars().noneMatch(Character::isISOControl)&&value.length()>=32&&value.length()<=4096&&this.owner.matches("[a-z0-9][a-z0-9-]{0,38}");
  }
  public boolean enabled(){return enabled;} public String owner(){return owner;}
  public void verify(String signature,byte[] body){
    if(!enabled)throw new GitHubWebhookException(503,"GITHUB_WEBHOOK_DISABLED","GitHub 联动尚未配置或未启用");
    if(body==null||body.length==0||body.length>MAX_BODY_BYTES)throw new GitHubWebhookException(413,"GITHUB_BODY_LIMIT","请求内容为空或超过大小限制");
    if(signature==null||!signature.matches("sha256=[a-fA-F0-9]{64}"))throw new GitHubWebhookException(401,"GITHUB_SIGNATURE_INVALID","Webhook 签名校验失败");
    try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));if(!MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature.substring(7))))throw new GitHubWebhookException(401,"GITHUB_SIGNATURE_INVALID","Webhook 签名校验失败");}
    catch(GitHubWebhookException failure){throw failure;}catch(Exception error){throw new GitHubWebhookException(503,"GITHUB_SIGNATURE_UNAVAILABLE","Webhook 签名校验暂不可用");}
  }
}
