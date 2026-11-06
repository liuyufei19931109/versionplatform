package com.local.release.web;

import com.local.release.domain.DeploymentServer;
import com.local.release.repo.*;
import com.local.release.security.CredentialSecretCrypto;
import com.local.release.service.SshClient;
import java.net.URI;
import org.springframework.web.bind.annotation.*;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@RestController @RequestMapping("/api/servers")
public class ServerController {
  private final DeploymentServerRepository repo;private final ServiceEnvironmentRepository environments;private final CredentialSecretCrypto crypto;private final SshClient ssh;
  public ServerController(DeploymentServerRepository r,ServiceEnvironmentRepository e,CredentialSecretCrypto c,SshClient s){repo=r;environments=e;crypto=c;ssh=s;}
  public record ServerRequest(String name,String host,Integer port,String username,String authType,String privateKeyPath,String password,String credentialHint,String description,String composeDirectory,String composeFile,Boolean composeUseSudo,Boolean enabled,String connectionMode,Integer sshConnectTimeoutSeconds,Integer sshCommandTimeoutSeconds,String businessUrl){}
  public record ServerResponse(Long id,String name,String host,int port,String username,String authType,String privateKeyPath,boolean passwordConfigured,String credentialHint,String description,String composeDirectory,String composeFile,boolean composeUseSudo,boolean enabled,LocalDateTime createdAt,LocalDateTime updatedAt,String connectionMode,Integer sshConnectTimeoutSeconds,Integer sshCommandTimeoutSeconds,String businessUrl){}

  @GetMapping public List<ServerResponse> list(){return repo.findAll().stream().map(this::response).toList();}
  @PostMapping public ServerResponse create(@RequestBody ServerRequest request){DeploymentServer server=new DeploymentServer();apply(server,request,true);return response(repo.save(server));}
  @PutMapping("/{id}") public ServerResponse update(@PathVariable Long id,@RequestBody ServerRequest request){DeploymentServer server=find(id);apply(server,request,false);return response(repo.save(server));}
  @PostMapping("/{id}/test") public Map<String,String> test(@PathVariable Long id)throws Exception{
    DeploymentServer server=find(id);Path directory=Path.of(".").toAbsolutePath();
    try{ssh.executeCheck(server,"true",directory,false,30);}catch(Exception e){throw new ServerCheckException(false,e);}
    if(server.getComposeDirectory()==null||server.getComposeDirectory().isBlank())
      return Map.of("message","SSH 连接成功；尚未配置 Compose 项目目录","sshStatus","SUCCESS","composeStatus","NOT_CONFIGURED","services","");
    String file=server.getComposeFile()==null||server.getComposeFile().isBlank()?"docker-compose.yml":server.getComposeFile();
    String command="cd "+quote(server.getComposeDirectory())+" && docker compose -f "+quote(file)+" config --services";
    try{
      String output=ssh.executeCheck(server,command,directory,server.isComposeUseSudo(),30);
      return Map.of("message","SSH 连接与 Compose 配置检查均成功","sshStatus","SUCCESS","composeStatus","SUCCESS","services",output.trim());
    }catch(Exception e){throw new ServerCheckException(true,e);}
  }
  @DeleteMapping("/{id}") public void delete(@PathVariable Long id){DeploymentServer server=find(id);if(environments.findAll().stream().anyMatch(e->e.getServerIds().contains(id)))throw new IllegalArgumentException("服务器正在被环境集群使用，请先解除绑定");repo.delete(server);}

