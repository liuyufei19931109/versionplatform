package com.local.release.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.local.release.domain.AppService;
import com.local.release.domain.ModelEnums.ServiceType;
import java.util.*;

@Component
public class BackendCloudSettings {
  @Value("${release.backend.build-engine:local}") String buildEngine="local";
  @Value("${release.backend.service-keys:}") String serviceKeys="";
  @Value("${release.backend.github.repository:}") String repository="";
  @Value("${release.backend.github.workflow:release-backend-build.yml}") String workflow="release-backend-build.yml";
  @Value("${release.backend.github.ref:main}") String ref="main";
  @Value("${release.backend.github.token-file:${release.mobile.github.token-file:}}") String tokenFile="";
  @Value("${release.backend.github.total-timeout-seconds:1800}") int totalTimeoutSeconds=1800;
  @Value("${release.backend.github.poll-seconds:10}") int pollSeconds=10;
  @Value("${release.backend.github.java-version:21}") String javaVersion="21";
  @Value("${release.backend.github.heap-mib:2048}") int heapMiB=2048;
  @PostConstruct void validate(){
    if ("LOCAL".equals(engine())) return;
    if(!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||!workflow.matches("[A-Za-z0-9_.-]+\\.ya?ml")||!ref.matches("[A-Za-z0-9_./-]+")||ref.startsWith("-")||ref.contains(".."))throw new IllegalArgumentException("后端 GitHub 仓库或工作流配置无效");
    if(totalTimeoutSeconds<300||totalTimeoutSeconds>7200||pollSeconds<2||pollSeconds>60||heapMiB<512||heapMiB>4096||!Set.of("17","21","25").contains(javaVersion))throw new IllegalArgumentException("后端云构建资源或超时配置无效");
    if(keys().isEmpty()||keys().stream().anyMatch(k->!k.equals("*")&&!k.matches("[A-Za-z0-9_-]+")))throw new IllegalArgumentException("后端云构建必须指定服务标识或 *");
  }
  String engine(){String value=buildEngine.trim().toUpperCase(Locale.ROOT);if(!Set.of("LOCAL","GITHUB").contains(value))throw new IllegalArgumentException("RELEASE_BACKEND_BUILD_ENGINE 只能是 local 或 github");return value;}
  private Set<String> keys(){return new HashSet<>(Arrays.stream(serviceKeys.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList());}
  String engineForNewTask(AppService service){
    if(service.getServiceType()!=ServiceType.BACKEND||"LOCAL".equals(engine())||"LOCAL".equals(service.getBuildEngine()))return "LOCAL";
    if(!keys().contains("*")&&!keys().contains(service.getServiceKey()))throw new IllegalArgumentException("服务 "+service.getServiceKey()+" 已选择 GitHub 云构建，但未在 RELEASE_BACKEND_SERVICE_KEYS 中允许；请将服务加入白名单、配置 * 或将服务构建方式改为 LOCAL");
    return "GITHUB";
  }
}
