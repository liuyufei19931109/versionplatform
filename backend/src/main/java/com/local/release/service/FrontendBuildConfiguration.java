package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.AppService;
import com.local.release.domain.ModelEnums.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** The command and output directory are frozen before the job enters the queue. */
public record FrontendBuildConfiguration(String sourceRepository,String workDirectory,String command,String outputDirectory,String nodeVersion,int heapMiB) {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final String NGINX_COPY = " && mkdir -p nginx && cp (deploy/nginx-[a-z][a-z0-9_-]{0,39}\\.conf) nginx/default\\.conf";
  private static final Pattern NGINX_SUFFIX = Pattern.compile(NGINX_COPY + "$");
  static final String COMMAND_PATTERN = "npm ci(?: --(?:no-audit|no-fund)){0,2}(?: && npm run [A-Za-z0-9:_][A-Za-z0-9:_-]*){1,5}(?:" + NGINX_COPY + ")?";
  public static FrontendBuildConfiguration from(AppService service,FrontendCloudSettings settings){
    if(service.getServiceType()!=ServiceType.FRONTEND||BuildConfiguration.imageBuildMode(service)!=ImageBuildMode.STATIC_NGINX)throw new IllegalArgumentException("前端云构建目前支持静态 Nginx 模式，仓库 Dockerfile 模式请保留本机构建");
    String command=BuildConfiguration.command(service.getBuildCommand(),service.getServiceType());
    // Run only bounded npm scripts; the optional fixed nginx copy is reproduced from the locked local checkout.
    if(!command.matches(COMMAND_PATTERN))throw new IllegalArgumentException("前端云构建命令需为 npm ci 后接 npm run <脚本名>（最多 5 步），请先核对服务的构建命令");
    var config=new FrontendBuildConfiguration(GitHubMobileBuildService.sourceRepository(service.getGitRepo()),path(service.getWorkDirectory(),"",true),command,path(service.getJarPath(),"dist",false),settings.nodeVersion,settings.heapMiB);
    config.nginxSourcePath();return config;
  }
  /** Keep the frozen JSON shape unchanged for historical jobs. */
  String nginxSourcePath(){
    if(!command.matches(COMMAND_PATTERN))throw new IllegalArgumentException("前端构建命令无效");
    var suffix = NGINX_SUFFIX.matcher(command);
    if (!suffix.find()) return null;
    String source = suffix.group(1);
    String output = outputDirectory.toLowerCase(Locale.ROOT);
    if (source.equals(output) || source.startsWith(output + "/")) throw new IllegalArgumentException("前端产物目录不能覆盖锁定源码中的 Nginx 配置");
    return source;
  }
  private static String path(String raw,String fallback,boolean allowRoot){
    String value=raw==null||raw.isBlank()?fallback:raw.trim();if(allowRoot&&value.equals("."))return "";
    if((!allowRoot&&value.isEmpty())||(!value.isEmpty()&&!value.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*"))||Arrays.stream(value.split("/")).anyMatch(s->Set.of(".","..",".git","node_modules",".release-platform").contains(s)))throw new IllegalArgumentException("前端云构建目录必须是仓库内普通相对路径");
    return value;
  }
  public String json(){try{return JSON.writeValueAsString(this);}catch(Exception e){throw new IllegalStateException("前端构建快照无法保存");}}
  static FrontendBuildConfiguration parse(String json){try{return JSON.readValue(json,FrontendBuildConfiguration.class);}catch(Exception e){throw new IllegalStateException("前端构建快照无效，请新建任务");}}
  String hash(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  void verifyService(AppService service){
    var settings=new FrontendCloudSettings();settings.nodeVersion=nodeVersion;settings.heapMiB=heapMiB;
    if(!equals(from(service,settings)))throw new IllegalArgumentException("前端服务的源码或构建配置已改变，请新建打包任务；原任务不会混用新配置");
  }
}
