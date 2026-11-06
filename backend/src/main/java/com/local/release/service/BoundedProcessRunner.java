package com.local.release.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Drains command output concurrently so a silent or stuck process can time out. */
public final class BoundedProcessRunner {
  public static final int MAX_OUTPUT_CHARS=1_000_000;
  private BoundedProcessRunner(){}

  public static String run(List<String> command,Path directory,String stdin,long timeoutSeconds,Consumer<String> liveLog)throws Exception{
    return run(command,directory,stdin,timeoutSeconds,liveLog,Map.of());
  }

  /** Explicit overrides are scoped to this child; null removes an inherited variable. */
  public static String run(List<String> command,Path directory,String stdin,long timeoutSeconds,Consumer<String> liveLog,Map<String,String> environment)throws Exception{
    ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
    builder.environment().put("GIT_TERMINAL_PROMPT","0");
    environment.forEach((key,value)->{if(value==null)builder.environment().remove(key);else builder.environment().put(key,value);});
    Process process=builder.start();StringBuilder output=new StringBuilder();
    AtomicReference<Exception> readerFailure=new AtomicReference<>();
    Thread reader=Thread.ofVirtual().name("release-command-output").start(()->{
      try(BufferedReader input=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
        String line;while((line=input.readLine())!=null){synchronized(output){appendTail(output,line+System.lineSeparator());}if(liveLog!=null)liveLog.accept(line);}
      }catch(Exception error){readerFailure.set(error);}
    });
    Map<Long,ProcessHandle> descendants=new LinkedHashMap<>();
    try{
      try(OutputStream input=process.getOutputStream()){if(stdin!=null)input.write(stdin.getBytes(StandardCharsets.UTF_8));}
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.max(1,timeoutSeconds));
      while(true){
        process.descendants().forEach(handle->descendants.put(handle.pid(),handle));
        if(process.waitFor(100,TimeUnit.MILLISECONDS))break;
        if(readerFailure.get()!=null)throw new IOException("读取命令日志失败",readerFailure.get());
        if(System.nanoTime()>=deadline)throw new IllegalStateException("命令执行超时（"+Math.max(1,timeoutSeconds)+" 秒），已终止相关进程");
      }
      reader.join(5000);
      if(reader.isAlive())throw new IllegalStateException("命令仍有子进程未结束，已终止相关进程");
      if(readerFailure.get()!=null)throw new IOException("读取命令日志失败",readerFailure.get());
      String text;synchronized(output){text=output.toString();}
      if(process.exitValue()!=0)throw new IllegalStateException("命令退出码 "+process.exitValue()+System.lineSeparator()+text);
      return text;
    }catch(Exception error){
      terminateTree(process,descendants);
      try{process.getInputStream().close();}catch(IOException ignored){}
      reader.interrupt();
      try{reader.join(2000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
      if(error instanceof InterruptedException)Thread.currentThread().interrupt();
      throw error;
    }
  }

  private static void terminateTree(Process process,Map<Long,ProcessHandle> descendants){
    process.descendants().forEach(handle->descendants.put(handle.pid(),handle));
    List<ProcessHandle> handles=new ArrayList<>(descendants.values());Collections.reverse(handles);
    handles.forEach(ProcessHandle::destroy);process.destroy();
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
    while(System.nanoTime()<deadline&&(process.isAlive()||handles.stream().anyMatch(ProcessHandle::isAlive))){
      try{Thread.sleep(25);}catch(InterruptedException interrupted){break;}
    }
    handles.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    if(process.isAlive())process.destroyForcibly();
    try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
  }

  static void appendTail(StringBuilder output,String value){
    output.append(value);if(output.length()>MAX_OUTPUT_CHARS)output.delete(0,output.length()-MAX_OUTPUT_CHARS);
  }
}
