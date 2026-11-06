package com.local.release.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** A bare cache outside all user build directories. No checkout, hooks, shell, or mutable branch HEAD is used. */
@Component
public class PolicyGitRepository {
  private final Path root;
  private final ConcurrentHashMap<String,Object> locks=new ConcurrentHashMap<>();
  public PolicyGitRepository(@Value("${release.branch-policy-workspace:./data/git-policy}") String root){this.root=Path.of(root).toAbsolutePath().normalize();}
  public record CheckResult(String baselineSha,boolean containsBaseline){}
  public record MergeResult(String outcome,String before,String after){}
  public static class GitPolicyException extends IllegalStateException {
    private final String code;
    public GitPolicyException(String code,String message){super(message);this.code=code;}
    public String code(){return code;}
  }
  public CheckResult check(String repository,String buildSha)throws Exception{
    validateSha(buildSha);String repo=validateRepository(repository);
    synchronized(locks.computeIfAbsent(identity(repo),key->new Object())){
      Path cache=prepare(repo);String baseline=fetch(cache,buildSha);
      return new CheckResult(baseline,isAncestor(cache,baseline,buildSha));
    }
  }
  public MergeResult mergeFixedCommit(String repository,String buildSha)throws Exception{
    validateSha(buildSha);String repo=validateRepository(repository);
    synchronized(locks.computeIfAbsent(identity(repo),key->new Object())){
      Path cache=prepare(repo);String before=fetch(cache,buildSha);
      if(isAncestor(cache,buildSha,before))return new MergeResult("ALREADY_MERGED",before,before);
      if(!isAncestor(cache,before,buildSha))throw new GitPolicyException("DIVERGED","生产发布成功，但 master 已有其他变更；请人工核对并合并，本次不会创建未经构建验证的合并提交");
      // Deliberately no '+' and no --force: a concurrent master update must be rejected by the server.
      Result push=run(cache,List.of("push","--porcelain","origin",buildSha+":refs/heads/master"),120);
      if(push.code()!=0)throw new GitPolicyException("PUSH_REJECTED","生产发布成功，但合回被拒绝；请检查 master 分支保护、写权限或并发更新，处理后可重试合回");
      fetchMaster(cache);String after=sha(cache,"refs/remotes/origin/master");
      if(!isAncestor(cache,buildSha,after))throw new GitPolicyException("VERIFY_FAILED","生产发布成功，但无法确认远程 master 已包含此版本；请核对仓库后重试合回");
      return new MergeResult("MERGED",before,after);
    }
  }
  private Path prepare(String repository)throws Exception{
    privateDirectory(root);Path hooks=root.resolve("empty-hooks");privateDirectory(hooks);
    Path cache=root.resolve(identity(repository));privateDirectory(cache);
    if(!Files.exists(cache.resolve("HEAD"),LinkOption.NOFOLLOW_LINKS))require(run(cache,List.of("init","--bare","--template="+hooks),20),"CACHE_FAILED","无法初始化分支校验缓存");
    if(Files.isSymbolicLink(cache.resolve("config")))throw new GitPolicyException("CACHE_FAILED","分支校验缓存路径不安全");
    require(run(cache,List.of("config","core.hooksPath",hooks.toString()),10),"CACHE_FAILED","无法配置分支校验缓存");
    require(run(cache,List.of("config","remote.origin.url",repository),10),"CACHE_FAILED","无法配置分支校验缓存");
    require(run(cache,List.of("config","remote.origin.fetch","+refs/heads/master:refs/remotes/origin/master"),10),"CACHE_FAILED","无法配置分支校验缓存");
    return cache;
  }
  private String fetch(Path cache,String buildSha)throws Exception{
    String shallow=require(run(cache,List.of("rev-parse","--is-shallow-repository"),10),"CACHE_FAILED","无法读取分支校验缓存").trim();
    if(!"false".equals(shallow))throw new GitPolicyException("INCOMPLETE_HISTORY","分支校验缓存历史不完整，已阻止打包，请由管理员重建校验缓存");
    fetchMaster(cache);
    String baseline=sha(cache,"refs/remotes/origin/master");
    require(run(cache,List.of("fetch","--no-tags","--no-recurse-submodules","--filter=blob:none","origin",buildSha),120),"COMMIT_UNAVAILABLE","无法读取已锁定的 Commit，请检查仓库授权和提交是否仍可获取；已阻止打包");
    if(!sha(cache,buildSha).equalsIgnoreCase(buildSha))throw new GitPolicyException("COMMIT_UNAVAILABLE","锁定的 Commit 不存在或不是提交，已阻止打包");
    return baseline;
  }
  private void fetchMaster(Path cache)throws Exception{
    require(run(cache,List.of("fetch","--no-tags","--no-recurse-submodules","--filter=blob:none","origin","+refs/heads/master:refs/remotes/origin/master"),120),"BASELINE_UNAVAILABLE","无法读取远程 master，请检查该分支是否存在、仓库授权和网络；不会跳过校验");
  }
  private String sha(Path cache,String ref)throws Exception{
    String sha=require(run(cache,List.of("rev-parse","--verify",ref+"^{commit}"),10),"COMMIT_UNAVAILABLE","无法解析 Commit").trim();validateSha(sha);return sha.toLowerCase(Locale.ROOT);
  }
  private boolean isAncestor(Path cache,String ancestor,String descendant)throws Exception{
    Result result=run(cache,List.of("merge-base","--is-ancestor",ancestor,descendant),30);
    if(result.code()==0)return true;if(result.code()==1)return false;
    throw new GitPolicyException("ANCESTRY_FAILED","Git 历史校验失败，已阻止操作；不会把读取失败当成校验通过");
  }
  private record Result(int code,String text){}
  private Result run(Path directory,List<String> arguments,int seconds)throws Exception{
    List<String> command=new ArrayList<>(List.of("git","-c","protocol.ext.allow=never","-c","gc.auto=0","-c","credential.interactive=false"));command.addAll(arguments);
    ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
    builder.environment().put("GIT_TERMINAL_PROMPT","0");builder.environment().put("GIT_OPTIONAL_LOCKS","0");
    Process process=builder.start();StringBuilder output=new StringBuilder();
    Map<Long,ProcessHandle> descendants=new LinkedHashMap<>();
    Thread reader=Thread.ofVirtual().start(()->{try(Reader input=new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)){char[] buffer=new char[4096];int n;while((n=input.read(buffer))!=-1){synchronized(output){output.append(buffer,0,n);if(output.length()>65536)output.delete(0,output.length()-65536);}}}catch(IOException ignored){}});
    try{
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
      while(true){
        process.descendants().forEach(handle->descendants.put(handle.pid(),handle));
        if(process.waitFor(100,TimeUnit.MILLISECONDS))break;
        if(System.nanoTime()>=deadline)throw new GitPolicyException("GIT_TIMEOUT","Git 操作超时，请检查仓库连接；本次不会跳过校验或强制合并");
      }
      reader.join(2000);if(reader.isAlive())throw new GitPolicyException("GIT_TIMEOUT","Git 子进程未结束，已中止本次操作");
      synchronized(output){return new Result(process.exitValue(),output.toString());}
    }finally{
      process.descendants().forEach(handle->descendants.put(handle.pid(),handle));
      List<ProcessHandle> remaining=new ArrayList<>(descendants.values());Collections.reverse(remaining);remaining.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
      if(process.isAlive())process.destroyForcibly();
      process.getInputStream().close();reader.interrupt();
    }
  }
  private static String require(Result result,String code,String message){if(result.code()!=0)throw new GitPolicyException(code,message);return result.text();}
  public static void validateSha(String sha){if(sha==null||!sha.matches("(?i)[0-9a-f]{40}|[0-9a-f]{64}"))throw new IllegalArgumentException("必须使用完整且已锁定的 Commit");}
  private static String validateRepository(String repository){
    if(repository==null)throw new IllegalArgumentException("Git 仓库地址不正确");String value=repository.trim();
    if(value.isBlank()||value.startsWith("-")||value.indexOf('\n')>=0||value.indexOf('\r')>=0||value.indexOf('\0')>=0||value.startsWith("ext::"))throw new IllegalArgumentException("Git 仓库地址不正确");
    return value;
  }
  /** Same host/path over SSH and HTTPS is one repository; credentials never enter the fingerprint. */
  public static String identity(String repository){
    String value=validateRepository(repository);
    try{
      if(value.contains("://")){URI uri=URI.create(value);if(uri.getHost()!=null){int port=uri.getPort();boolean standard=port<0||("ssh".equals(uri.getScheme())&&port==22)||("https".equals(uri.getScheme())&&port==443)||("http".equals(uri.getScheme())&&port==80)||("git".equals(uri.getScheme())&&port==9418);value=uri.getHost().toLowerCase(Locale.ROOT)+(standard?"":":"+port)+Objects.toString(uri.getPath(),"");}else value=uri.normalize().toString();}
      else if(value.matches("(?:[^@/]+@)?[^:/]+:.+")&&!value.matches("^[A-Za-z]:[\\\\/].*")){int colon=value.indexOf(':');String host=value.substring(0,colon);if(host.contains("@"))host=host.substring(host.indexOf('@')+1);value=host.toLowerCase(Locale.ROOT)+"/"+value.substring(colon+1);}
      else value=Path.of(value).toAbsolutePath().normalize().toString();
      value=value.replaceAll("/+$","").replaceAll("\\.git$","");
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception error){throw new IllegalArgumentException("Git 仓库地址不正确");}
  }
  private static void privateDirectory(Path path)throws IOException{
    if(Files.isSymbolicLink(path))throw new IOException("Policy cache may not be a symbolic link");
    Files.createDirectories(path);
    try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
  }
}
