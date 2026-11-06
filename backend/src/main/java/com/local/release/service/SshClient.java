package com.local.release.service;

import com.jcraft.jsch.*;
import com.local.release.domain.DeploymentServer;
import com.local.release.security.CredentialSecretCrypto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

@Component
public class SshClient {
  private final CredentialSecretCrypto crypto;
  private final SshConnectionResolver resolver;
  public SshClient(CredentialSecretCrypto crypto){this(crypto,new SshConnectionResolver());}
  @Autowired public SshClient(CredentialSecretCrypto crypto,SshConnectionResolver resolver){this.crypto=crypto;this.resolver=resolver;}

  public String execute(DeploymentServer server,String command,Path directory)throws Exception{return execute(server,command,directory,null);}
  public String execute(DeploymentServer server,String command,Path directory,String stdin)throws Exception{
    return executeWithBudget(server,command,directory,stdin,false,SshConnectionResolver.commandSeconds(server),SshProcessRunner.NORMAL_LIMIT);
  }
  public String executeSudo(DeploymentServer server,String command,Path directory)throws Exception{
    return executeWithBudget(server,command,directory,null,true,SshConnectionResolver.commandSeconds(server),SshProcessRunner.NORMAL_LIMIT);
  }
  /** A connection test has the node's complete connection deadline and a separate short remote command deadline. */
  public String executeCheck(DeploymentServer server,String command,Path directory,boolean useSudo,long commandTimeoutSeconds)throws Exception{
    if(commandTimeoutSeconds<1||commandTimeoutSeconds>7200)throw SshConnectionResolver.configuration("SSH 检查超时配置无效。");
    return executeWithBudget(server,command,directory,null,useSudo,commandTimeoutSeconds,SshProcessRunner.PROBE_LIMIT);
  }
  public String executeReadOnly(DeploymentServer server,String command,boolean useSudo)throws Exception{
    return executeWithBudget(server,command,Path.of(".").toAbsolutePath(),null,useSudo,35,SshProcessRunner.PROBE_LIMIT);
  }

  /** Read-only command with its own deadline and output budget, e.g. a container log snapshot. */
  public String executeBounded(DeploymentServer server,String command,boolean useSudo,long commandSeconds,int limit)throws Exception{
    return executeWithBudget(server,command,Path.of(".").toAbsolutePath(),null,useSudo,commandSeconds,limit);
  }

  /** Follow a read-only command's output until it ends, the caller cancels, or maxSeconds elapse. */
  SshProcessRunner.StreamEnd stream(DeploymentServer server,String command,boolean useSudo,long maxSeconds,int maxLineBytes,SshProcessRunner.LineSink sink,java.util.function.BooleanSupplier cancelled)throws Exception{
    if(Thread.currentThread().isInterrupted())throw new InterruptedException("SSH 操作已取消，未开始新的节点连接");
    if(command==null||command.isBlank())throw SshConnectionResolver.configuration("SSH 远程命令不能为空。");
    try{
      if("PASSWORD".equalsIgnoreCase(server.getAuthType())){
        if(SshConnectionResolver.isConfigured(server))throw SshConnectionResolver.configuration("SSH 配置别名仅支持 SSH Key，密码连接不支持 IAP 代理。");
        return streamPassword(server,command,useSudo,maxSeconds,maxLineBytes,sink,cancelled);
      }
      SshConnectionResolver.Connection connection=resolver.resolve(server);
      String remote=useSudo?"sudo -n sh -lc "+SshProcessRunner.quote(command):command;
      return SshProcessRunner.stream(connection.command(),Path.of(".").toAbsolutePath(),remote,connection.connectSeconds(),maxSeconds,maxLineBytes,sink,cancelled);
    }catch(InterruptedException error){Thread.currentThread().interrupt();throw error;}
  }

