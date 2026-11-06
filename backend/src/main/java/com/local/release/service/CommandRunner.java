package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.StepType;
import com.local.release.repo.RegistryCredentialRepository;
import com.local.release.security.CredentialSecretCrypto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Component;
import java.io.*; import java.net.URI; import java.nio.charset.StandardCharsets; import java.nio.file.*; import java.util.*; import java.util.function.Consumer;

@Component
public class CommandRunner {
  @Value("${release.force-simulate:false}") boolean forceSimulate;
  @Value("${release.workspace:./workspaces}") String workspaceRoot;
  @Value("${release.registry-prefix:}") String registryPrefix;
  @Value("${release.step-delay-ms:350}") long delay;
  @Value("${release.push-max-attempts:3}") int pushMaxAttempts;
  @Value("${release.push-retry-delay-ms:3000}") long pushRetryDelayMs;
  @Value("${release.command-timeout-seconds:1800}") long commandTimeoutSeconds=1800;
  @Value("${release.git-timeout-seconds:300}") long gitTimeoutSeconds=300;
  private final ObjectMapper objectMapper;
  private final SshClient ssh;
  private final RegistryCredentialRepository registryCredentials;
  private final CredentialSecretCrypto credentialCrypto;

  public CommandRunner(ObjectMapper objectMapper,SshClient ssh,RegistryCredentialRepository registryCredentials,CredentialSecretCrypto credentialCrypto){this.objectMapper=objectMapper;this.ssh=ssh;this.registryCredentials=registryCredentials;this.credentialCrypto=credentialCrypto;}