  private void apply(DeploymentServer server,ServerRequest request,boolean creating){
    String mode=request.connectionMode()==null?server.getConnectionMode():request.connectionMode().trim().toUpperCase(Locale.ROOT);
    if(!Set.of("DIRECT","SSH_CONFIG").contains(mode))throw new IllegalArgumentException("服务器连接方式不正确");
    boolean alias="SSH_CONFIG".equals(mode);
    String auth=normalizeAuth(request.authType());int port=request.port()==null?22:request.port();boolean enabled=request.enabled()==null||request.enabled();
    String host=required(request.host(),alias?"请填写 SSH 配置别名":"请填写服务器地址").trim();
    if(host.startsWith("-")||host.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))||host.contains("@"))throw new IllegalArgumentException("服务器地址格式不正确");
    if(alias&&!host.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}"))throw new IllegalArgumentException("SSH 别名只能包含字母、数字、点、下划线和连字符");
    if(alias&&!"SSH_KEY".equals(auth))throw new IllegalArgumentException("SSH 配置别名仅支持密钥认证");
    String username=alias?server.getUsername():required(request.username(),"请填写 SSH 用户").trim();
    if(!alias&&(username.startsWith("-")||username.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))||username.contains("@")))throw new IllegalArgumentException("SSH 用户格式不正确");
    if(request.name()==null||request.name().isBlank())throw new IllegalArgumentException("请填写服务器名称");if(port<1||port>65535)throw new IllegalArgumentException("SSH 端口不正确");
    Integer connect=timeout(request.sshConnectTimeoutSeconds(),5,300,"连接超时须为 5 至 300 秒");
    Integer command=timeout(request.sshCommandTimeoutSeconds(),10,3600,"命令超时须为 10 至 3600 秒");
    String businessUrl=businessUrl(request.businessUrl());
    if("PASSWORD".equals(auth)){if(request.password()!=null&&!request.password().isBlank())server.setEncryptedPassword(crypto.encrypt(request.password()));else if(creating||server.getEncryptedPassword()==null||server.getEncryptedPassword().isBlank())throw new IllegalArgumentException("请填写 SSH 密码");server.setPrivateKeyPath(null);}else{server.setEncryptedPassword(null);server.setPrivateKeyPath(alias?null:blankToNull(request.privateKeyPath()));}
    server.setName(request.name().trim());server.setHost(host);server.setPort(port);server.setUsername(username);server.setAuthType(auth);server.setCredentialHint(blankToNull(request.credentialHint()));server.setDescription(blankToNull(request.description()));server.setComposeDirectory(blankToNull(request.composeDirectory()));server.setComposeFile(request.composeFile()==null||request.composeFile().isBlank()?"docker-compose.yml":request.composeFile().trim());server.setComposeUseSudo(request.composeUseSudo()!=null&&request.composeUseSudo());server.setEnabled(enabled);
    server.setConnectionMode(mode);server.setSshConnectTimeoutSeconds(connect);server.setSshCommandTimeoutSeconds(command);server.setBusinessUrl(businessUrl);
  }
  private DeploymentServer find(Long id){return repo.findById(id).orElseThrow(()->new IllegalArgumentException("服务器不存在"));}
  private ServerResponse response(DeploymentServer s){return new ServerResponse(s.getId(),s.getName(),s.getHost(),s.getPort(),s.getUsername(),s.getAuthType(),s.getPrivateKeyPath(),s.getEncryptedPassword()!=null&&!s.getEncryptedPassword().isBlank(),s.getCredentialHint(),s.getDescription(),s.getComposeDirectory(),s.getComposeFile(),s.isComposeUseSudo(),s.isEnabled(),s.getCreatedAt(),s.getUpdatedAt(),s.getConnectionMode(),s.getSshConnectTimeoutSeconds(),s.getSshCommandTimeoutSeconds(),s.getBusinessUrl());}
  private Integer timeout(Integer value,int min,int max,String message){if(value!=null&&(value<min||value>max))throw new IllegalArgumentException(message);return value;}
  private String businessUrl(String value){String url=blankToNull(value);if(url==null)return null;try{URI uri=URI.create(url);if(url.length()>1000||!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException();return uri.toASCIIString();}catch(Exception e){throw new IllegalArgumentException("业务访问地址须为完整 HTTP 或 HTTPS 地址，不能包含账号密码或片段标记");}}
  private String normalizeAuth(String value){String auth=value==null?"SSH_KEY":value.trim().toUpperCase(Locale.ROOT);if(!Set.of("SSH_KEY","PASSWORD").contains(auth))throw new IllegalArgumentException("SSH 认证方式不正确");return auth;}
  private String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
  private String required(String value,String message){if(value==null||value.isBlank())throw new IllegalArgumentException(message);return value;}
  private String quote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
}
