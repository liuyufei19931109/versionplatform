package com.local.release.miniprogram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;

/** Deliberately opt-in: neither an existing deployment nor a new database enables WeChat writes. */
@Component
public class MiniProgramSettings {
  @Value("${release.miniprogram.enabled:false}") boolean enabled;
  @Value("${release.miniprogram.upload-enabled:false}") boolean uploadEnabled;
  @Value("${release.miniprogram.workspace:${release.workspace:./workspaces}/miniprograms}") String workspace;
  @Value("${release.miniprogram.key-root:}") String keyRoot;
  @Value("${release.miniprogram.ci-home:}") String ciHome;
  @Value("${release.miniprogram.node:node}") String node;
  @Value("${release.miniprogram.timeout-seconds:600}") long timeoutSeconds=600;
  public boolean enabled(){return enabled;}
  public boolean uploadEnabled(){return enabled&&uploadEnabled;}
  public void requireEnabled(){if(!enabled)throw new IllegalArgumentException("小程序发布尚未启用");}
  public Path root(){return Path.of(workspace).toAbsolutePath().normalize();}
  public Path key(String appId)throws Exception {
    if(keyRoot==null||keyRoot.isBlank())throw new IllegalArgumentException("尚未配置小程序代码上传密钥目录");
    Path root=Path.of(keyRoot).toRealPath();
    Path key=root.resolve("private."+appId+".key");
    if(!Files.isRegularFile(key,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(key)||!key.toRealPath().startsWith(root))
      throw new IllegalArgumentException("未找到此 AppID 的代码上传密钥，请联系管理员配置");
    return key;
  }
  public Path runner() {
    if(ciHome==null||ciHome.isBlank())throw new IllegalArgumentException("尚未安装小程序 CI 执行工具");
    Path runner=Path.of(ciHome).toAbsolutePath().normalize().resolve("runner.cjs");
    if(!Files.isRegularFile(runner))throw new IllegalArgumentException("小程序 CI 执行工具不存在");
    return runner;
  }
}
