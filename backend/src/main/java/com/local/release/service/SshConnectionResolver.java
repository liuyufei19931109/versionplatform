package com.local.release.service;

import com.local.release.domain.DeploymentServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

/** SSH_CONFIG references only an operations-managed config; the web form never supplies executable proxy commands. */
@Component
public class SshConnectionResolver {
  @Value("${release.ssh.config-file:/opt/release-platform/ssh/config}")
  private String configFile="/opt/release-platform/ssh/config";
  public SshConnectionResolver(){}
  SshConnectionResolver(String configFile){this.configFile=configFile;}
  public record Connection(List<String> command,int connectSeconds,int commandSeconds,boolean configured){}

  public Connection resolve(DeploymentServer server)throws Exception{
    boolean configured=isConfigured(server);
    if(configured&&"PASSWORD".equalsIgnoreCase(server.getAuthType()))throw configuration("SSH 配置别名仅支持 SSH Key，请先切换认证方式。");
    int connect=connectSeconds(server),execute=commandSeconds(server);
    List<String> command=baseCommand(server);
    if(configured){
      Path path=configurationPath();
      List<String> inspect=new ArrayList<>(command);inspect.add(1,"-G");
      String resolved=SshProcessRunner.inspect(inspect,path.getParent(),Math.min(10,connect));
      Map<String,String> values=effectiveValues(resolved);
      if(!values.containsKey("hostname")||!values.containsKey("user")||!values.containsKey("port"))throw configuration("SSH 配置缺少主机、登录用户或端口，请检查对应 Host 别名。");
      String proxy=values.get("proxycommand");
      if(proxy==null||proxy.isBlank()||proxy.equalsIgnoreCase("none"))throw configuration("SSH 别名未匹配到 ProxyCommand，请检查受控配置中的 Host 名称和 IAP 隧道配置。");
    }
    return new Connection(List.copyOf(command),connect,execute,configured);
  }

  List<String> baseCommand(DeploymentServer server){
    boolean configured=isConfigured(server);String host=Objects.toString(server.getHost(),"").trim();
    if(configured&&!host.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}"))throw configuration("SSH 配置别名须为 1～200 个字母、数字、点、下划线或连字符，且不能以符号开头。");
    if(!configured&&(host.isBlank()||host.startsWith("-")||host.codePoints().anyMatch(Character::isWhitespace)||host.codePoints().anyMatch(Character::isISOControl)||host.contains("@")))throw configuration("SSH 服务器地址格式不正确。");
    List<String> command=new ArrayList<>(List.of("ssh","-T","-o","BatchMode=yes","-o","ConnectTimeout="+connectSeconds(server),"-o","ServerAliveInterval=15","-o","ServerAliveCountMax=3"));
    if(configured){
      command.addAll(List.of("-F",configurationPath().toString(),"-o","StrictHostKeyChecking=yes","-o","PermitLocalCommand=no","-o","ClearAllForwardings=yes","-o","ControlMaster=no","-o","ControlPath=none","-o","RemoteCommand=none","-o","RequestTTY=no",host));
    }else{
      String user=Objects.toString(server.getUsername(),"").trim();
      if(user.isBlank()||user.startsWith("-")||user.contains("@")||user.codePoints().anyMatch(Character::isWhitespace)||user.codePoints().anyMatch(Character::isISOControl))throw configuration("SSH 登录用户格式不正确。");
      if(server.getPort()<1||server.getPort()>65535)throw configuration("SSH 端口不正确。");
      command.addAll(List.of("-p",String.valueOf(server.getPort())));
      if(server.getPrivateKeyPath()!=null&&!server.getPrivateKeyPath().isBlank())command.addAll(List.of("-i",expandHome(server.getPrivateKeyPath())));
      command.add(user+"@"+host);
    }
    return command;
  }
  private Path configurationPath(){
    try{Path path=Path.of(configFile).toAbsolutePath().normalize();if(!Files.isRegularFile(path)||!Files.isReadable(path))throw configuration("平台运行账号无法读取受控 SSH 配置文件，请由运维准备 release.ssh.config-file 指定的文件及目录权限。");return path;}
    catch(InvalidPathException error){throw configuration("平台受控 SSH 配置路径无效，请检查 release.ssh.config-file。");}
  }
  static Map<String,String> effectiveValues(String output){Map<String,String> result=new HashMap<>();for(String line:output.split("\\R")){int separator=line.indexOf(' ');if(separator>0){String key=line.substring(0,separator).toLowerCase(Locale.ROOT);if(Set.of("hostname","user","port","proxycommand").contains(key))result.putIfAbsent(key,line.substring(separator+1).trim());}}return result;}
  public static boolean isConfigured(DeploymentServer server){String mode=server.getConnectionMode();if(mode==null||mode.equalsIgnoreCase("DIRECT"))return false;if(mode.equalsIgnoreCase("SSH_CONFIG"))return true;throw configuration("SSH 连接方式不正确。");}
  public static int connectSeconds(DeploymentServer server){Integer value=server.getSshConnectTimeoutSeconds();int seconds=value==null?(isConfigured(server)?60:10):value;if(seconds<5||seconds>300)throw configuration("SSH 连接超时须为 5～300 秒。");return seconds;}
  public static int commandSeconds(DeploymentServer server){Integer value=server.getSshCommandTimeoutSeconds();int seconds=value==null?600:value;if(seconds<10||seconds>3600)throw configuration("SSH 命令超时须为 10～3600 秒。");return seconds;}
  static SshExecutionException configuration(String message){return new SshExecutionException(SshExecutionException.Code.CONFIGURATION,message,null,false);}
  private static String expandHome(String value){String path=value.trim();if(path.equals("~"))return System.getProperty("user.home");if(path.startsWith("~/")||path.startsWith("~\\"))return Path.of(System.getProperty("user.home"),path.substring(2)).toString();return path;}
}
