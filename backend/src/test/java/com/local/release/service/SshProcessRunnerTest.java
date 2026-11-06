package com.local.release.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SshProcessRunnerTest {
  @TempDir Path directory;
  static final String MARKER="__TEST_READY__";
  static List<String> fixture(String mode,String...extra){List<String> args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),Fixture.class.getName(),mode));args.addAll(List.of(extra));return args;}
  SshProcessRunner.Result run(String mode,String input,long connect,long command)throws Exception{return SshProcessRunner.run(fixture(mode),directory,input,MARKER,connect,command,1024);}

  @Test void stderrAndHandshakeNeverPolluteStdoutAndStdinReachesCommand()throws Exception{
    var result=run("ok","fixture-secret\n",3,3);
    assertThat(result.stdout().replace("\r\n","\n")).isEqualTo("{\"received\":\"fixture-secret\"}\n");assertThat(result.stderr()).contains("fixture gcloud warning").doesNotContain(MARKER);
  }
  @Test void wrapperPreservesInputAndKeepsItOutOfArguments()throws Exception{
    String output=SshProcessRunner.execute(fixture("wrapper"),directory,"printf ok","fixture-secret\n",3,3,1024);
    assertThat(output.replace("\r\n","\n")).isEqualTo("fixture-secret\n");
  }
  @Test void connectionDeadlineCoversSilentProxyStartup(){assertThatThrownBy(()->run("silent",null,1,10)).isInstanceOfSatisfying(SshExecutionException.class,e->{assertThat(e.getCode()).isEqualTo(SshExecutionException.Code.CONNECT_TIMEOUT);assertThat(e.isRemoteStarted()).isFalse();});}
  @Test void commandGetsItsOwnDeadlineAfterHandshake(){assertThatThrownBy(()->run("started-wait",null,5,1)).isInstanceOfSatisfying(SshExecutionException.class,e->{assertThat(e.getCode()).isEqualTo(SshExecutionException.Code.COMMAND_TIMEOUT);assertThat(e.isRemoteStarted()).isTrue();assertThat(e.getMessage()).contains("可能已执行");});}
  @Test void blockedStdinCannotPreventConnectionTimeout(){assertThatThrownBy(()->run("silent","x".repeat(8_000_000),1,10)).isInstanceOfSatisfying(SshExecutionException.class,e->assertThat(e.getCode()).isEqualTo(SshExecutionException.Code.CONNECT_TIMEOUT));}
  @Test void remoteNonzeroResultUsesExitCodeWithoutCredentialOutput(){assertThatThrownBy(()->run("remote-failed",null,3,3)).isInstanceOfSatisfying(SshExecutionException.class,e->{assertThat(e.getExitCode()).isEqualTo(17);assertThat(e.getCode()).isEqualTo(SshExecutionException.Code.REMOTE_COMMAND);assertThat(e.getMessage()).doesNotContain("PRIVATE_SECRET");});}
  @Test void authenticationFailureBeforeMarkerIsDifferentFromRemoteFailure(){assertThatThrownBy(()->run("auth-failed",null,3,3)).isInstanceOfSatisfying(SshExecutionException.class,e->{assertThat(e.getCode()).isEqualTo(SshExecutionException.Code.SSH_AUTH);assertThat(e.isRemoteStarted()).isFalse();assertThat(e.getMessage()).doesNotContain("PRIVATE_SECRET");});}
  @Test void successExitWithoutRemoteMarkerDoesNotReportSuccessfulCommand(){assertThatThrownBy(()->run("no-marker",null,3,3)).hasMessageContaining("未返回远程执行确认");}
  @Test void connectionLossAfterStartWarnsOfUnknownRemoteResult(){assertThatThrownBy(()->run("disconnected",null,3,3)).isInstanceOfSatisfying(SshExecutionException.class,e->{assertThat(e.getCode()).isEqualTo(SshExecutionException.Code.CONNECTION_LOST);assertThat(e.isRemoteStarted()).isTrue();});}
  @Test void outputIsStrictlyBounded(){assertThatThrownBy(()->SshProcessRunner.run(fixture("flood"),directory,null,MARKER,3,3,32)).hasMessageContaining("OUTPUT_LIMIT");}
  @Test void markerMayArriveAcrossSmallReads()throws Exception{assertThat(run("split-marker",null,3,3).stdout()).isEqualTo("ok");}
  @Test void timeoutTerminatesNestedProxyChildren()throws Exception{
    Path child=directory.resolve("child.pid");
    assertThatThrownBy(()->SshProcessRunner.run(fixture("parent",child.toString()),directory,null,MARKER,1,10,1024)).hasMessageContaining("CONNECT_TIMEOUT");
    assertDead(child);
  }
  @Test void proxyChildIsCleanedWhenParentExitsFirst()throws Exception{
    Path child=directory.resolve("child.pid");
    assertThat(SshProcessRunner.run(fixture("orphan",child.toString()),directory,null,MARKER,3,3,1024).status()).isZero();assertDead(child);
  }
  @Test void interruptionTerminatesProxyTree()throws Exception{
    Path child=directory.resolve("child.pid");AtomicReference<Throwable> failure=new AtomicReference<>();
    Thread task=Thread.ofVirtual().start(()->{try{SshProcessRunner.run(fixture("parent",child.toString()),directory,null,MARKER,30,30,1024);}catch(Throwable error){failure.set(error);}});
    long deadline=System.nanoTime()+5_000_000_000L;while(!Files.exists(child)&&System.nanoTime()<deadline)Thread.sleep(20);
    assertThat(Files.exists(child)).isTrue();Thread.sleep(100);task.interrupt();task.join(3000);
    assertThat(task.isAlive()).isFalse();assertThat(failure.get()).isInstanceOf(InterruptedException.class);assertDead(child);
  }
  @Test void existingInterruptionNeverStartsAnotherProcess(){
    Path marker=directory.resolve("must-not-start");
    Thread.currentThread().interrupt();
    try{assertThatThrownBy(()->SshProcessRunner.run(fixture("proxy-protocol",marker.toString()),directory,null,MARKER,3,3,1024)).isInstanceOf(InterruptedException.class);assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(marker).doesNotExist();}
    finally{Thread.interrupted();}
  }
  @Test void machineToolErrorsAreClassifiedWithoutLeakingRawCredentials(){
    assertThat(SshExecutionException.failure("gcloud: command not found SECRET",127,false).getCode()).isEqualTo(SshExecutionException.Code.DEPENDENCY_MISSING);
    assertThat(SshExecutionException.failure("invalid_grant SECRET",255,false).getCode()).isEqualTo(SshExecutionException.Code.GOOGLE_AUTH);
    assertThat(SshExecutionException.failure("403 iap.tunnelInstances.accessViaIAP SECRET",255,false).getCode()).isEqualTo(SshExecutionException.Code.IAP_PERMISSION);
    assertThat(SshExecutionException.failure("gcloud IAP failed to connect to backend SECRET",255,false).getCode()).isEqualTo(SshExecutionException.Code.TUNNEL_FAILED);
    assertThat(SshExecutionException.failure("Host key verification failed SECRET",255,false).getMessage()).contains("HOST_KEY").doesNotContain("SECRET");
  }
  @Test void commonRemoteFailuresKeepSafeActionableReasons(){
    assertThat(SshExecutionException.failure("manifest unknown PRIVATE_SECRET",1,true).getMessage()).contains("镜像或版本不存在").doesNotContain("PRIVATE_SECRET");
    assertThat(SshExecutionException.failure("sudo: a password is required PRIVATE_SECRET",1,true).getMessage()).contains("sudo 权限不足").doesNotContain("PRIVATE_SECRET");
    assertThat(SshExecutionException.failure("cd: /private/secret: No such file or directory",1,true).getMessage()).contains("路径或配置文件不存在").doesNotContain("/private/secret");
    assertThat(SshExecutionException.failure("curl: (7) secret-host; 健康检查失败",1,true).getMessage()).contains("健康检查未通过").doesNotContain("secret-host");
    assertThat(SshExecutionException.failure("no space left on device PRIVATE_SECRET",1,true).getMessage()).contains("磁盘空间").doesNotContain("PRIVATE_SECRET");
  }
  @Test void actualOpenSshUsesLocalProxyWithoutMakingANetworkConnection()throws Exception{
    boolean available=false;
    try{Process version=new ProcessBuilder("ssh","-V").redirectErrorStream(true).start();available=version.waitFor(3,java.util.concurrent.TimeUnit.SECONDS)&&version.exitValue()==0;if(version.isAlive())version.destroyForcibly();}catch(java.io.IOException ignored){}
    assumeTrue(available,"OpenSSH is not installed on this test host");
    Path proxyMarker=directory.resolve("local-proxy-ran");
    List<String> javaCommand=fixture("proxy-protocol",proxyMarker.toString());
    String proxy=javaCommand.stream().map(SshProcessRunnerTest::sshConfigQuote).collect(java.util.stream.Collectors.joining(" "));
    Path config=directory.resolve("ssh-config");
    Files.writeString(config,"Host local-fixture\n  HostName never-network.invalid\n  User fixture\n  Port 22\n  ProxyCommand "+proxy+"\n");
    com.local.release.domain.DeploymentServer server=new com.local.release.domain.DeploymentServer();server.setHost("local-fixture");server.setConnectionMode("SSH_CONFIG");
    var resolved=new SshConnectionResolver(config.toString()).resolve(server);
    assertThatThrownBy(()->SshProcessRunner.execute(resolved.command(),directory,"true",null,5,5,1024)).isInstanceOfSatisfying(SshExecutionException.class,e->{assertThat(e.getCode()).isEqualTo(SshExecutionException.Code.GOOGLE_AUTH);assertThat(e.getMessage()).doesNotContain("PRIVATE_SECRET");});
    assertThat(Files.readString(proxyMarker)).isEqualTo("local-only");
  }
  @Test void streamDeliversLinesAndStopsWhenCancelled()throws Exception{
    List<String> lines=Collections.synchronizedList(new ArrayList<>());
    long started=System.nanoTime();
    var end=SshProcessRunner.stream(fixture("follow-wait"),directory,"docker logs -f x",5,60,1024,lines::add,()->lines.size()>=2);
    assertThat(end).isEqualTo(SshProcessRunner.StreamEnd.CANCELLED);
    assertThat(lines).containsExactly("line-1","line-2");
    assertThat(System.nanoTime()-started).isLessThan(20_000_000_000L);
  }
  @Test void streamEndsWithRemoteCommandAndHonoursTimeLimit()throws Exception{
    List<String> lines=new ArrayList<>();
    assertThat(SshProcessRunner.stream(fixture("follow-end"),directory,"docker logs -f x",5,60,1024,lines::add,()->false)).isEqualTo(SshProcessRunner.StreamEnd.ENDED);
    assertThat(lines).containsExactly("line-1","line-2");
    assertThat(SshProcessRunner.stream(fixture("follow-wait"),directory,"docker logs -f x",5,1,1024,line->{},()->false)).isEqualTo(SshProcessRunner.StreamEnd.TIME_LIMIT);
  }
  @Test void streamReportsConnectTimeoutBeforeRemoteStarts(){
    assertThatThrownBy(()->SshProcessRunner.stream(fixture("silent"),directory,"docker logs -f x",1,60,1024,line->{},()->false)).hasMessageContaining("CONNECT_TIMEOUT");
  }
  @Test void streamSendsLeaseHeartbeatsWithoutClosingInputEarly()throws Exception{
    List<String> lines=new ArrayList<>();
    assertThat(SshProcessRunner.stream(fixture("follow-lease"),directory,"read-only logs",5,10,1024,lines::add,()->false)).isEqualTo(SshProcessRunner.StreamEnd.ENDED);
    assertThat(lines).containsExactly("lease-ok");
  }
  @Test void passwordPrefixPrecedesLeaseAndStoppingClosesThePipe()throws Exception{
    var input=new java.io.PipedInputStream(4096);var output=new java.io.PipedOutputStream(input);
    var stopped=new java.util.concurrent.atomic.AtomicBoolean();
    Thread writer=SshProcessRunner.keepAlive(output,"test-password\n",stopped::get);
    try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(input))){
      assertThat(reader.readLine()).isEqualTo("test-password");
      assertThat(reader.readLine()).isEqualTo("RELEASE_LOG_KEEPALIVE");
      stopped.set(true);writer.interrupt();writer.join(2000);
      assertThat(writer.isAlive()).isFalse();assertThat(reader.readLine()).isNull();
    }finally{stopped.set(true);writer.interrupt();input.close();output.close();}
  }
  private static String sshConfigQuote(String value){return "\""+value.replace('\\','/').replace("\"","\\\"")+"\"";}
  private void assertDead(Path pidFile)throws Exception{long pid=Long.parseLong(Files.readString(pidFile));long deadline=System.nanoTime()+3_000_000_000L;while(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)&&System.nanoTime()<deadline)Thread.sleep(25);assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();}

  public static class Fixture {
    public static void main(String[] args)throws Exception{
      String mode=args[0];
      if(mode.equals("proxy-protocol")){Files.writeString(Path.of(args[1]),"local-only");System.err.println("gcloud invalid_grant PRIVATE_SECRET");System.err.flush();System.exit(1);}
      if(mode.equals("child")||mode.equals("silent")){Thread.sleep(60000);return;}
      if(mode.equals("parent")||mode.equals("orphan")){
        Process child=new ProcessBuilder(fixture("child")).inheritIO().start();Files.writeString(Path.of(args[1]),String.valueOf(child.pid()));
        if(mode.equals("orphan")){System.err.println(MARKER);Thread.sleep(200);return;}Thread.sleep(60000);return;
      }
      if(mode.equals("auth-failed")){System.err.println("Permission denied (publickey). PRIVATE_SECRET");System.exit(255);}
      if(mode.equals("no-marker")){System.out.print("not a confirmed command");return;}
      if(mode.equals("wrapper")){
        String argument=args[1];if(argument.contains("fixture-secret"))throw new IllegalStateException("stdin leaked into argv");
        var match=java.util.regex.Pattern.compile("__RELEASE_SSH_READY_[a-f0-9]+__").matcher(argument);if(!match.find())throw new IllegalStateException("missing wrapper marker");
        System.err.println(match.group());System.err.println("fixture gcloud warning");System.out.println(new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine());return;
      }
      if(mode.startsWith("follow")){
        var match=java.util.regex.Pattern.compile("__RELEASE_SSH_READY_[a-f0-9]+__").matcher(args[1]);if(!match.find())throw new IllegalStateException("missing wrapper marker");
        System.err.println(match.group());System.err.flush();
        if(mode.equals("follow-lease")){
          var reader=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
          if(!"RELEASE_LOG_KEEPALIVE".equals(reader.readLine())||!"RELEASE_LOG_KEEPALIVE".equals(reader.readLine()))throw new IllegalStateException("missing lease");
          System.out.println("lease-ok");return;
        }
        System.out.print("line-1\nline-2\r\n");System.out.flush();
        if(mode.equals("follow-wait"))Thread.sleep(60000);return;
      }
      if(mode.equals("split-marker")){for(char value:(MARKER+"\n").toCharArray()){System.err.print(value);System.err.flush();Thread.sleep(3);}System.out.print("ok");return;}
      System.err.println(MARKER);System.err.flush();
      switch(mode){
        case "started-wait" -> Thread.sleep(60000);
        case "remote-failed" -> {System.err.println("PRIVATE_SECRET remote error");System.exit(17);}
        case "disconnected" -> System.exit(255);
        case "flood" -> System.out.print("x".repeat(1024));
        default -> {System.err.println("fixture gcloud warning");String input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();System.out.println("{\"received\":\""+input+"\"}");}
      }
    }
  }
}
