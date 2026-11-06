package com.local.release.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.local.release.domain.AppService;
import com.local.release.domain.ModelEnums.ServiceType;
import java.util.*;

/** An explicit server-side switch; existing tasks keep their original executor. */
@Component
public class FrontendCloudSettings {
  @Value("${release.frontend.build-engine:local}") String buildEngine="local";
  @Value("${release.frontend.service-keys:}") String serviceKeys="";
  @Value("${release.frontend.github.repository:}") String repository="";
  @Value("${release.frontend.github.workflow:release-frontend-build.yml}") String workflow="release-frontend-build.yml";
  @Value("${release.frontend.github.ref:main}") String ref="main";
  @Value("${release.frontend.github.token-file:${release.mobile.github.token-file:}}") String tokenFile="";
  @Value("${release.frontend.github.total-timeout-seconds:1800}") int totalTimeoutSeconds=1800;
  @Value("${release.frontend.github.poll-seconds:10}") int pollSeconds=10;
  @Value("${release.frontend.github.node-version:22}") String nodeVersion="22";
  @Value("${release.frontend.github.heap-mib:6144}") int heapMiB=6144;
  @PostConstruct void validate(){
    if ("LOCAL".equals(engine())) return;
    if(!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||!workflow.matches("[A-Za-z0-9_.-]+\\.ya?ml")||!ref.matches("[A-Za-z0-9_./-]+")||ref.startsWith("-")||ref.contains(".."))throw new IllegalArgumentException("前端 GitHub 仓库或工作流配置无效");
    if(totalTimeoutSeconds<300||totalTimeoutSeconds>7200||pollSeconds<2||pollSeconds>60||heapMiB<1024||heapMiB>6144||!nodeVersion.matches("(?:22|24)(?:\\.[0-9]+\\.[0-9]+)?"))throw new IllegalArgumentException("前端云构建资源或超时配置无效");
    if(keys().isEmpty()||keys().stream().anyMatch(k->!k.equals("*")&&!k.matches("[A-Za-z0-9_-]+")))throw new IllegalArgumentException("前端云构建必须指定服务标识或 *");
  }
  String engine(){String value=buildEngine.trim().toUpperCase(Locale.ROOT);if(!Set.of("LOCAL","GITHUB").contains(value))throw new IllegalArgumentException("RELEASE_FRONTEND_BUILD_ENGINE 只能是 local 或 github");return value;}
  private Set<String> keys(){return new HashSet<>(Arrays.stream(serviceKeys.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList());}
  String engineForNewTask(AppService service){
    if(service.getServiceType()!=ServiceType.FRONTEND||"LOCAL".equals(engine())||"LOCAL".equals(service.getBuildEngine()))return "LOCAL";
    if(!keys().contains("*")&&!keys().contains(service.getServiceKey()))throw new IllegalArgumentException("服务 "+service.getServiceKey()+" 已选择 GitHub 云构建，但未在 RELEASE_FRONTEND_SERVICE_KEYS 中允许；请将服务加入白名单、配置 * 或将服务构建方式改为 LOCAL");
    return "GITHUB";
  }
}
