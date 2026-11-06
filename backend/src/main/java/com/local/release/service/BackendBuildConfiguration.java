package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.AppService;
import com.local.release.domain.ModelEnums.ServiceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Maven inputs are immutable; output includes module target JARs used by the repository Dockerfile. */
public record BackendBuildConfiguration(String sourceRepository,String workDirectory,String command,String jarPath,String javaVersion,int heapMiB) {
  private static final ObjectMapper JSON=new ObjectMapper();
  public static BackendBuildConfiguration from(AppService service,BackendCloudSettings settings){
    if(service.getServiceType()!=ServiceType.BACKEND)throw new IllegalArgumentException("后端云构建仅支持 Maven 后端服务");
    String command=BuildConfiguration.command(service.getBuildCommand(),service.getServiceType()).trim().replaceFirst("^mvn\\.cmd(?=\\s)","mvn");
    if(!command.matches("mvn(?: -B)? clean package(?: -DskipTests(?:=true)?)?"))throw new IllegalArgumentException("后端云构建命令需为 mvn clean package，可带 -B 和 -DskipTests");
    return new BackendBuildConfiguration(GitHubMobileBuildService.sourceRepository(service.getGitRepo()),path(service.getWorkDirectory(),"",true),command,path(service.getJarPath(),"target/*.jar",false),settings.javaVersion,settings.heapMiB);
  }
  private static String path(String raw,String fallback,boolean root){
    String value=raw==null||raw.isBlank()?fallback:raw.trim();if(root&&value.equals("."))return "";
    if((!root&&value.isEmpty())||(!value.isEmpty()&&!value.matches(root?"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*":"[A-Za-z0-9_.*-]+(?:/[A-Za-z0-9_.*-]+)*"))||Arrays.stream(value.split("/")).anyMatch(p->Set.of(".","..",".git",".release-platform").contains(p)))throw new IllegalArgumentException("后端构建路径必须位于仓库内");
    return value;
  }
  public String json(){try{return JSON.writeValueAsString(this);}catch(Exception e){throw new IllegalStateException("后端构建快照无法保存");}}
  static BackendBuildConfiguration parse(String json){try{return JSON.readValue(json,BackendBuildConfiguration.class);}catch(Exception e){throw new IllegalStateException("后端构建快照无效，请新建任务");}}
  String hash(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  void verifyService(AppService service){var settings=new BackendCloudSettings();settings.javaVersion=javaVersion;settings.heapMiB=heapMiB;if(!equals(from(service,settings)))throw new IllegalArgumentException("后端源码或构建配置已改变，请新建任务；原任务不会混用新配置");}
}
