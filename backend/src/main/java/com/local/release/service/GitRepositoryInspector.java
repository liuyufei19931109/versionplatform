package com.local.release.service;

import com.local.release.domain.AppService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Component
public class GitRepositoryInspector {
  @Value("${release.workspace:./workspaces}") String workspaceRoot;
  private final ConcurrentHashMap<Long,Object> locks=new ConcurrentHashMap<>();

  public record CommitPreview(String branch,String commitId,String shortCommitId,String authorName,String authorEmail,String committedAt,String title,String message,List<String> changedFiles,boolean changedFilesTruncated){}

  public List<String> branches(AppService service)throws Exception{
    String repository=required(service.getGitRepo(),"服务尚未配置 Git 仓库");
    String output=run(List.of("git","ls-remote","--symref","--",repository,"HEAD","refs/heads/*"),null,20);
    LinkedHashSet<String> result=new LinkedHashSet<>();
    for(String line:output.split("\\R")){
      if(line.startsWith("ref: refs/heads/")&&line.endsWith("\tHEAD")){
        result.add(line.substring("ref: refs/heads/".length(),line.indexOf('\t')).trim());
      }else if(line.matches("[a-fA-F0-9]+\\s+refs/heads/.+")){
        result.add(line.substring(line.indexOf("refs/heads/")+"refs/heads/".length()).trim());
      }
    }
    if(result.isEmpty())throw new IllegalArgumentException("仓库没有可用分支，请先推送代码后重试");
    return new ArrayList<>(result);
  }

  public CommitPreview latestCommit(AppService service,String branch)throws Exception{
    validateBranch(branch);String repository=required(service.getGitRepo(),"服务尚未配置 Git 仓库");Object lock=locks.computeIfAbsent(service.getId(),key->new Object());
    synchronized(lock){
      Path cache=Paths.get(workspaceRoot).toAbsolutePath().normalize().resolve(".branch-preview").resolve(String.valueOf(service.getId()));Files.createDirectories(cache);
      if(!Files.isDirectory(cache.resolve(".git"))){run(List.of("git","init"),cache,15);run(List.of("git","remote","add","origin",repository),cache,15);}else run(List.of("git","remote","set-url","origin",repository),cache,15);
      run(List.of("git","fetch","--force","--no-tags","--filter=blob:none","--depth=2","origin","refs/heads/"+branch),cache,30);
      String metadata=run(List.of("git","show","-s","--format=%H%x1f%h%x1f%an%x1f%ae%x1f%aI%x1f%s%x1f%B","FETCH_HEAD"),cache,15).trim();String[] values=metadata.split("\u001f",7);if(values.length<7)throw new IllegalStateException("无法解析最新提交信息");
      String changed=run(List.of("git","show","--format=","--name-status","--no-renames","FETCH_HEAD"),cache,15);List<String> files=changed.lines().map(String::trim).filter(v->!v.isBlank()).toList();boolean truncated=files.size()>20;
      return new CommitPreview(branch,values[0].trim(),values[1].trim(),values[2].trim(),values[3].trim(),values[4].trim(),values[5].trim(),values[6].trim(),files.stream().limit(20).toList(),truncated);
    }
  }
  private String run(List<String> command,Path directory,int timeoutSeconds)throws Exception{
    ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);if(directory!=null)builder.directory(directory.toFile());builder.environment().put("GIT_TERMINAL_PROMPT","0");Process process=builder.start();ByteArrayOutputStream bytes=new ByteArrayOutputStream();Thread reader=Thread.ofVirtual().start(()->{try(InputStream input=process.getInputStream()){input.transferTo(bytes);}catch(IOException ignored){}});
    try{
      if(!process.waitFor(timeoutSeconds,TimeUnit.SECONDS))throw new IllegalStateException("读取 Git 仓库超时，请检查服务器网络及仓库连接");
      reader.join(2000);String output=bytes.toString(StandardCharsets.UTF_8);
      if(process.exitValue()!=0){String detail=tail(output,600).replaceAll("(?i)(https?://)[^/@\\s]+@","$1[已隐藏]@");throw new IllegalStateException("读取 Git 仓库失败，请检查仓库地址及授权："+detail);}
      return output;
    }finally{
      if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
      process.getInputStream().close();
    }
  }
  private void validateBranch(String branch){String value=required(branch,"请选择代码分支");if(value.startsWith("-")||value.contains("..")||value.contains("@{")||value.endsWith(".")||value.endsWith("/")||!value.matches("[A-Za-z0-9._/-]+"))throw new IllegalArgumentException("代码分支名称格式不正确");}
  private String required(String value,String message){if(value==null||value.isBlank())throw new IllegalArgumentException(message);return value.trim();}
  private String tail(String value,int max){String text=value==null?"":value.trim();return text.length()<=max?text:text.substring(text.length()-max);}
}
