package com.local.release.service;

import com.fasterxml.jackson.databind.*;
import com.local.release.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Reload Compose configuration with the confirmed current image, then track replacement IDs. */
@Component
public class RuntimeRestartRunner {
  private final SshClient ssh;
  private final ObjectMapper mapper;
  private final String script;
  @Value("${release.workspace:../workspaces}") private String workspace="../workspaces";
  public RuntimeRestartRunner(SshClient ssh,ObjectMapper mapper) {
    this.ssh=ssh;this.mapper=mapper;
    try(InputStream input=getClass().getResourceAsStream("/runtime/restart-service.py")) {
      if(input==null)throw new IOException("缺少重启脚本");
      script=new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException e){throw new UncheckedIOException(e);}
  }

  public Map<String,String> restart(AppService service,ServiceEnvironment environment,DeploymentServer server,Map<String,String> expected)throws Exception {
    JsonNode result=execute("restart",service,environment,server,expected);
    JsonNode containers=result.path("containers");Map<String,String> replacement=new TreeMap<>();
    if(!containers.isObject())throw new IllegalArgumentException("重建结果缺少新容器信息，请检查目标服务状态");
    containers.fields().forEachRemaining(entry->{if(entry.getValue().isTextual())replacement.put(entry.getKey(),entry.getValue().asText());});
    if(replacement.size()!=expected.size()||containers.size()!=replacement.size()||replacement.keySet().stream().anyMatch(expected::containsKey)
      ||replacement.keySet().stream().anyMatch(id->!id.matches("[a-f0-9]{64}"))||!new HashSet<>(replacement.values()).equals(new HashSet<>(expected.values())))
      throw new IllegalArgumentException("重建后的容器或镜像与预期不一致，请检查目标服务状态");
    return Map.copyOf(replacement);
  }
  public void health(AppService service,ServiceEnvironment environment,DeploymentServer server,Map<String,String> expected)throws Exception {
    execute("health",service,environment,server,expected);
  }
  private JsonNode execute(String phase,AppService service,ServiceEnvironment environment,DeploymentServer server,Map<String,String> expected)throws Exception {
    Map<String,Object> config=configuration(phase,service,environment,server,expected);
    String encoded=Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(config));
    String command="python3 -c "+quote(script)+" "+quote(encoded);
    boolean sudo="DOCKER_COMPOSE".equalsIgnoreCase(environment.getDeploymentMode())&&server.isComposeUseSudo();
    String output=sudo?ssh.executeSudo(server,command,Path.of(workspace).toAbsolutePath()):ssh.execute(server,command,Path.of(workspace).toAbsolutePath());
    if(output==null||output.length()>8192)throw new IllegalArgumentException("重启执行结果不完整，请检查目标节点状态");
    JsonNode result=mapper.readTree(output);
    if(result==null||!result.isObject()||!result.path("success").isBoolean())throw new IllegalArgumentException("重启执行结果不完整，请检查目标节点状态");
    if(!result.path("success").booleanValue())throw new IllegalArgumentException(switch(result.path("code").asText()) {
      case "TARGET_CHANGED" -> "目标容器或镜像已变化，重启已中止，请重新检查并确认";
      case "NO_CONTAINER" -> "目标服务没有可重启的容器，请先发布服务";
      case "STATE_UNSUPPORTED" -> "容器正在重启、暂停或删除，当前不能重启，请核对节点状态";
      case "HEALTH_FAILED" -> "容器已执行重启，但健康检查未通过，请检查应用状态后再决定下一步";
      case "RESTART_FAILED" -> "更新配置并重启未完成，部分容器可能已重建，请检查节点状态";
      case "TIMEOUT" -> "远程操作超时，结果可能不确定，请先检查节点状态，避免重复重启";
      case "INTERRUPTED" -> "更新配置并重启已中止，部分操作可能已执行，请检查节点状态";
      case "COMPOSE_ACCESS" -> "无法定位 Compose 服务，请检查配置路径和读取权限";
      case "CONFIG_PERMISSION" -> "配置文件读取权限不足，尚未重建容器。请授权发布账号读取"+safePath(result.path("path").asText());
      case "CONFIG_INVALID" -> "Compose 或环境配置校验失败，尚未重建容器。请检查配置语法、文件路径与必填变量";
      case "MODE_UNSUPPORTED" -> "更新配置并重启需要 Docker Compose 部署；当前部署方式请使用原制品重新发布";
      case "MIXED_IMAGES" -> "同一服务的容器运行了不同镜像版本，无法安全重建，请先核对版本";
      case "RECREATE_VERIFY_FAILED" -> "容器已尝试重建，但容器数量或镜像校验未通过，请检查目标服务状态";
      default -> "无法执行容器操作，请检查 SSH、Docker 与 sudo 权限";
    });
    return result;
  }
  private static String safePath(String path){return path!=null&&path.length()<=500&&path.matches("/[A-Za-z0-9_./ -]+")?"："+path:"所需配置文件";}

  Map<String,Object> configuration(String phase,AppService service,ServiceEnvironment env,DeploymentServer server,Map<String,String> expected) {
    if(expected.isEmpty()||expected.size()>12||expected.entrySet().stream().anyMatch(e->!e.getKey().matches("[a-f0-9]{64}")||!e.getValue().matches("sha256:[a-f0-9]{64}")))throw new IllegalArgumentException("目标容器信息不完整，请重新检查");
    Map<String,Object> values=new LinkedHashMap<>();
    values.put("phase",phase);values.put("expected",expected);
    String mode=env.getDeploymentMode();values.put("mode",mode.toUpperCase(Locale.ROOT));
    if("DOCKER_COMPOSE".equalsIgnoreCase(mode)) {
      values.put("directory",validPath(server.getComposeDirectory(),"Compose 项目目录"));
      values.put("file",validPath(server.getComposeFile()==null||server.getComposeFile().isBlank()?"docker-compose.yml":server.getComposeFile(),"Compose 文件"));
      values.put("service",validName(env.getComposeService()));
    }else throw new IllegalArgumentException("更新配置并重启需要 Docker Compose 部署；当前部署方式请使用原制品重新发布");
    if(env.getContainerPort()<1||env.getContainerPort()>65535)throw new IllegalArgumentException("请配置正确的容器内部端口");
    values.put("port",env.getContainerPort());values.put("path",healthPath(service,env));
    return values;
  }
  private String healthPath(AppService service,ServiceEnvironment env) {
    String value=env.getHealthCheckUrl();
    if(value==null||value.isBlank())return service.getServiceType()==ModelEnums.ServiceType.FRONTEND?"/":"/actuator/health";
    value=value.replace("{host}","127.0.0.1").replace("{hostPort}",String.valueOf(env.getHostPort()));
    try {
      URI uri=URI.create(value);String path=uri.getRawPath();if(path==null||path.isBlank())path="/";
      String result=path+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
      if(!result.startsWith("/")||result.length()>1000||result.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
      return result;
    }catch(IllegalArgumentException e){throw new IllegalArgumentException("健康检查地址格式不正确");}
  }
  private String validName(String value) {if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))throw new IllegalArgumentException("容器或 Compose 服务名不正确");return value;}
  private String validPath(String value,String label) {if(value==null||value.isBlank()||value.length()>500||value.startsWith("-")||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("请配置正确的"+label);return value;}
  private static String quote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
}
