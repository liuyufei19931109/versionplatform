package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.local.release.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeProbeTest {
  private final ObjectMapper mapper=new ObjectMapper();
  private final SshClient ssh=mock(SshClient.class);
  private final RuntimeProbe probe=new RuntimeProbe(ssh,mapper);
  private static final String ID="a".repeat(64),IMAGE="sha256:"+"b".repeat(64);

  private ObjectNode container(){
    ObjectNode node=mapper.createObjectNode();node.put("id",ID);node.put("name","example-api");node.put("state","running");node.put("health","healthy");
    node.put("imageReference","registry.example/example/user:main-b63");node.put("imageId",IMAGE);node.putArray("repoDigests").add("registry.example/example/user@sha256:"+"c".repeat(64));
    node.putArray("ports").addObject().put("hostIp","127.0.0.1").put("hostPort","8091").put("containerPort","8091").put("protocol","tcp");
    node.put("memoryLimitBytes",0);node.putNull("memoryUsageBytes");node.putArray("javaOptions").add("-Xmx1024m").add("-XX:MaxRAMPercentage=75.0");
    node.put("javaOptionsSource","容器标准 JVM 环境变量（未读取 Java 进程；仅启动配置）");return node;
  }
  private String result(ObjectNode container){ObjectNode root=mapper.createObjectNode();root.put("status","OK");root.put("message","arbitrary remote message");root.putArray("containers").add(container);return root.toString();}
  private AppService service(){AppService service=new AppService();service.setServiceKey("example-api");return service;}
  private ServiceEnvironment environment(String mode){ServiceEnvironment environment=new ServiceEnvironment();environment.setDeploymentMode(mode);environment.setEnvironmentName("test");environment.setComposeService("example-api");return environment;}
  private DeploymentServer server(){DeploymentServer server=new DeploymentServer();server.setComposeDirectory("/data/example");server.setComposeFile("compose.yml");return server;}

  @Test void parsesSafeSnapshotWithoutConfusingUnlimitedMemoryAndUnknownUsage(){
    RuntimeProbe.Result result=probe.parse(result(container()));
    assertThat(result.status()).isEqualTo("OK");assertThat(result.message()).isEqualTo("只读检查完成。");
    RuntimeProbe.ContainerInfo info=result.containers().getFirst();
    assertThat(info.memoryLimitBytes()).isZero();assertThat(info.memoryUsageBytes()).isNull();
    assertThat(info.repoDigests()).containsExactly("registry.example/example/user@sha256:"+"c".repeat(64));
    assertThat(info.ports()).containsExactly(new RuntimeProbe.PortBinding("127.0.0.1","8091","8091","tcp"));
    assertThat(info.javaOptions()).containsExactly("-Xmx1024m","-XX:MaxRAMPercentage=75.0");
  }

  @Test void filtersSecretsUnexpectedFieldsAndInvalidStatusEvenIfRemoteOutputIsMalformed()throws Exception{
    ObjectNode raw=container();raw.put("state","running SECRET_PASSWORD");raw.put("health","healthy SECRET_PASSWORD");
    raw.put("imageReference","secret@value with spaces");raw.put("javaOptionsSource","raw SECRET_PASSWORD");
    raw.putArray("javaOptions").add("-Dspring.datasource.password=SECRET_PASSWORD").add("-Xmx1024m").add("-Xmx1024m --token=SECRET_PASSWORD").add("-javaagent:/secret").add("-XX:+UseG1GC");
    raw.putArray("env").add("PASSWORD=SECRET_PASSWORD");raw.putArray("command").add("--token=SECRET_PASSWORD");
    raw.putArray("ports").addObject().put("hostIp","localhost/password").put("hostPort","80").put("containerPort","80").put("protocol","tcp");
    RuntimeProbe.ContainerInfo info=probe.parse(result(raw)).containers().getFirst();
    assertThat(info.state()).isEqualTo("unknown");assertThat(info.health()).isEqualTo("unknown");assertThat(info.ports()).isEmpty();
    assertThat(info.javaOptions()).containsExactly("-Xmx1024m","-XX:+UseG1GC");assertThat(info.imageReference()).isEmpty();
    assertThat(mapper.writeValueAsString(info)).doesNotContain("SECRET_PASSWORD","javaagent","datasource");
  }

  @Test void remoteErrorCannotReturnRawStderrOrClaimHealth(){
    RuntimeProbe.Result result=probe.parse("{\"status\":\"ERROR\",\"message\":\"PASSWORD=do-not-return\",\"containers\":[]}");
    assertThat(result.status()).isEqualTo("ERROR");assertThat(result.message()).isEqualTo("运行信息暂时无法读取。");assertThat(result.containers()).isEmpty();
    assertThat(probe.parse("{\"status\":\"OK\",\"containers\":[]}").status()).isEqualTo("ERROR");
    assertThat(probe.parse("{\"status\":\"NOT_FOUND\",\"containers\":[]}").status()).isEqualTo("NOT_FOUND");
    assertThat(probe.parse("not JSON --password=do-not-return").message()).doesNotContain("do-not-return");
    assertThat(probe.parse("x".repeat(131073)).status()).isEqualTo("ERROR");
  }

  @Test void dockerRunUsesExactExistingFallbackAndDoesNotInheritComposeSudo()throws Exception{
    DeploymentServer server=server();server.setComposeUseSudo(true);
    when(ssh.executeReadOnly(eq(server),anyString(),eq(false))).thenReturn(result(container()));
    RuntimeProbe.Result result=probe.inspect(server,service(),environment("DOCKER_RUN"));
    assertThat(result.status()).isEqualTo("OK");
    ArgumentCaptor<String> command=ArgumentCaptor.forClass(String.class);verify(ssh).executeReadOnly(eq(server),command.capture(),eq(false));
    String remote=command.getValue();int end=remote.lastIndexOf('\''),start=remote.lastIndexOf('\'',end-1);
    var configuration=mapper.readTree(Base64.getDecoder().decode(remote.substring(start+1,end)));
    assertThat(configuration.path("container").asText()).isEqualTo("example-api-test");
    assertThat(configuration.path("mode").asText()).isEqualTo("DOCKER_RUN");
  }

  @Test void composeConfigTravelsAsDataAndRespectsConfiguredSudo()throws Exception{
    DeploymentServer server=server();server.setComposeUseSudo(true);server.setComposeDirectory("/data/example'; touch /tmp/never-execute");
    when(ssh.executeReadOnly(eq(server),anyString(),eq(true))).thenReturn(result(container()));
    assertThat(probe.inspect(server,service(),environment("DOCKER_COMPOSE")).status()).isEqualTo("OK");
    ArgumentCaptor<String> command=ArgumentCaptor.forClass(String.class);verify(ssh).executeReadOnly(eq(server),command.capture(),eq(true));
    String remote=command.getValue();assertThat(remote).doesNotContain("touch /tmp/never-execute");
    int end=remote.lastIndexOf('\''),start=remote.lastIndexOf('\'',end-1);
    var configuration=mapper.readTree(Base64.getDecoder().decode(remote.substring(start+1,end)));
    assertThat(configuration.path("directory").asText()).isEqualTo(server.getComposeDirectory());
    assertThat(configuration.path("service").asText()).isEqualTo("example-api");
  }

  @Test void invalidSelectorDoesNotExecuteAnyRemoteCommand()throws Exception{
    ServiceEnvironment environment=environment("DOCKER_RUN");environment.setContainerName("--all; docker restart anything");
    assertThat(probe.inspect(server(),service(),environment).status()).isEqualTo("ERROR");verifyNoInteractions(ssh);
  }

  @Test void connectionFailureReturnsSafeErrorWithoutExceptionText()throws Exception{
    when(ssh.executeReadOnly(any(),anyString(),anyBoolean())).thenThrow(new IOException("secret=do-not-return"));
    RuntimeProbe.Result result=probe.inspect(server(),service(),environment("DOCKER_RUN"));
    assertThat(result.status()).isEqualTo("ERROR");assertThat(result.message()).doesNotContain("do-not-return");
  }

  @Test void sshFailuresAreClassifiedWithoutReturningRawDetails()throws Exception{
    when(ssh.executeReadOnly(any(),anyString(),anyBoolean())).thenThrow(new com.jcraft.jsch.JSchException("Auth fail for user SECRET_PASSWORD"));
    assertThat(probe.inspect(server(),service(),environment("DOCKER_RUN")).message()).isEqualTo("SSH 身份认证失败，请检查服务器账号及密码或密钥。");
    doThrow(new java.net.SocketTimeoutException("target SECRET_PASSWORD timeout")).when(ssh).executeReadOnly(any(),anyString(),anyBoolean());
    assertThat(probe.inspect(server(),service(),environment("DOCKER_RUN")).message()).isEqualTo("SSH 运行检查超时，请检查网络或稍后重试。");
  }
  @Test void readOnlySshOutputFailsClosedAtByteLimit()throws Exception{
    SshClient.ProbeOutput output=new SshClient.ProbeOutput();
    byte[] data="x".repeat(SshClient.ProbeOutput.LIMIT).getBytes(StandardCharsets.UTF_8);output.write(data);
    assertThat(output.overflowed()).isFalse();assertThat(output.text()).hasSize(SshClient.ProbeOutput.LIMIT);
    output.write('y');assertThat(output.overflowed()).isTrue();assertThatThrownBy(output::text).isInstanceOf(IOException.class).hasMessageContaining("大小限制");
  }
}