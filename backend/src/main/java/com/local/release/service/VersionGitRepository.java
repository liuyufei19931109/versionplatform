package com.local.release.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

/** Isolated branch-creation cache. Never checks out, changes master, or force-updates an existing ref. */
@Component
public class VersionGitRepository {
  public record Snapshot(String sha,String summary,String existingBranchSha) {}
  public static class Failure extends IllegalStateException {
    private final String code;
    public Failure(String code,String message){super(message);this.code=code;}
    public String code(){return code;}
  }
  private record Result(int code,String output) {}
  private final Path root;
  private final ConcurrentMap<String,Object> locks=new ConcurrentHashMap<>();
  public VersionGitRepository(@Value("${release.version-workspace:./data/git-versions}") String root){this.root=Path.of(root).toAbsolutePath().normalize();}

  public Snapshot preview(String repository,String branch)throws Exception {
    validate(repository,branch);
    synchronized(locks.computeIfAbsent(PolicyGitRepository.identity(repository),key->new Object())){
      Path cache=prepare(repository);
      String master=remoteHead(cache,"master");
      if(master==null)throw new Failure("MASTER_MISSING","仓库没有 master 分支，请先在仓库准备基准分支");
      fetch(cache,master);
      String summary=require(run(cache,List.of("show","-s","--format=%s",master),10,false),"READ_FAILED","无法读取 master 的提交说明").trim();
      return new Snapshot(master,safeSummary(summary),remoteHead(cache,branch));
    }
  }

  public void create(String repository,String branch,String expectedMaster)throws Exception {
    validate(repository,branch);PolicyGitRepository.validateSha(expectedMaster);
    synchronized(locks.computeIfAbsent(PolicyGitRepository.identity(repository),key->new Object())){
      Path cache=prepare(repository);
      if(remoteHead(cache,branch)!=null)throw conflict();
      String master=remoteHead(cache,"master");
      if(!expectedMaster.equals(master))throw new Failure("BASELINE_CHANGED","master 已发生变化，请重新预览并确认最新基准；未创建分支");
      fetch(cache,expectedMaster);
      // Recheck after fetching: the confirmation always names one immutable, observed master commit.
      if(!expectedMaster.equals(remoteHead(cache,"master")))throw new Failure("BASELINE_CHANGED","master 已发生变化，请重新预览并确认最新基准；未创建分支");
      String ref="refs/heads/"+branch;
      // The EMPTY expected value is a server-enforced compare-and-create. It must never be replaced
      // by ordinary push (which could fast-forward an existing branch), '+' or unrestricted --force.
      Result pushed=run(cache,List.of("push","--porcelain","--no-follow-tags","--force-with-lease="+ref+":","origin",expectedMaster+":"+ref),90,true);
      boolean created=pushed.output().lines().anyMatch(line->line.startsWith("*\t")&&line.contains(":"+ref+"\t")&&line.contains("[new branch]"));
      if(pushed.code()!=0){
        if(pushed.output().contains("[rejected]")||pushed.output().contains("[remote rejected]"))throw new Failure("PUSH_REJECTED","分支创建被拒绝，请检查同名分支、仓库写权限和分支保护；原分支未被覆盖");
        throw new Failure("CREATE_UNKNOWN","Git 未返回可确认的创建结果，请在仓库核对；平台不会接管或覆盖同名分支");
      }
      // Git may return exit 0 / up-to-date when another actor created the SAME SHA. That is not our creation.
      if(!created)throw conflict();
      String observed;
      try{observed=remoteHead(cache,branch);}catch(Exception error){throw new Failure("CREATE_UNKNOWN","创建请求已发出，但读取远程结果失败，请在仓库核对；平台不会自动覆盖分支");}
      if(!expectedMaster.equals(observed))throw new Failure("CREATE_UNKNOWN","分支创建后已变化或无法确认，请在仓库核对；平台不会重置分支");
    }
  }

