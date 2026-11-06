package com.local.release.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

/** A local SSH process owns its proxy descendants. stdout remains machine-readable and stdin is never a shell script. */
final class SshProcessRunner {
  static final int NORMAL_LIMIT=1_000_000, PROBE_LIMIT=131072;
  private SshProcessRunner(){}

  static String execute(List<String> prefix,Path directory,String remoteCommand,String stdin,long connectSeconds,long commandSeconds,int outputLimit)throws Exception{
    String marker="__RELEASE_SSH_READY_"+UUID.randomUUID().toString().replace("-","")+"__";
    List<String> command=new ArrayList<>(prefix);
    command.add("printf '%s\\n' "+quote(marker)+" >&2; exec sh -c "+quote(remoteCommand));
    return run(command,directory,stdin,marker,connectSeconds,commandSeconds,outputLimit).stdout();
  }

  /** Local `ssh -G` only; never return the effective configuration in diagnostics. */
  static String inspect(List<String> command,Path directory,long timeoutSeconds)throws Exception{
    try{return run(command,directory,null,null,timeoutSeconds,timeoutSeconds,NORMAL_LIMIT).stdout();}
    catch(SshExecutionException error){
      if(error.getCode()==SshExecutionException.Code.DEPENDENCY_MISSING)throw error;
      throw new SshExecutionException(SshExecutionException.Code.CONFIGURATION,"无法解析受控 SSH 配置，请检查配置语法、权限及 Include 文件。",error.getExitCode(),false);
    }
  }

  record Result(String stdout,String stderr,int status){}
  static Result run(List<String> command,Path directory,String stdin,String marker,long connectSeconds,long commandSeconds,int limit)throws Exception{
    if(Thread.currentThread().isInterrupted())throw new InterruptedException("SSH 操作已取消，未启动新的本地进程");
    long startedAt=System.nanoTime();
    ProcessBuilder builder=new ProcessBuilder(command);
    if(directory!=null)builder.directory(directory.toFile());
    builder.environment().put("CLOUDSDK_CORE_DISABLE_PROMPTS","1");
    builder.environment().put("CLOUDSDK_CORE_DISABLE_USAGE_REPORTING","true");
    builder.environment().put("SSH_ASKPASS_REQUIRE","never");
    builder.environment().put("GIT_TERMINAL_PROMPT","0");
    Process process;
    try{process=builder.start();}catch(IOException error){throw new SshExecutionException(SshExecutionException.Code.DEPENDENCY_MISSING,"平台运行账号无法启动 ssh，请检查 OpenSSH 安装、PATH 和文件权限。",null,false);}
    Capture stdout=new Capture(limit),stderr=new Capture(65536);
    AtomicLong readyAt=new AtomicLong(marker==null?startedAt:0);
    AtomicReference<IOException> readFailure=new AtomicReference<>(),writeFailure=new AtomicReference<>();
    Thread out=drain(process.getInputStream(),stdout,null,readyAt,readFailure);
    Thread err=drain(process.getErrorStream(),stderr,marker,readyAt,readFailure);
    Thread writer=Thread.ofVirtual().name("ssh-stdin").start(()->{
      try(OutputStream stream=process.getOutputStream()){if(stdin!=null)stream.write(stdin.getBytes(StandardCharsets.UTF_8));}
      catch(IOException error){writeFailure.set(error);}
    });
    Map<Long,ProcessHandle> children=new LinkedHashMap<>();
    try{
      while(true){
        process.descendants().forEach(child->children.put(child.pid(),child));
        if(stdout.overflowed())throw new SshExecutionException(SshExecutionException.Code.OUTPUT_LIMIT,"SSH 执行结果超过大小限制，请检查目标命令输出。",null,readyAt.get()!=0);
        if(readFailure.get()!=null)throw new SshExecutionException(SshExecutionException.Code.PROCESS_IO,"读取 SSH 执行结果失败，请核对目标状态。",null,readyAt.get()!=0);
        long ready=readyAt.get(),now=System.nanoTime();
        if(ready==0&&now-startedAt>=TimeUnit.SECONDS.toNanos(Math.max(1,connectSeconds)))
          throw new SshExecutionException(SshExecutionException.Code.CONNECT_TIMEOUT,"连接超时（"+connectSeconds+" 秒），已停止本地 SSH 和隧道进程；尚未收到远程执行确认。",null,false);
        if(ready!=0&&now-ready>=TimeUnit.SECONDS.toNanos(Math.max(1,commandSeconds)))
          throw new SshExecutionException(SshExecutionException.Code.COMMAND_TIMEOUT,"远程命令超时（"+commandSeconds+" 秒），已停止本地 SSH 和隧道进程；远端操作可能已执行，请核对目标状态。",null,marker!=null);
        if(process.waitFor(25,TimeUnit.MILLISECONDS))break;
      }
      // A proxy may outlive ssh and keep its pipe open. End cached descendants before draining final bytes.
      if(children.values().stream().anyMatch(ProcessHandle::isAlive))terminate(process,children);
      out.join(1500);err.join(1500);writer.join(500);
      boolean ready=readyAt.get()!=0;
      if(out.isAlive()||err.isAlive()||readFailure.get()!=null)
        throw new SshExecutionException(SshExecutionException.Code.PROCESS_IO,"SSH 输出未完整结束，已停止本地隧道；请核对目标执行结果。",process.exitValue(),ready&&marker!=null);
      if(stdout.overflowed())throw new SshExecutionException(SshExecutionException.Code.OUTPUT_LIMIT,"SSH 执行结果超过大小限制，请检查目标命令输出。",process.exitValue(),ready&&marker!=null);
      String errorText=stderr.text();int status=process.exitValue();
      if(status!=0)throw SshExecutionException.failure(errorText+"\n"+stdout.text(),status,ready&&marker!=null);
      if(marker!=null&&!ready)throw new SshExecutionException(SshExecutionException.Code.CONNECTION_FAILED,"SSH 未返回远程执行确认，请检查配置中的代理与远程命令设置。",status,false);
      if(writer.isAlive()||writeFailure.get()!=null)throw new SshExecutionException(SshExecutionException.Code.PROCESS_IO,"SSH 标准输入未完整传送，请核对目标执行结果。",status,ready&&marker!=null);
      return new Result(stdout.text(),marker==null?errorText:errorText.replace(marker+"\n","").replace(marker+"\r\n",""),status);
    }finally{
      // Also runs on InterruptedException. Do not leave gcloud/shell children behind when ssh has exited first.
      terminate(process,children);
      close(process.getOutputStream());close(process.getInputStream());close(process.getErrorStream());
      out.interrupt();err.interrupt();writer.interrupt();
    }
  }

