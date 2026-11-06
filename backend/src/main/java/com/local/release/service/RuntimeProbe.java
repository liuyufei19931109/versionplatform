package com.local.release.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.AppService;
import com.local.release.domain.DeploymentServer;
import com.local.release.domain.ServiceEnvironment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** A bounded, read-only Docker snapshot; never returns environment values or raw SSH output. */
@Component
public class RuntimeProbe {
  public record Result(String status,String message,List<ContainerInfo> containers) {}
  public record ContainerInfo(String id,String name,String state,String health,String imageReference,String imageId,
      List<String> repoDigests,List<PortBinding> ports,Long memoryLimitBytes,Long memoryUsageBytes,
      List<String> javaOptions,String javaOptionsSource) {}
  public record PortBinding(String hostIp,String hostPort,String containerPort,String protocol) {}

  private static final int MAX_OUTPUT_CHARS=131072;
  private static final Pattern NAME=Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
  private static final Pattern IMAGE=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,511}");
  private static final Pattern DIGEST=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/+\\-]{0,440}@sha256:[a-f0-9]{64}");
  private static final Pattern JAVA_OPTION=Pattern.compile("(?:-Xm[sx]\\d+[kKmMgG]?|-Xss\\d+[kKmMgG]?|-XX:(?:Initial|Max|Min)RAMPercentage=\\d+(?:\\.\\d+)?|-XX:(?:InitialHeapSize|MaxHeapSize|MinHeapSize|MaxRAM|MaxMetaspaceSize|MaxDirectMemorySize)=\\d+[kKmMgG]?|-XX:[+-]Use(?:G1GC|ParallelGC|SerialGC|ZGC|ShenandoahGC|ContainerSupport))");
  private static final Set<String> MESSAGES=Set.of(
      "目标服务器未安装 Docker，或当前账号无法执行 Docker。",
      "无法读取 Docker，请检查服务器连接账号的 Docker 权限。",
      "无法读取现有 Compose 项目，请检查目录、配置文件及 .env 的读取权限。",
      "运行检查超时，请稍后重新检查。",
      "Docker 返回的数据不完整，未将其判定为正常。",
      "检查结果超过大小限制，请缩小该服务的容器规模。",
      "该服务超过 12 个容器，当前检查范围受限。",
      "运行信息暂时无法读取。"
  );
  private static final Set<String> JAVA_SOURCES=Set.of(
      "Java 进程参数及容器标准 JVM 环境变量（仅启动配置，非有效最大堆）",
      "容器标准 JVM 环境变量（多个 Java 进程，未合并进程参数）",
      "容器标准 JVM 环境变量（未读取 Java 进程；仅启动配置）",
      "未读取到可展示的 Java 启动配置"
  );
  private final SshClient ssh;
  private final ObjectMapper mapper;
  private final String script;

  public RuntimeProbe(SshClient ssh,ObjectMapper mapper) {
    this.ssh=ssh;this.mapper=mapper;
    try(InputStream input=RuntimeProbe.class.getResourceAsStream("/runtime/runtime-probe.py")){
      if(input==null)throw new IOException("缺少运行检查资源");
      this.script=new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException error){throw new UncheckedIOException(error);}
  }

  public Result inspect(DeploymentServer server,AppService service,ServiceEnvironment environment)throws Exception {
    Map<String,String> config=new LinkedHashMap<>();
    String mode=environment.getDeploymentMode();
    if("DOCKER_COMPOSE".equalsIgnoreCase(mode)){
      String directory=server.getComposeDirectory(),name=environment.getComposeService();
      if(directory==null||directory.isBlank()||directory.length()>500||hasControl(directory))return error("未配置有效的 Compose 项目目录。");
      if(name==null||!NAME.matcher(name).matches())return error("未配置有效的 Compose 服务名。");
      String file=server.getComposeFile();if(file==null||file.isBlank())file="docker-compose.yml";
      if(file.length()>500||file.startsWith("-")||hasControl(file))return error("Compose 配置文件路径无效。");
      config.put("mode","DOCKER_COMPOSE");config.put("directory",directory);config.put("file",file);config.put("service",name);
    }else if("DOCKER_RUN".equalsIgnoreCase(mode)){
      String name=environment.getContainerName();
      if(name==null||name.isBlank())name=service.getServiceKey()+"-"+environment.getEnvironmentName();
      if(!NAME.matcher(name).matches())return error("未配置有效的容器名称。");
      config.put("mode","DOCKER_RUN");config.put("container",name);
    }else{return error("该部署模式暂不支持运行检查。");}
    String encoded=Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(config));
    String command="python3 -c "+shellQuote(script)+" "+shellQuote(encoded);
    try{
      String output=ssh.executeReadOnly(server,command,"DOCKER_COMPOSE".equals(config.get("mode"))&&server.isComposeUseSudo());
      return parse(output);
    }catch(InterruptedException error){Thread.currentThread().interrupt();throw error;}
    catch(Exception error){return error(sshFailureMessage(error));}
  }

  Result parse(String output){
    if(output==null||output.length()>MAX_OUTPUT_CHARS)return error("运行检查结果无效或超过大小限制。");
    try{
      JsonNode root=mapper.readTree(output);
      if(root==null||!root.isObject())return error("运行检查结果格式无效。");
      String status=text(root,"status");
      if("ERROR".equals(status)){
        String message=text(root,"message");return error(MESSAGES.contains(message)?message:"运行信息暂时无法读取。");
      }
      JsonNode nodes=root.path("containers");
      if(!nodes.isArray()||nodes.size()>12)return error("运行检查结果格式无效。");
      if("NOT_FOUND".equals(status)&&nodes.isEmpty())return new Result("NOT_FOUND","未找到该服务的容器。",List.of());
      if(!"OK".equals(status)||nodes.isEmpty())return error("运行检查结果不完整。");
      List<ContainerInfo> containers=new ArrayList<>();
      for(JsonNode node:nodes){
        String id=text(node,"id");if(!id.matches("[a-f0-9]{64}"))return error("运行检查结果不完整。");
        String state=oneOf(text(node,"state"),Set.of("created","running","paused","restarting","removing","exited","dead"),"unknown");
        String health=oneOf(text(node,"health"),Set.of("starting","healthy","unhealthy","none"),"unknown");
        List<String> digests=strings(node.path("repoDigests"),DIGEST,12,512);
        List<String> options=strings(node.path("javaOptions"),JAVA_OPTION,24,80);
        List<PortBinding> ports=new ArrayList<>();
        JsonNode bindings=node.path("ports");
        if(bindings.isArray())for(JsonNode port:bindings){
          if(ports.size()>=48)break;
          String hostIp=text(port,"hostIp"),hostPort=text(port,"hostPort"),containerPort=text(port,"containerPort"),protocol=text(port,"protocol");
          if(!hostIp.matches("[0-9a-fA-F:.]{0,64}")||!validPort(hostPort)||!validPort(containerPort)||!Set.of("tcp","udp","sctp").contains(protocol))continue;
          ports.add(new PortBinding(hostIp,hostPort,containerPort,protocol));
        }
        String source=oneOf(text(node,"javaOptionsSource"),JAVA_SOURCES,"未读取到可展示的 Java 启动配置");
        containers.add(new ContainerInfo(id,safe(text(node,"name"),NAME),state,health,
            safe(text(node,"imageReference"),IMAGE),safe(text(node,"imageId"),Pattern.compile("sha256:[a-f0-9]{64}")),
            digests,List.copyOf(ports),nonnegative(node.path("memoryLimitBytes")),nonnegative(node.path("memoryUsageBytes")),options,source));
      }
      return new Result("OK","只读检查完成。",List.copyOf(containers));
    }catch(Exception error){return error("运行检查结果格式无效。");}
  }

  private static String sshFailureMessage(Exception error){
    for(Throwable cause=error;cause!=null;cause=cause.getCause()){
      if(cause instanceof SshExecutionException sshError)return sshError.getMessage();
      String message=Objects.toString(cause.getMessage(),"").toLowerCase(Locale.ROOT);
      if(cause instanceof java.net.SocketTimeoutException||message.contains("timeout")||message.contains("timed out")||message.contains("超时"))return "SSH 运行检查超时，请检查网络或稍后重试。";
      if(message.contains("auth fail")||message.contains("auth cancel")||message.contains("authentication failed"))return "SSH 身份认证失败，请检查服务器账号及密码或密钥。";
      if(cause instanceof java.net.UnknownHostException)return "无法解析服务器地址，请检查主机配置。";
      if(cause instanceof java.net.ConnectException||cause instanceof java.net.NoRouteToHostException||message.contains("connection refused")||message.contains("connection reset")||message.contains("session is down"))return "SSH 连接失败，请检查服务器地址、端口和网络。";
      if(message.contains("大小限制"))return "运行检查结果超过大小限制。";
    }
    return "SSH 运行检查未完成，请检查连接、Python 3 及读取权限。";
  }
  private static Result error(String message){return new Result("ERROR",message,List.of());}
  private static String text(JsonNode node,String field){JsonNode value=node.path(field);return value.isTextual()?value.textValue():"";}
  private static boolean hasControl(String value){return value.codePoints().anyMatch(Character::isISOControl);}
  private static String shellQuote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
  private static String safe(String value,Pattern pattern){return pattern.matcher(value).matches()?value:"";}
  private static String oneOf(String value,Set<String> allowed,String fallback){return allowed.contains(value)?value:fallback;}
  private static Long nonnegative(JsonNode value){return value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()>=0?value.longValue():null;}
  private static boolean validPort(String value){if(!value.matches("[0-9]{1,5}"))return false;int port=Integer.parseInt(value);return port>=1&&port<=65535;}
  private static List<String> strings(JsonNode node,Pattern allowed,int count,int length){
    if(!node.isArray())return List.of();List<String> values=new ArrayList<>();
    for(JsonNode item:node){if(values.size()>=count)break;if(item.isTextual()){String value=item.textValue();if(value.length()<=length&&allowed.matcher(value).matches()&&!values.contains(value))values.add(value);}}
    return List.copyOf(values);
  }
}
