package com.local.release.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

class BoundedProcessRunnerTest {
  @TempDir Path directory;
  static List<String> command(String mode){return List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),Fixture.class.getName(),mode);}

  @Test void silentCommandTimesOutAndTerminatesItsChild()throws Exception{
    List<String> lines=new CopyOnWriteArrayList<>();
    assertThatThrownBy(()->BoundedProcessRunner.run(command("parent"),directory,null,2,lines::add)).hasMessageContaining("超时");
    long pid=lines.stream().filter(line->line.startsWith("child=")).mapToLong(line->Long.parseLong(line.substring(6))).findFirst().orElseThrow();
    long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
    while(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)&&System.nanoTime()<deadline)Thread.sleep(25);
    assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
  }

  @Test void capturesOutputAndReportsExitCode()throws Exception{
    List<String> lines=new ArrayList<>();
    assertThat(BoundedProcessRunner.run(command("ok"),directory,"hello\n",10,lines::add)).contains("received=hello");
    assertThat(lines).contains("received=hello");
    assertThatThrownBy(()->BoundedProcessRunner.run(command("failed"),directory,null,10,null)).hasMessageContaining("退出码 7").hasMessageContaining("fixture failure");
  }

  @Test void retainedOutputIsBounded(){
    StringBuilder output=new StringBuilder("old");BoundedProcessRunner.appendTail(output,"x".repeat(BoundedProcessRunner.MAX_OUTPUT_CHARS)+"tail");
    assertThat(output.length()).isEqualTo(BoundedProcessRunner.MAX_OUTPUT_CHARS);assertThat(output.toString()).endsWith("tail").doesNotStartWith("old");
  }

  public static class Fixture {
    public static void main(String[] args)throws Exception{
      if(args[0].equals("parent")){Process child=new ProcessBuilder(command("child")).start();System.out.println("child="+child.pid());System.out.flush();Thread.sleep(60000);}
      else if(args[0].equals("child"))Thread.sleep(60000);
      else if(args[0].equals("failed")){System.out.println("fixture failure");System.exit(7);}
      else{String input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();System.out.println("received="+input);}
    }
  }
}