  private SshProcessRunner.StreamEnd streamPassword(DeploymentServer server,String command,boolean sudo,long maxSeconds,int maxLineBytes,SshProcessRunner.LineSink sink,java.util.function.BooleanSupplier cancelled)throws Exception{
    String encrypted=server.getEncryptedPassword();
    if(encrypted==null||encrypted.isBlank())throw SshConnectionResolver.configuration("服务器尚未配置 SSH 密码。");
    String password=crypto.decrypt(encrypted);
    Session session=new JSch().getSession(server.getUsername(),server.getHost(),server.getPort());
    session.setPassword(password);session.setConfig("PreferredAuthentications","password,keyboard-interactive");
    session.setConfig("StrictHostKeyChecking","no");
    ChannelExec channel=null;Thread reader=null,writer=null;boolean started=false;
    java.util.concurrent.atomic.AtomicBoolean inputDone=new java.util.concurrent.atomic.AtomicBoolean();
    PipedInputStream leaseInput=new PipedInputStream(4096);
    PipedOutputStream leaseOutput=new PipedOutputStream(leaseInput);
    SshProcessRunner.Capture errors=new SshProcessRunner.Capture(65536);
    java.util.concurrent.atomic.AtomicReference<IOException> failure=new java.util.concurrent.atomic.AtomicReference<>();
    long connectDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(SshConnectionResolver.connectSeconds(server));
    try{
      session.connect(remainingConnectMillis(connectDeadline));
      checkInterrupted();
      channel=(ChannelExec)session.openChannel("exec");
      channel.setCommand(sudo?"sudo -S -p '' sh -lc "+SshProcessRunner.quote(command):command);
      channel.setInputStream(leaseInput);
      writer=SshProcessRunner.keepAlive(leaseOutput,sudo?password+"\n":"",()->inputDone.get()||cancelled.getAsBoolean());
      channel.setErrStream(errors);
      InputStream output=channel.getInputStream();
      channel.connect(remainingConnectMillis(connectDeadline));started=true;
      reader=Thread.ofVirtual().name("ssh-stream").start(()->{try{SshProcessRunner.pumpLines(output,maxLineBytes,sink);}catch(IOException error){failure.compareAndSet(null,error);}});
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(maxSeconds);
      while(!(channel.isClosed()&&!reader.isAlive())){
        checkInterrupted();
        if(cancelled.getAsBoolean())return SshProcessRunner.StreamEnd.CANCELLED;
        if(System.nanoTime()>=deadline)return SshProcessRunner.StreamEnd.TIME_LIMIT;
        Thread.sleep(50);
      }
      int status=channel.getExitStatus();
      if(status<0)throw new SshExecutionException(SshExecutionException.Code.CONNECTION_LOST,"SSH 连接已断开，未收到远程退出状态。",null,true);
      if(status!=0)throw SshExecutionException.failure(errors.text(),status,true);
      if(failure.get()!=null)throw new SshExecutionException(SshExecutionException.Code.PROCESS_IO,"读取 SSH 输出失败，请稍后重试。",status,true);
      return SshProcessRunner.StreamEnd.ENDED;
    }catch(JSchException error){
      if(System.nanoTime()>=connectDeadline&&!started)throw new SshExecutionException(SshExecutionException.Code.CONNECT_TIMEOUT,"SSH 连接超时，请检查网络和登录配置。",null,false);
      throw SshExecutionException.failure(error.getMessage(),255,started);
    }finally{
      inputDone.set(true);if(writer!=null)writer.interrupt();
      try{leaseOutput.close();}catch(IOException ignored){}try{leaseInput.close();}catch(IOException ignored){}
      if(channel!=null)channel.disconnect();session.disconnect();if(reader!=null)reader.interrupt();
    }
  }

  private String executeWithBudget(DeploymentServer server,String command,Path directory,String stdin,boolean sudo,long commandSeconds,int limit)throws Exception{
    if(Thread.currentThread().isInterrupted())throw new InterruptedException("SSH 操作已取消，未开始新的节点连接");
    if(command==null||command.isBlank())throw SshConnectionResolver.configuration("SSH 远程命令不能为空。");
    try{
      if("PASSWORD".equalsIgnoreCase(server.getAuthType())){
        if(SshConnectionResolver.isConfigured(server))throw SshConnectionResolver.configuration("SSH 配置别名仅支持 SSH Key，密码连接不支持 IAP 代理。");
        return executePassword(server,command,stdin,sudo,commandSeconds,limit);
      }
      SshConnectionResolver.Connection connection=resolver.resolve(server);
      String remote=sudo?"sudo -n sh -lc "+SshProcessRunner.quote(command):command;
      return SshProcessRunner.execute(connection.command(),directory,remote,stdin,connection.connectSeconds(),commandSeconds,limit);
    }catch(InterruptedException error){Thread.currentThread().interrupt();throw error;}
  }