  public String execute(StepType type,AppService service,ReleaseTask task,Artifact artifact,ServiceEnvironment environment,List<DeploymentServer> servers,Consumer<String> liveLog)throws Exception{
    if(forceSimulate||task.isSimulate())throw new IllegalArgumentException("模拟打包和模拟发布已停用，请关闭模拟配置");
    Path workspace=Paths.get(workspaceRoot).toAbsolutePath().resolve(String.valueOf(task.getId()));
    Path source=workspace.resolve("source");Files.createDirectories(source);
    return switch(type){
      case GIT_PULL -> gitPull(service,task,source,liveLog);
      case BUILD_JAR -> buildApplication(service,source,liveLog);
      case BUILD_IMAGE -> buildImage(service,task,source,liveLog);
      case PUSH_IMAGE -> pushImage(service,task,source,liveLog);
      case SSH_CONNECT -> onTargetNodes(environment, servers, liveLog,
        server -> ssh.execute(server, "true", source) + "连接成功\n");
      case START_SERVICE -> onTargetNodes(environment, servers, liveLog,
        server -> startServer(service, artifact, environment, server, source));
      case HEALTH_CHECK -> onTargetNodes(environment, servers, liveLog,
        server -> healthServer(service, environment, server));
      case BUILD_AAB, VALIDATE_AAB, BUILD_APK, VALIDATE_APK, ARCHIVE_ARTIFACT -> throw new IllegalArgumentException("App 构建必须使用专用流程");
    };
  }
  public Path sourcePath(ReleaseTask task)throws IOException{
    Path root=Paths.get(workspaceRoot).toAbsolutePath().normalize();
    if(task.getId()==null||task.getId()<1)throw new IllegalArgumentException("任务编号无效");
    Path source=root.resolve(task.getId().toString()).resolve("source");Files.createDirectories(source);
    if(!source.toRealPath().startsWith(root.toRealPath()))throw new IOException("构建目录超出工作区");
    return source;
  }
  private String buildApplication(AppService service,Path source,Consumer<String> liveLog)throws Exception{
    Path directory=workDirectory(service,source);
    if(service.getServiceType()==ModelEnums.ServiceType.FRONTEND&&BuildConfiguration.imageBuildMode(service)==ModelEnums.ImageBuildMode.REPOSITORY_DOCKERFILE){
      String configured=service.getDockerfilePath()==null||service.getDockerfilePath().isBlank()?"Dockerfile":service.getDockerfilePath();
      Path dockerfile=relativePath(source,configured,"Dockerfile 路径");
      if(!Files.isRegularFile(dockerfile))throw new IllegalArgumentException("Dockerfile 不存在："+configured);
      return "仓库 Dockerfile 模式：已验证 "+source.toAbsolutePath().normalize().relativize(dockerfile)+"，依赖安装和应用构建将在镜像构建步骤内完成。"+System.lineSeparator();
    }
    String output=run(buildShellCommand(BuildConfiguration.command(service.getBuildCommand(),service.getServiceType())),directory,liveLog);
    if(service.getServiceType()==ModelEnums.ServiceType.FRONTEND){
      Path artifact=relativePath(directory,service.getJarPath()==null||service.getJarPath().isBlank()?"dist":service.getJarPath(),"前端输出目录");
      if(!Files.isDirectory(artifact))throw new IllegalStateException("前端构建完成，但输出目录不存在："+service.getJarPath());
      try(var files=Files.list(artifact)){if(files.findAny().isEmpty())throw new IllegalStateException("前端构建完成，但输出目录为空："+service.getJarPath());}
      output+=System.lineSeparator()+"前端输出目录验证通过："+directory.relativize(artifact);
    }
    return output;
  }
  private String buildImage(AppService service,ReleaseTask task,Path source,Consumer<String> liveLog)throws Exception{
    Path directory=workDirectory(service,source);
    String configured=service.getDockerfilePath()==null||service.getDockerfilePath().isBlank()?"Dockerfile":service.getDockerfilePath();
    Path dockerfile=relativePath(directory,configured,"Dockerfile 路径");
    String notice="";
    Path buildContext=directory,runDirectory=directory;
    if(service.getServiceType()==ModelEnums.ServiceType.FRONTEND&&BuildConfiguration.imageBuildMode(service)==ModelEnums.ImageBuildMode.REPOSITORY_DOCKERFILE){
      Path repositoryRoot=source.toAbsolutePath().normalize();
      dockerfile=relativePath(repositoryRoot,configured,"Dockerfile 路径");
      buildContext=repositoryRoot;runDirectory=repositoryRoot;
      notice="使用仓库 Dockerfile，并以源码根目录作为镜像构建上下文："+repositoryRoot.relativize(dockerfile)+System.lineSeparator();
    }else if(service.getServiceType()==ModelEnums.ServiceType.FRONTEND){
      Path artifact=relativePath(directory,service.getJarPath()==null||service.getJarPath().isBlank()?"dist":service.getJarPath(),"前端输出目录");
      Path releaseDirectory=directory.resolve(".release-platform").resolve("frontend-image");
      recreateDirectory(releaseDirectory);
      copyDirectory(artifact,releaseDirectory.resolve("html"));
      Path repositoryNginx=directory.resolve("nginx").resolve("default.conf");
      Path nginx=releaseDirectory.resolve("default.conf");
      if(Files.isRegularFile(repositoryNginx))Files.copy(repositoryNginx,nginx,StandardCopyOption.REPLACE_EXISTING);
      else Files.writeString(nginx,"server {\n  listen 80;\n  server_name _;\n  root /usr/share/nginx/html;\n  index index.html;\n  location / { try_files $uri $uri/ /index.html; }\n}\n",StandardCharsets.UTF_8);
      dockerfile=releaseDirectory.resolve("Dockerfile");
      Files.writeString(dockerfile,"FROM nginx:1.27-alpine\nCOPY html/ /usr/share/nginx/html/\nCOPY default.conf /etc/nginx/conf.d/default.conf\nRUN find /usr/share/nginx/html -type d -exec chmod 755 {} + && find /usr/share/nginx/html -type f -exec chmod 644 {} + && chmod 644 /etc/nginx/conf.d/default.conf\nEXPOSE 80\n",StandardCharsets.UTF_8);
      buildContext=releaseDirectory;
      notice="静态 Nginx 模式：使用 Node 构建产物自动生成 Nginx 镜像。"+System.lineSeparator();
    }
    if(!Files.isRegularFile(dockerfile))throw new IllegalArgumentException("Dockerfile 不存在："+configured);
    return notice+run(List.of("docker","build","--load","--provenance=false","--sbom=false","-f",dockerfile.toString(),"-t",imageTag(service,task),buildContext.toString()),runDirectory,liveLog);
  }
  @FunctionalInterface
  private interface NodeCommand {
    String execute(DeploymentServer server) throws Exception;
  }

