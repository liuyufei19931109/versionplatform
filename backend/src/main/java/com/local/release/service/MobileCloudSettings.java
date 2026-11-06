package com.local.release.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Locale;

@Component
public class MobileCloudSettings {
  @Value("${release.mobile.build-engine:local}") String buildEngine="local";
  @Value("${release.mobile.github.repository:}") String repository="";
  @Value("${release.mobile.github.workflow:release-mobile-build.yml}") String workflow="release-mobile-build.yml";
  @Value("${release.mobile.github.ref:main}") String ref="main";
  @Value("${release.mobile.github.token-file:}") String tokenFile="";
  @Value("${release.mobile.github.total-timeout-seconds:3600}") int totalTimeoutSeconds=3600;
  @Value("${release.mobile.github.poll-seconds:10}") int pollSeconds=10;
  @PostConstruct void validate(){
    if ("LOCAL".equals(engine())) return;
    if(!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||!workflow.matches("[A-Za-z0-9_.-]+\\.ya?ml")||!ref.matches("[A-Za-z0-9_./-]+")||ref.startsWith("-")||ref.contains(".."))throw new IllegalArgumentException("GitHub 云构建仓库或工作流配置无效");
    if(totalTimeoutSeconds<300||totalTimeoutSeconds>10800||pollSeconds<2||pollSeconds>60)throw new IllegalArgumentException("GitHub 云构建超时或轮询间隔无效");
  }
  public String engine(){String value=buildEngine.trim().toUpperCase(Locale.ROOT);if(!value.equals("LOCAL")&&!value.equals("GITHUB"))throw new IllegalArgumentException("RELEASE_MOBILE_BUILD_ENGINE 只能是 local 或 github");return value;}
}