  enum StreamEnd { ENDED, CANCELLED, TIME_LIMIT }
  @FunctionalInterface interface LineSink { void accept(String line); }

  /** A long-running read-only command (log follow). Lines arrive as they are produced; the local process tree never outlives this call. */
  static StreamEnd stream(List<String> prefix,Path directory,String remoteCommand,long connectSeconds,long maxSeconds,int maxLineBytes,LineSink sink,java.util.function.BooleanSupplier cancelled)throws Exception{
    if(Thread.currentThread().isInterrupted())throw new InterruptedException("SSH 操作已取消，未启动新的本地进程");
    String marker="__RELEASE_SSH_READY_"+UUID.randomUUID().toString().replace("-","")+"__";
    List<String> command=new ArrayList<>(prefix);
    command.add("printf '%s\\n' "+quote(marker)+" >&2; exec sh -c "+quote(remoteCommand));
    long startedAt=System.nanoTime();
    ProcessBuilder builder=new ProcessBuilder(command);
    if(directory!=null)builder.directory(directory.toFile());
    builder.environment().put("CLOUDSDK_CORE_DISABLE_PROMPTS","1");
    builder.environment().put("CLOUDSDK_CORE_DISABLE_USAGE_REPORTING","true");
    builder.environment().put("SSH_ASKPASS_REQUIRE","never");
    Process process;
    try{process=builder.start();}catch(IOException error){throw new SshExecutionException(SshExecutionException.Code.DEPENDENCY_MISSING,"平台运行账号无法启动 ssh，请检查 OpenSSH 安装、PATH 和文件权限。",null,false);}
    Capture stderr=new Capture(65536);
    AtomicLong readyAt=new AtomicLong();
    AtomicReference<IOException> readFailure=new AtomicReference<>();
    Thread err=drain(process.getErrorStream(),stderr,marker,readyAt,readFailure);
    AtomicBoolean inputDone=new AtomicBoolean();
    Thread writer=keepAlive(process.getOutputStream(),"",()->inputDone.get()||cancelled.getAsBoolean());
    Thread out=Thread.ofVirtual().name("ssh-stream").start(()->{
      try(InputStream input=process.getInputStream()){pumpLines(input,maxLineBytes,sink);}catch(IOException error){readFailure.compareAndSet(null,error);}
    });
    Map<Long,ProcessHandle> children=new LinkedHashMap<>();
    try{
      while(true){
        process.descendants().forEach(child->children.put(child.pid(),child));
        if(cancelled.getAsBoolean())return StreamEnd.CANCELLED;
        long ready=readyAt.get(),now=System.nanoTime();
        if(ready==0&&now-startedAt>=TimeUnit.SECONDS.toNanos(Math.max(1,connectSeconds)))
          throw new SshExecutionException(SshExecutionException.Code.CONNECT_TIMEOUT,"连接超时（"+connectSeconds+" 秒），已停止本地 SSH 和隧道进程。",null,false);
        if(ready!=0&&now-ready>=TimeUnit.SECONDS.toNanos(Math.max(1,maxSeconds)))return StreamEnd.TIME_LIMIT;
        if(process.waitFor(50,TimeUnit.MILLISECONDS))break;
      }
      if(children.values().stream().anyMatch(ProcessHandle::isAlive))terminate(process,children);
      out.join(1500);err.join(1500);
      int status=process.exitValue();boolean ready=readyAt.get()!=0;
      if(status!=0)throw SshExecutionException.failure(stderr.text(),status,ready);
      if(!ready)throw new SshExecutionException(SshExecutionException.Code.CONNECTION_FAILED,"SSH 未返回远程执行确认，请检查配置中的代理与远程命令设置。",status,false);
      if(readFailure.get()!=null)throw new SshExecutionException(SshExecutionException.Code.PROCESS_IO,"读取 SSH 输出失败，请稍后重试。",status,true);
      return StreamEnd.ENDED;
    }finally{
      inputDone.set(true);writer.interrupt();close(process.getOutputStream());
      terminate(process,children);
      close(process.getInputStream());close(process.getErrorStream());
      out.interrupt();err.interrupt();
    }
  }

