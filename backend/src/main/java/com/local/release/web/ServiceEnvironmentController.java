package com.local.release.web;

import com.local.release.domain.*;
import com.local.release.repo.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/services/{serviceId}/environments")
public class ServiceEnvironmentController {
  private static final Set<String> ALLOWED=Set.of("dev","test","prod");
  private final ServiceEnvironmentRepository environments;
  private final AppServiceRepository services;
  private final DeploymentServerRepository servers;

  public ServiceEnvironmentController(ServiceEnvironmentRepository e,AppServiceRepository s,DeploymentServerRepository d){environments=e;services=s;servers=d;}

  @GetMapping public List<ServiceEnvironment> list(@PathVariable Long serviceId){service(serviceId);return environments.findByServiceIdOrderByEnvironmentName(serviceId);}

  @PutMapping("/{environment}") public ServiceEnvironment save(@PathVariable Long serviceId,@PathVariable String environment,@RequestBody ServiceEnvironment input){
    AppService app=service(serviceId);String env=normalize(environment);
    for(Long serverId:input.getServerIds())servers.findById(serverId).orElseThrow(()->new IllegalArgumentException("服务器不存在："+serverId));
    if(input.getHostPort()<1||input.getHostPort()>65535||input.getContainerPort()<1||input.getContainerPort()>65535)throw new IllegalArgumentException("容器端口不正确");
    ServiceEnvironment value=environments.findByServiceIdAndEnvironmentName(serviceId,env).orElseGet(ServiceEnvironment::new);
    String mode=input.getDeploymentMode().trim().toUpperCase(Locale.ROOT);if(!Set.of("DOCKER_RUN","DOCKER_COMPOSE").contains(mode))throw new IllegalArgumentException("部署方式不正确");
    String composeService=input.getComposeService()==null||input.getComposeService().isBlank()?app.getServiceKey():input.getComposeService().trim();if("DOCKER_COMPOSE".equals(mode)&&!composeService.matches("[A-Za-z0-9_.-]+"))throw new IllegalArgumentException("Compose 服务名格式不正确");
    String containerName=input.getContainerName()==null||input.getContainerName().isBlank()?app.getServiceKey():input.getContainerName().trim();if("DOCKER_RUN".equals(mode)&&!containerName.matches("[A-Za-z0-9_.-]+"))throw new IllegalArgumentException("容器名称格式不正确");
    String network=input.getDockerNetwork()==null?null:input.getDockerNetwork().trim();if(network!=null&&!network.isBlank()&&!network.matches("[A-Za-z0-9_.-]+"))throw new IllegalArgumentException("Docker 网络名称格式不正确");
    String restart=input.getRestartPolicy()==null?"UNLESS_STOPPED":input.getRestartPolicy().trim().toUpperCase(Locale.ROOT);if(!Set.of("NO","UNLESS_STOPPED","ALWAYS","ON_FAILURE").contains(restart))throw new IllegalArgumentException("容器重启策略不正确");
    value.setServiceId(serviceId);value.setEnvironmentName(env);value.setServerIds(input.getServerIds());value.setHostPort(input.getHostPort());value.setContainerPort(input.getContainerPort());value.setContainerName(containerName);value.setEnvFilePath(input.getEnvFilePath());value.setDockerNetwork(network);value.setRestartPolicy(restart);value.setDeploymentMode(mode);value.setComposeService(composeService);value.setStartCommand(input.getStartCommand());value.setHealthCheckUrl(input.getHealthCheckUrl());value.setEnvJson(input.getEnvJson()==null?"{}":input.getEnvJson());value.setEnabled(input.isEnabled());
    return environments.save(value);
  }

  private AppService service(Long id){return services.findById(id).orElseThrow(()->new IllegalArgumentException("服务不存在"));}
  private String normalize(String value){String env=value==null?"":value.trim().toLowerCase(Locale.ROOT);if(!ALLOWED.contains(env))throw new IllegalArgumentException("环境仅支持 dev、test、prod");return env;}
}
