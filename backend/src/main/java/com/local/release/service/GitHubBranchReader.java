package com.local.release.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only process boundary: no clone, checkout, fetch, hooks, shell or remote write. */
@Component
public class GitHubBranchReader {
  private final Path root;
  public GitHubBranchReader(@Value("${release.github.workspace:./data/github-observations}") String root){this.root=Path.of(root).toAbsolutePath().normalize();}
  public String head(String repository,String branch)throws Exception {
    Path hooks=root.resolve("empty-hooks");List<String> command=command(hooks,repository,branch);
    for(Path path:List.of(root,hooks)){if(Files.isSymbolicLink(path))throw new IOException("Unsafe observation directory");Files.createDirectories(path);try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}}
    ProcessBuilder builder=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
    builder.environment().put("GIT_TERMINAL_PROMPT","0");builder.environment().put("GIT_OPTIONAL_LOCKS","0");
    builder.environment().put("GIT_SSH_COMMAND",builder.environment().getOrDefault("GIT_SSH_COMMAND","ssh")+" -o BatchMode=yes -o ConnectTimeout=15");
    Process process=builder.start();StringBuilder output=new StringBuilder();Map<Long,ProcessHandle> children=new LinkedHashMap<>();java.util.concurrent.atomic.AtomicBoolean overflow=new java.util.concurrent.atomic.AtomicBoolean();
    Thread reader=Thread.ofVirtual().start(()->{try(Reader in=new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)){char[] chars=new char[4096];int size;while((size=in.read(chars))!=-1)synchronized(output){if(output.length()+size<=65536)output.append(chars,0,size);else overflow.set(true);}}catch(IOException ignored){}});
    try{
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
      while(true){process.descendants().forEach(p->children.put(p.pid(),p));if(process.waitFor(100,TimeUnit.MILLISECONDS))break;if(System.nanoTime()>=deadline)throw new IOException("Remote head check timed out");}
      reader.join(2000);if(reader.isAlive()||overflow.get()||process.exitValue()!=0)throw new IOException("Remote head check failed");
      String text;synchronized(output){text=output.toString();}
      for(String line:text.lines().toList()){String[] fields=line.split("\\s+",2);if(fields.length==2&&fields[1].equals("refs/heads/"+branch)){PolicyGitRepository.validateSha(fields[0]);return fields[0].toLowerCase(Locale.ROOT);}}
      return null;
    }finally{
      process.descendants().forEach(p->children.put(p.pid(),p));List<ProcessHandle> reverse=new ArrayList<>(children.values());Collections.reverse(reverse);reverse.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
      if(process.isAlive())process.destroyForcibly();process.getInputStream().close();reader.interrupt();
    }
  }
  static List<String> command(Path hooks,String repository,String branch){
    PolicyGitRepository.identity(repository);if(branch==null||!branch.matches("release[0-9]{8}"))throw new IllegalArgumentException("仅可核对受管版本分支");
    return List.of("git","-c","protocol.ext.allow=never","-c","credential.interactive=false","-c","core.hooksPath="+hooks,"ls-remote","--heads","--",repository,"refs/heads/"+branch);
  }
}