  /** Only read-only log streams use this lease. Business command stdin is unchanged. */
  static Thread keepAlive(OutputStream output,String prefix,java.util.function.BooleanSupplier stopped){
    return Thread.ofVirtual().name("ssh-log-lease").start(()->{
      try(output){
        if(!prefix.isEmpty()){output.write(prefix.getBytes(StandardCharsets.UTF_8));output.flush();}
        byte[] heartbeat="RELEASE_LOG_KEEPALIVE\n".getBytes(StandardCharsets.US_ASCII);
        while(!stopped.getAsBoolean()){
          output.write(heartbeat);output.flush();Thread.sleep(1000);
        }
      }catch(InterruptedException error){Thread.currentThread().interrupt();}
      catch(IOException ignored){/* A closed channel also ends the remote lease. */}
    });
  }

  /** Split a byte stream into lines without unbounded buffering: an oversized line keeps its first maxLineBytes and is marked. */
  static void pumpLines(InputStream input,int maxLineBytes,LineSink sink)throws IOException{
    ByteArrayOutputStream line=new ByteArrayOutputStream();boolean clipped=false;byte[] buffer=new byte[8192];int count;
    while((count=input.read(buffer))!=-1){
      for(int i=0;i<count;i++){
        byte value=buffer[i];
        if(value=='\n'){emitLine(line,clipped,sink);line.reset();clipped=false;}
        else if(line.size()<maxLineBytes)line.write(value);
        else clipped=true;
      }
    }
    if(line.size()>0||clipped)emitLine(line,clipped,sink);
  }
  private static void emitLine(ByteArrayOutputStream line,boolean clipped,LineSink sink){
    String text=line.toString(StandardCharsets.UTF_8);
    if(text.endsWith("\r"))text=text.substring(0,text.length()-1);
    sink.accept(clipped?text+" …[本行过长，已截断]":text);
  }

  private static Thread drain(InputStream input,Capture output,String marker,AtomicLong readyAt,AtomicReference<IOException> failure){
    return Thread.ofVirtual().name("ssh-output").start(()->{
      byte[] expected=marker==null?null:(marker+"\n").getBytes(StandardCharsets.US_ASCII);int matched=0;
      try(input){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){
        output.write(buffer,0,count);
        if(expected!=null&&readyAt.get()==0)for(int i=0;i<count;i++){
          byte value=buffer[i];if(value=='\r')continue;
          if(value==expected[matched]){matched++;if(matched==expected.length){readyAt.compareAndSet(0,System.nanoTime());break;}}
          else matched=value==expected[0]?1:0;
        }
      }}catch(IOException error){failure.compareAndSet(null,error);}
    });
  }

  private static void terminate(Process process,Map<Long,ProcessHandle> remembered){
    process.descendants().forEach(child->remembered.put(child.pid(),child));
    List<ProcessHandle> handles=new ArrayList<>(remembered.values());Collections.reverse(handles);
    handles.forEach(ProcessHandle::destroy);if(process.isAlive())process.destroy();
    boolean interrupted=Thread.interrupted();
    long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(400);
    while(System.nanoTime()<deadline&&(process.isAlive()||handles.stream().anyMatch(ProcessHandle::isAlive))){
      try{Thread.sleep(20);}catch(InterruptedException error){interrupted=true;break;}
    }
    handles.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    if(process.isAlive())process.destroyForcibly();
    try{process.waitFor(1,TimeUnit.SECONDS);}catch(InterruptedException error){interrupted=true;}
    if(interrupted)Thread.currentThread().interrupt();
  }
  private static void close(Closeable value){try{value.close();}catch(IOException ignored){}}
  static String quote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}

  static final class Capture extends OutputStream{
    private final int limit;private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private boolean overflow;
    Capture(int limit){this.limit=limit;}
    @Override public synchronized void write(int value){if(bytes.size()<limit)bytes.write(value);else overflow=true;}
    @Override public synchronized void write(byte[] value,int offset,int length){int available=limit-bytes.size();if(length>available)overflow=true;bytes.write(value,offset,Math.min(available,length));}
    synchronized boolean overflowed(){return overflow;}
    synchronized String text(){return bytes.toString(StandardCharsets.UTF_8);}
  }
}
