package com.local.release.miniprogram;

import com.local.release.service.BoundedProcessRunner;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MiniProgramGit {
  private final MiniProgramSettings settings;
  private final Map<Long,Object> locks=new ConcurrentHashMap<>();
  public MiniProgramGit(MiniProgramSettings settings){this.settings=settings;}
  public record Commit(String branch,String commitId,String title,String committedAt){}
  public List<String> branches(MiniProgramConfiguration config)throws Exception {
    Path cache=directory("lookup");
    String output=run(cache,List.of("ls-remote","--heads","--",config.gitRepo()),30);
    return output.lines().filter(line->line.matches("[a-fA-F0-9]+\\s+refs/heads/.+"))
      .map(line->line.substring(line.indexOf("refs/heads/")+11)).sorted().toList();
  }
  public Commit latest(MiniProgramProject project,String branch)throws Exception {
    branch=MiniProgramConfiguration.branch(branch);
    synchronized(locks.computeIfAbsent(project.getId(),key->new Object())){
      Path cache=directory("previews/"+project.getId());prepare(cache,project.getGitRepo());
      run(cache,List.of("fetch","--no-tags","--depth=2","origin","refs/heads/"+branch),60);
      String[] parts=run(cache,List.of("show","-s","--format=%H%x1f%s%x1f%cI","FETCH_HEAD"),15).trim().split("\u001f",3);
      if(parts.length!=3)throw new IllegalArgumentException("未能读取分支最新提交，请重试");
      return new Commit(branch,MiniProgramConfiguration.commit(parts[0]),parts[1],parts[2]);
    }
  }
  public Path checkout(MiniProgramJob job,MiniProgramConfiguration config)throws Exception {
    Path source=directory("jobs/"+job.getId()+"/source");prepare(source,config.gitRepo());
    run(source,List.of("fetch","--no-tags","origin","refs/heads/"+job.getBranch()),120);
    String branchTip=run(source,List.of("rev-parse","FETCH_HEAD"),10).trim();
    run(source,List.of("fetch","--no-tags","origin",job.getCommitId()),120);
    run(source,List.of("merge-base","--is-ancestor",job.getCommitId(),branchTip),30);
    run(source,List.of("checkout","--detach",job.getCommitId()),30);
    if(!run(source,List.of("rev-parse","HEAD"),10).trim().equals(job.getCommitId()))throw new IllegalArgumentException("检出的代码与已确认 Commit 不一致");
    // A checked-in symlink must never let the uploader package a server file outside the repository.
    try(var paths=Files.walk(source)){if(paths.anyMatch(Files::isSymbolicLink))throw new IllegalArgumentException("小程序源码包含符号链接，请先移除后再上传");}
    return source;
  }
  Path directory(String relative)throws Exception {
    Path root=settings.root();Files.createDirectories(root);Path path=root.resolve(relative).normalize();
    if(!path.startsWith(root))throw new IllegalArgumentException("小程序工作目录无效");
    Files.createDirectories(path);
    if(!path.toRealPath().startsWith(root.toRealPath()))throw new IllegalArgumentException("小程序工作目录超出允许范围");
    return path;
  }
  private void prepare(Path directory,String repo)throws Exception {
    if(!Files.exists(directory.resolve(".git")))run(directory,List.of("init","--template="+directory("empty-hooks")),15);
    run(directory,List.of("config","remote.origin.url",repo),10);
    run(directory,List.of("config","remote.origin.fetch","+refs/heads/*:refs/remotes/origin/*"),10);
  }
  protected String run(Path directory,List<String> arguments,long timeout)throws Exception {
    List<String> command=new ArrayList<>(List.of("git","-c","core.hooksPath="+directory("empty-hooks"),"-c","protocol.ext.allow=never","-c","credential.interactive=false"));
    command.addAll(arguments);
    try{return BoundedProcessRunner.run(command,directory,null,timeout,null);}
    catch(InterruptedException e){throw e;}
    catch(Exception e){throw new IllegalArgumentException("读取 Git 失败：请检查仓库授权、网络，以及所选分支是否仍包含已确认的 Commit");}
  }
}