  private Path prepare(String repository)throws Exception {
    privateDirectory(root);Path hooks=root.resolve("empty-hooks");privateDirectory(hooks);
    Path cache=root.resolve(PolicyGitRepository.identity(repository));privateDirectory(cache);
    if(!Files.exists(cache.resolve("HEAD"),LinkOption.NOFOLLOW_LINKS))require(run(cache,List.of("init","--bare","--template="+hooks),15,false),"CACHE_FAILED","无法初始化版本分支缓存");
    if(Files.isSymbolicLink(cache.resolve("config")))throw new Failure("CACHE_FAILED","版本分支缓存路径不安全");
    require(run(cache,List.of("config","core.hooksPath",hooks.toString()),10,false),"CACHE_FAILED","无法配置版本分支缓存");
    require(run(cache,List.of("config","remote.origin.url",repository),10,false),"CACHE_FAILED","无法配置版本分支缓存");
    return cache;
  }
  private void fetch(Path cache,String sha)throws Exception {
    require(run(cache,List.of("fetch","--no-tags","--no-recurse-submodules","--depth=1","--filter=blob:none","origin",sha),90,false),"READ_FAILED","无法读取 master 提交，请检查仓库读取权限与网络");
    String commit=require(run(cache,List.of("rev-parse","--verify",sha+"^{commit}"),10,false),"READ_FAILED","无法确认 master 提交").trim();
    if(!sha.equals(commit))throw new Failure("READ_FAILED","master 的提交校验失败");
  }
  private String remoteHead(Path cache,String branch)throws Exception {
    String ref="refs/heads/"+branch;
    String output=require(run(cache,List.of("ls-remote","--heads","origin",ref),30,false),"READ_FAILED","无法读取远程分支，请检查仓库地址、SSH 密钥或网络");
    for(String line:output.lines().toList()){
      String[] values=line.split("\\s+",2);
      if(values.length==2&&values[1].equals(ref)){PolicyGitRepository.validateSha(values[0]);return values[0].toLowerCase(Locale.ROOT);}
    }
    return null;
  }
  private Result run(Path directory,List<String> args,int seconds,boolean push)throws Exception {
    List<String> command=new ArrayList<>(List.of("git","-c","protocol.ext.allow=never","-c","gc.auto=0","-c","credential.interactive=false","-c","push.followTags=false"));command.addAll(args);
    ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
    builder.environment().put("GIT_TERMINAL_PROMPT","0");builder.environment().put("GIT_OPTIONAL_LOCKS","0");
    String ssh=builder.environment().getOrDefault("GIT_SSH_COMMAND","ssh");
    builder.environment().put("GIT_SSH_COMMAND",ssh+" -o BatchMode=yes -o ConnectTimeout=15");
    Process process;
    try{process=builder.start();}catch(IOException missing){throw new Failure("GIT_UNAVAILABLE","Git 无法启动，请检查平台运行账号的 Git 安装与执行权限");}
    StringBuilder output=new StringBuilder();Map<Long,ProcessHandle> children=new LinkedHashMap<>();
    Thread reader=Thread.ofVirtual().start(()->{try(Reader in=new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)){char[] b=new char[4096];int n;while((n=in.read(b))!=-1)synchronized(output){output.append(b,0,n);if(output.length()>65536)output.delete(0,output.length()-65536);}}catch(IOException ignored){}});
    try{
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
      while(true){process.descendants().forEach(h->children.put(h.pid(),h));if(process.waitFor(100,TimeUnit.MILLISECONDS))break;if(System.nanoTime()>=deadline)throw new Failure(push?"CREATE_UNKNOWN":"GIT_TIMEOUT",push?"创建请求超时，远程结果需要核对；平台不会覆盖分支":"读取 Git 超时，请检查网络后重新预览");}
      reader.join(2000);if(reader.isAlive())throw new Failure(push?"CREATE_UNKNOWN":"GIT_TIMEOUT","Git 子进程未结束，请核对远程结果后重试");
      synchronized(output){return new Result(process.exitValue(),output.toString());}
    }finally{
      process.descendants().forEach(h->children.put(h.pid(),h));List<ProcessHandle> reverse=new ArrayList<>(children.values());Collections.reverse(reverse);reverse.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
      if(process.isAlive())process.destroyForcibly();process.getInputStream().close();reader.interrupt();
    }
  }
  private static String require(Result result,String code,String message){if(result.code()!=0)throw new Failure(code,message);return result.output();}
  private static Failure conflict(){return new Failure("BRANCH_EXISTS","仓库已存在同名分支，请先核对；平台不会覆盖、重置或自动接管");}
  private static void validate(String repository,String branch){PolicyGitRepository.identity(repository);if(branch==null||!branch.matches("release[0-9]{8}"))throw new IllegalArgumentException("版本分支格式不正确");}
  private static String safeSummary(String summary){String value=summary.replaceAll("[\\p{Cntrl}]"," ").replaceAll("(?i)(https?://)[^\\s/@]+:[^\\s/@]+@","$1[已隐藏]@");return value.length()>300?value.substring(0,300):value;}
  private static void privateDirectory(Path path)throws IOException{if(Files.isSymbolicLink(path))throw new IOException("Unsafe version cache");Files.createDirectories(path);try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}}
}