  private String onTargetNodes(ServiceEnvironment environment, List<DeploymentServer> servers,
      Consumer<String> liveLog, NodeCommand command) throws Exception {
    deploymentReady(environment, servers);
    StringBuilder output = new StringBuilder();
    for (DeploymentServer server : servers) {
      String title = nodeTitle(server).stripTrailing();
      emit(liveLog, title);
      try {
        String result = command.execute(server);
        output.append(title).append('\n').append(result).append('\n');
        emit(liveLog, "节点 #" + server.getId() + " 已完成本步骤");
      } catch (InterruptedException interrupted) {
        throw interrupted;
      } catch (Exception failure) {
        // Prefix only platform-owned node metadata. Existing SSH diagnostics are already redacted.
        String reason = Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
        throw new IllegalStateException("[失败节点 #" + server.getId() + "] " + reason, failure);
      }
    }
    return output.toString();
  }

  private String startServer(AppService service, Artifact artifact, ServiceEnvironment environment,
      DeploymentServer server, Path source) throws Exception {
    if (artifact == null) throw new IllegalArgumentException("发版任务未选择制品包");
    String container = environment.getContainerName();
    if (container == null || container.isBlank()) container = service.getServiceKey() + "-" + environment.getEnvironmentName();
    String image = artifact.getImageUrl();
    String envArgs = environmentVariables(environment.getEnvJson());
    String login = loginRemoteRegistry(server, image, source);
    boolean compose = "DOCKER_COMPOSE".equalsIgnoreCase(environment.getDeploymentMode());
    String automatic = compose ? composeStartCommand(image, environment, server) : defaultStartCommand(image, container, environment, envArgs);
    String configured = environment.getStartCommand();
    String command = (configured == null || configured.isBlank() ? automatic : configured)
      .replace("{image}", image).replace("{version}", artifact.getVersion()).replace("{container}", container)
      .replace("{hostPort}", String.valueOf(environment.getHostPort())).replace("{containerPort}", String.valueOf(environment.getContainerPort()))
      .replace("{envArgs}", envArgs).replace("{host}", server.getHost());
    String result = compose && server.isComposeUseSudo() ? ssh.executeSudo(server, command, source) : ssh.execute(server, command, source);
    return login + result + "启动命令执行成功\n";
  }
  public String imageTag(AppService s,ReleaseTask t){
    String repository=s.getImageRepository();
    if((repository==null||repository.isBlank())&&registryPrefix!=null&&!registryPrefix.isBlank())repository=registryPrefix.replaceAll("/+$","")+"/"+s.getServiceKey();
    return required(repository,"未配置镜像仓库")+":"+imageVersion(t);
  }
  public String imageVersion(ReleaseTask task){
    String branch=sanitize(required(task.getBranch(),"未配置代码分支")).replaceAll("^[.-]+","");
    if(branch.isBlank())branch="build";
    String commit=task.getCommitId()==null?"":task.getCommitId().trim();
    String hex=commit.replaceAll("[^a-fA-F0-9]","");
    String shortCommit=hex.length()>=6?hex.substring(0,6).toLowerCase(Locale.ROOT):sanitize(commit);
    if(shortCommit.length()>6)shortCommit=shortCommit.substring(0,6);
    if(shortCommit.isBlank())shortCommit=String.format(Locale.ROOT,"%06d",task.getId()%1_000_000);
    String suffix="-"+shortCommit+"-b"+Objects.requireNonNull(task.getId(),"打包任务编号不存在");
    if(branch.length()>120-suffix.length())branch=branch.substring(0,120-suffix.length());
    return branch+suffix;
  }
  private String gitPull(AppService s,ReleaseTask t,Path source,Consumer<String> liveLog)throws Exception{
    String expected=required(t.getCommitId(),"任务未固定 Commit，请重新新建打包").toLowerCase(Locale.ROOT);
    if(!expected.matches("[a-f0-9]{40}"))throw new IllegalArgumentException("任务 Commit 格式不正确");
    String output="";
    if(!Files.exists(source.resolve(".git"))){
      output+=run(List.of("git","init"),source,liveLog);
      output+=run(List.of("git","remote","add","origin",required(s.getGitRepo(),"未配置 Git 仓库")),source,liveLog);
    }
    if(!required(s.getGitRepo(),"未配置 Git 仓库").equals(run(List.of("git","remote","get-url","origin"),source).trim()))
      throw new IllegalArgumentException("工作区仓库与当前服务配置不一致，请新建打包任务");
    output+=run(List.of("git","fetch","--no-tags","origin",expected),source,liveLog);
    output+=run(List.of("git","checkout","--detach","--force",expected),source,liveLog);
    String actual=run(List.of("git","rev-parse","HEAD"),source).trim();
    if(!expected.equalsIgnoreCase(actual))throw new IllegalStateException("实际检出的 Commit 与已确认 Commit 不一致");
    return output+"已锁定确认的 Commit："+actual+System.lineSeparator();
  }
  private String healthServer(AppService service, ServiceEnvironment environment, DeploymentServer server) throws Exception {
    boolean compose = "DOCKER_COMPOSE".equalsIgnoreCase(environment.getDeploymentMode());
    String script = compose ? composeHealthScript(service, environment, server) : httpHealthScript(remoteHealthUrl(service, environment));
    Path directory = Paths.get(workspaceRoot).toAbsolutePath();
    if (compose && server.isComposeUseSudo()) return ssh.executeSudo(server, script, directory);
    return ssh.execute(server, "sh -lc " + shellQuote(script), directory);
  }
  private String httpHealthScript(String url){return "attempt=1; while [ $attempt -le 24 ]; do if curl -fsS -o /dev/null --connect-timeout 3 --max-time 8 "+shellQuote(url)+"; then echo '健康检查通过 "+url+"'; exit 0; fi; echo \"第 $attempt 次健康检查未通过，5 秒后重试\"; attempt=$((attempt + 1)); [ $attempt -le 24 ] && sleep 5; done; echo '健康检查失败 "+url+"' >&2; exit 1";}
  private String composeHealthScript(AppService service,ServiceEnvironment environment,DeploymentServer server){
    String directory=required(server.getComposeDirectory(),"服务器未配置远程 Compose 项目目录");
    String composeService=required(environment.getComposeService(),"未配置 Compose 服务名");
    if(!composeService.matches("[A-Za-z0-9_.-]+"))throw new IllegalArgumentException("Compose 服务名格式不正确");
    String file=server.getComposeFile()==null||server.getComposeFile().isBlank()?"docker-compose.yml":server.getComposeFile();
    String compose="docker compose -f "+shellQuote(file),target=shellQuote(composeService),path=healthPath(service,environment);
    return "cd "+shellQuote(directory)+" || exit 1; path="+shellQuote(path)+"; attempt=1; while [ $attempt -le 24 ]; do cid=$("+compose+" ps -q "+target+" | head -n 1); if [ -n \"$cid\" ] && [ \"$(docker inspect -f '{{.State.Running}}' \"$cid\" 2>/dev/null)\" = 'true' ]; then ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{if .IPAddress}}{{println .IPAddress}}{{end}}{{end}}' \"$cid\" 2>/dev/null | sed '/^$/d' | head -n 1); url=\"http://$ip:"+environment.getContainerPort()+"$path\"; if [ -n \"$ip\" ] && curl -fsS -o /dev/null --connect-timeout 3 --max-time 8 \"$url\"; then echo \"健康检查通过 $url\"; exit 0; fi; fi; echo \"第 $attempt 次容器内部健康检查未通过，5 秒后重试\"; attempt=$((attempt + 1)); [ $attempt -le 24 ] && sleep 5; done; echo 'Compose 容器内部健康检查失败' >&2; exit 1";
  }
  private String healthPath(AppService service,ServiceEnvironment environment){
    String fallback=service.getServiceType()==ModelEnums.ServiceType.FRONTEND?"/":"/actuator/health";
    String configured=environment.getHealthCheckUrl();if(configured==null||configured.isBlank())return fallback;
    String value=configured.replace("{host}","127.0.0.1").replace("{hostPort}",String.valueOf(environment.getHostPort()));
    try{URI uri=URI.create(value);String path=uri.getRawPath();if(path==null||path.isBlank())path="/";return uri.getRawQuery()==null?path:path+"?"+uri.getRawQuery();}catch(IllegalArgumentException ignored){return value.startsWith("/")?value:fallback;}
  }
  private String remoteHealthUrl(AppService service,ServiceEnvironment environment){String configured=environment.getHealthCheckUrl();String url=configured==null||configured.isBlank()?"http://127.0.0.1:"+environment.getHostPort()+(service.getServiceType()==ModelEnums.ServiceType.FRONTEND?"/":"/actuator/health"):configured;url=url.replace("{host}","127.0.0.1").replace("{hostPort}",String.valueOf(environment.getHostPort()));int scheme=url.indexOf("://");if(scheme<0)return url;int hostStart=scheme+3,slash=url.indexOf('/',hostStart),colon=url.indexOf(':',hostStart);int hostEnd=slash<0?url.length():slash;if(colon>=0&&colon<hostEnd)hostEnd=colon;return url.substring(0,hostStart)+"127.0.0.1"+url.substring(hostEnd);}
  private String run(List<String> command,Path dir)throws Exception{
    return run(command,dir,line->{});
  }
  private String run(List<String> command,Path dir,Consumer<String> liveLog)throws Exception{
    return BoundedProcessRunner.run(command,dir,null,"git".equals(command.get(0))?gitTimeoutSeconds:commandTimeoutSeconds,liveLog);
  }
  private String pushImage(AppService service,ReleaseTask task,Path source,Consumer<String> liveLog)throws Exception{
    String image=imageTag(service,task);int attempts=Math.max(1,pushMaxAttempts);StringBuilder output=new StringBuilder();Exception last=null;
    for(int attempt=1;attempt<=attempts;attempt++){
      String start="推送尝试 "+attempt+"/"+attempts;output.append(start).append(System.lineSeparator());emit(liveLog,start);
      try{
        output.append(loginLocalRegistry(image,source,liveLog));
        output.append(run(List.of("docker","push",image),source,liveLog));
        if(attempt>1){String success="第 "+attempt+" 次推送成功";output.append(success).append(System.lineSeparator());emit(liveLog,success);}
        return output.toString();
      }catch(Exception error){
        if(error instanceof InterruptedException||Thread.currentThread().isInterrupted())throw error;
        last=error;output.append("第 ").append(attempt).append(" 次推送失败：").append(error.getMessage()).append(System.lineSeparator());
        if(attempt<attempts){long wait=Math.max(0,pushRetryDelayMs)*attempt;String retry="第 "+attempt+" 次推送失败，"+(wait/1000.0)+" 秒后自动重新认证并重试";output.append(retry).append(System.lineSeparator());emit(liveLog,retry);if(wait>0)Thread.sleep(wait);}
      }
    }
    throw new IllegalStateException("推送镜像失败，已自动重试 "+attempts+" 次："+firstLine(last),last);
  }
  private String loginLocalRegistry(String image,Path source,Consumer<String> liveLog)throws Exception{RegistryCredential credential=findCredential(image);if(credential==null)return "";String output=runWithInput(List.of("docker","login","--username",credential.getUsername(),"--password-stdin",credential.getRegistryServer()),source,credentialCrypto.decrypt(credential.getEncryptedPassword())+System.lineSeparator(),liveLog);return "镜像仓库认证成功。"+System.lineSeparator()+output;}
  private String loginRemoteRegistry(DeploymentServer server,String image,Path source)throws Exception{RegistryCredential credential=findCredential(image);if(credential==null)return "";String command="docker login --username "+shellQuote(credential.getUsername())+" --password-stdin "+shellQuote(credential.getRegistryServer());String output=ssh.execute(server,command,source,credentialCrypto.decrypt(credential.getEncryptedPassword())+"\n");return "镜像仓库认证成功。\n"+output;}
  private RegistryCredential findCredential(String image){String value=image==null?"":image.replaceFirst("(?i)^https?://","");int slash=value.indexOf('/');String host=slash<0?value:value.substring(0,slash);return registryCredentials.findByRegistryServer(host).orElse(null);}
  private String runWithInput(List<String> command,Path dir,String stdin,Consumer<String> liveLog)throws Exception{return BoundedProcessRunner.run(command,dir,stdin,Math.min(commandTimeoutSeconds,120),liveLog);}
  private void recreateDirectory(Path directory)throws IOException{if(Files.exists(directory))try(var paths=Files.walk(directory)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(IOException e){throw new UncheckedIOException(e);}});}Files.createDirectories(directory);}
  private void copyDirectory(Path source,Path target)throws IOException{try(var paths=Files.walk(source)){for(Path path:paths.toList()){Path destination=target.resolve(source.relativize(path));if(Files.isDirectory(path))Files.createDirectories(destination);else Files.copy(path,destination,StandardCopyOption.REPLACE_EXISTING);}}}
  private void emit(Consumer<String> liveLog,String value){if(liveLog!=null)liveLog.accept(value);}
  private String firstLine(Exception error){if(error==null)return "未知错误";String message=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();int newline=message.indexOf('\n');return (newline<0?message:message.substring(0,newline)).replace("\r","");}
  private List<String> buildShellCommand(String command){
    String value=required(command,"未配置构建命令").trim();
    java.util.regex.Matcher windowsWrapper=java.util.regex.Pattern.compile("(?is)^cmd(?:\\.exe)?\\s+/d\\s+/s\\s+/c\\s+(.+)$").matcher(value);
    if(windowsWrapper.matches()){value=windowsWrapper.group(1).trim();if(value.length()>=2&&value.startsWith("\"")&&value.endsWith("\""))value=value.substring(1,value.length()-1);}
    boolean windows=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");
    if(windows)return List.of("cmd.exe","/d","/s","/c",value);
    value=value.replaceAll("(?i)(?<![A-Za-z0-9_.-])mvn\\.cmd(?![A-Za-z0-9_.-])","mvn")
      .replaceAll("(?i)(?<![A-Za-z0-9_.-])mvnw\\.cmd(?![A-Za-z0-9_.-])","./mvnw")
      .replaceAll("(?i)(?<![A-Za-z0-9_.-])gradlew\\.bat(?![A-Za-z0-9_.-])","./gradlew")
      .replaceAll("(?i)(?<![A-Za-z0-9_.-])(npm|pnpm|yarn)\\.cmd(?![A-Za-z0-9_.-])","$1");
    return List.of("/bin/sh","-lc",value);
  }
  private String environmentVariables(String json)throws Exception{if(json==null||json.isBlank()||json.equals("{}"))return "";Map<String,Object> values=objectMapper.readValue(json,new TypeReference<>(){});StringBuilder args=new StringBuilder();for(var entry:values.entrySet()){if(entry.getKey()==null||entry.getKey().isBlank())continue;args.append(" -e ").append(shellQuote(entry.getKey()+"="+String.valueOf(entry.getValue())));}return args.toString();}
  private String defaultStartCommand(String image,String container,ServiceEnvironment environment,String envArgs){String restart=switch(environment.getRestartPolicy().toUpperCase(Locale.ROOT)){case "NO"->"";case "ALWAYS"->" --restart always";case "ON_FAILURE"->" --restart on-failure";default->" --restart unless-stopped";};String envFile=environment.getEnvFilePath()==null||environment.getEnvFilePath().isBlank()?"":" --env-file "+shellQuote(environment.getEnvFilePath().trim());String network=environment.getDockerNetwork()==null||environment.getDockerNetwork().isBlank()?"":" --network "+shellQuote(environment.getDockerNetwork().trim());return "docker pull "+shellQuote(image)+" && (docker rm -f "+shellQuote(container)+" >/dev/null 2>&1 || true) && docker run -d"+restart+" --name "+shellQuote(container)+envFile+network+" -p "+environment.getHostPort()+":"+environment.getContainerPort()+envArgs+" "+shellQuote(image);}
  private String composeStartCommand(String image,ServiceEnvironment environment,DeploymentServer server){
    String directory=required(server.getComposeDirectory(),"服务器未配置远程 Compose 项目目录");
    String service=required(environment.getComposeService(),"未配置 Compose 服务名");
    if(!service.matches("[A-Za-z0-9_.-]+"))throw new IllegalArgumentException("Compose 服务名格式不正确");
    String file=server.getComposeFile()==null||server.getComposeFile().isBlank()?"docker-compose.yml":server.getComposeFile();
    String override="/tmp/release-platform-"+service+".yml";
    String baseCompose="docker compose -f "+shellQuote(file);
    String compose=baseCompose+" -f "+shellQuote(override);
    String target=shellQuote(service);
    String yaml="printf '%s\\n' "+shellQuote("services:")+" "+shellQuote("  "+service+":")+" "+shellQuote("    image: "+image)+" > "+shellQuote(override);
    return "cd "+shellQuote(directory)+" && "+baseCompose+" config --services | grep -Fx -- "+target+" && "+yaml+" && "+compose+" pull "+target+" && "+compose+" up -d --no-deps --force-recreate "+target+" && "+compose+" ps "+target+" && "+compose+" logs --tail=100 "+target;
  }
  private String shellQuote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
  private String nodeTitle(DeploymentServer server){return "[节点 "+server.getName()+" · "+server.getHost()+"]\n";}
  private void deploymentReady(ServiceEnvironment environment,List<DeploymentServer> servers){if(environment==null)throw new IllegalArgumentException("目标环境尚未配置");if(!environment.isEnabled())throw new IllegalArgumentException("目标环境已停用");if(servers==null||servers.isEmpty())throw new IllegalArgumentException("目标环境尚未绑定服务器");}
  private Path workDirectory(AppService service,Path source){
    String configured=service.getWorkDirectory();
    BuildConfiguration.repositoryPath(configured,"工作目录");
    if(configured==null||configured.isBlank())return source;
    Path root=source.toAbsolutePath().normalize();Path target=root.resolve(configured).normalize();
    if(!target.startsWith(root))throw new IllegalArgumentException("工作目录必须位于 Git 仓库内");
    if(!Files.isDirectory(target))throw new IllegalArgumentException("工作目录不存在："+configured);
    return target;
  }
  private Path relativePath(Path root,String configured,String label){
    BuildConfiguration.repositoryPath(configured,label);
    Path base=root.toAbsolutePath().normalize();Path path=Paths.get(required(configured,label+"未配置"));
    if(path.isAbsolute())throw new IllegalArgumentException(label+"必须是仓库内的相对路径");
    Path target=base.resolve(path).normalize();
    if(!target.startsWith(base))throw new IllegalArgumentException(label+"必须位于 Git 仓库内");
    return target;
  }
  private String required(String v,String msg){if(v==null||v.isBlank())throw new IllegalArgumentException(msg);return v;}
  private String sanitize(String v){return v.replaceAll("[^a-zA-Z0-9_.-]","-");}
  private String description(StepType t){return switch(t){case GIT_PULL->"拉取代码";case BUILD_JAR->"构建应用";case BUILD_IMAGE->"构建镜像";case PUSH_IMAGE->"推送镜像";case SSH_CONNECT->"连接服务器";case START_SERVICE->"启动服务";case HEALTH_CHECK->"健康检查";case BUILD_AAB->"构建 AAB";case BUILD_APK->"构建 APK";case VALIDATE_AAB,VALIDATE_APK->"校验安装包";case ARCHIVE_ARTIFACT->"保存安装包";};}
}
