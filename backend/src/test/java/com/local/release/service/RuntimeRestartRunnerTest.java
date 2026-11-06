package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeRestartRunnerTest {
  final SshClient ssh=mock(SshClient.class);
  final RuntimeRestartRunner runner=new RuntimeRestartRunner(ssh,new ObjectMapper());
  final AppService app=new AppService();
  final ServiceEnvironment env=new ServiceEnvironment();
  final DeploymentServer server=new DeploymentServer();
  static final String OLD="a".repeat(64),NEW="c".repeat(64),IMAGE="sha256:"+"b".repeat(64);
  @BeforeEach void setup(){env.setDeploymentMode("DOCKER_COMPOSE");env.setComposeService("ui");env.setContainerPort(80);server.setComposeDirectory("/data/example");}
  @Test void tracksNewContainersInsteadOfHealthCheckingRemovedOnes()throws Exception{
    when(ssh.execute(any(),anyString(),any())).thenReturn("{\"success\":true,\"containers\":{\""+NEW+"\":\""+IMAGE+"\"}}");
    assertThat(runner.restart(app,env,server,Map.of(OLD,IMAGE))).containsExactlyEntriesOf(Map.of(NEW,IMAGE));
  }
  @Test void sameIdMissingIdentityAndChangedImageCannotClaimSuccess()throws Exception{
    for(String result:List.of("{\"success\":true}","{\"success\":true,\"containers\":{\""+OLD+"\":\""+IMAGE+"\"}}",
      "{\"success\":true,\"containers\":{\""+NEW+"\":\"sha256:"+"d".repeat(64)+"\"}}")){
      when(ssh.execute(any(),anyString(),any())).thenReturn(result);
      assertThatThrownBy(()->runner.restart(app,env,server,Map.of(OLD,IMAGE))).isInstanceOf(IllegalArgumentException.class);
    }
  }
  @Test void permissionFailureNamesFileWithoutEchoingSecrets()throws Exception{
    when(ssh.execute(any(),anyString(),any())).thenReturn("{\"success\":false,\"code\":\"CONFIG_PERMISSION\",\"path\":\"/data/example/env/ui.env\",\"stderr\":\"SECRET\"}");
    assertThatThrownBy(()->runner.restart(app,env,server,Map.of(OLD,IMAGE))).hasMessageContaining("尚未重建").hasMessageContaining("/data/example/env/ui.env").hasMessageNotContaining("SECRET");
  }
  @Test void composeSudoIsPreserved()throws Exception{
    server.setComposeUseSudo(true);
    when(ssh.executeSudo(any(),anyString(),any())).thenReturn("{\"success\":true,\"containers\":{\""+NEW+"\":\""+IMAGE+"\"}}");
    runner.restart(app,env,server,Map.of(OLD,IMAGE));verify(ssh).executeSudo(eq(server),anyString(),any());verify(ssh,never()).execute(any(),anyString(),any());
  }
  @Test void unsupportedModeHasNoOldRestartFallback(){
    env.setDeploymentMode("DOCKER_RUN");
    assertThatThrownBy(()->runner.configuration("restart",app,env,server,Map.of(OLD,IMAGE))).hasMessageContaining("Docker Compose");verifyNoInteractions(ssh);
  }
}