  private String executePassword(DeploymentServer server,String command,String stdin,boolean sudo,long commandSeconds,int limit)throws Exception{
    String encrypted=server.getEncryptedPassword();
    if(encrypted==null||encrypted.isBlank())throw SshConnectionResolver.configuration("服务器尚未配置 SSH 密码。");
    String password=crypto.decrypt(encrypted);
    Session session=new JSch().getSession(server.getUsername(),server.getHost(),server.getPort());
    session.setPassword(password);session.setConfig("PreferredAuthentications","password,keyboard-interactive");
    // Existing password nodes retain their connection policy; IAP is an SSH_KEY-only opt-in mode.
    session.setConfig("StrictHostKeyChecking","no");
    ChannelExec channel=null;boolean started=false;
    SshProcessRunner.Capture output=new SshProcessRunner.Capture(limit),errors=new SshProcessRunner.Capture(65536);
    long connectDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(SshConnectionResolver.connectSeconds(server));
    try{
      session.connect(remainingConnectMillis(connectDeadline));
      checkInterrupted();
      channel=(ChannelExec)session.openChannel("exec");
      channel.setCommand(sudo?"sudo -S -p '' sh -lc "+SshProcessRunner.quote(command):command);
      String input=sudo?password+"\n":stdin;
      channel.setInputStream(new ByteArrayInputStream((input==null?"":input).getBytes(StandardCharsets.UTF_8)));
      channel.setOutputStream(output);channel.setErrStream(errors);
      checkInterrupted();
      channel.connect(remainingConnectMillis(connectDeadline));started=true;
      checkInterrupted();
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(commandSeconds);
      while(true){
        checkInterrupted();
        if(channel.isClosed())break;
        if(System.nanoTime()>=deadline)throw new SshExecutionException(SshExecutionException.Code.COMMAND_TIMEOUT,"远程命令超时（"+commandSeconds+" 秒）；远端操作可能已执行，请核对目标状态。",null,true);
        if(output.overflowed())throw new SshExecutionException(SshExecutionException.Code.OUTPUT_LIMIT,"SSH 执行结果超过大小限制，请检查目标命令输出。",null,true);
        Thread.sleep(50);
      }
      checkInterrupted();
      if(output.overflowed())throw new SshExecutionException(SshExecutionException.Code.OUTPUT_LIMIT,"SSH 执行结果超过大小限制，请检查目标命令输出。",null,true);
      int status=channel.getExitStatus();
      if(status<0)throw new SshExecutionException(SshExecutionException.Code.CONNECTION_LOST,"SSH 连接已断开，未收到远程退出状态；请核对目标状态后重试。",null,true);
      if(status!=0)throw SshExecutionException.failure(errors.text()+"\n"+output.text(),status,true);
      return output.text();
    }catch(JSchException error){
      if(System.nanoTime()>=connectDeadline&&!started)throw new SshExecutionException(SshExecutionException.Code.CONNECT_TIMEOUT,"SSH 连接超时，请检查网络和登录配置。",null,false);
      throw SshExecutionException.failure(error.getMessage(),255,started);
    }finally{if(channel!=null)channel.disconnect();session.disconnect();}
  }

  private static int remainingConnectMillis(long deadline){long millis=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(millis<=0)throw new SshExecutionException(SshExecutionException.Code.CONNECT_TIMEOUT,"SSH 连接超时，请检查网络和登录配置。",null,false);return (int)Math.min(Integer.MAX_VALUE,millis);}
  private static void checkInterrupted()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("SSH 操作已取消，请核对目标状态");}

  // Kept for callers/tests which check the runtime probe's strict output boundary.
  static final class ProbeOutput extends OutputStream{
    static final int LIMIT=SshProcessRunner.PROBE_LIMIT;
    private final SshProcessRunner.Capture capture=new SshProcessRunner.Capture(LIMIT);
    @Override public void write(int value){capture.write(value);}
    @Override public void write(byte[] value,int offset,int length){capture.write(value,offset,length);}
    boolean overflowed(){return capture.overflowed();}
    String text()throws IOException{if(overflowed())throw new IOException("运行检查结果超过大小限制");return capture.text();}
  }
}
