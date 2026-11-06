package com.local.release.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.DeploymentServer;
import com.local.release.repo.*;
import com.local.release.security.CredentialSecretCrypto;
import com.local.release.service.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.*;

class ServerConnectionModeTest {
  final DeploymentServerRepository repository=mock(DeploymentServerRepository.class);
  final ServiceEnvironmentRepository environments=mock(ServiceEnvironmentRepository.class);
  final CredentialSecretCrypto crypto=mock(CredentialSecretCrypto.class);
  final SshClient ssh=mock(SshClient.class);
  final ServerController controller=new ServerController(repository,environments,crypto,ssh);
  final ObjectMapper mapper=new ObjectMapper();
  MockMvc mvc;
  @BeforeEach void setup(){when(repository.save(any())).thenAnswer(c->c.getArgument(0));mvc=standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();}
  ServerController.ServerRequest request(Map<String,Object> changes){
    Map<String,Object> value=new HashMap<>(Map.of("name","节点","host","host.example","username","ubuntu","port",22,"authType","SSH_KEY"));
    value.putAll(changes);return mapper.convertValue(value,ServerController.ServerRequest.class);
  }
  @Test void legacyRequestKeepsDirectSettingsAndDoesNotInventProxyConfiguration(){
    var result=controller.create(request(Map.of("privateKeyPath","/keys/old")));
    assertThat(result.connectionMode()).isEqualTo("DIRECT");assertThat(result.username()).isEqualTo("ubuntu");assertThat(result.privateKeyPath()).isEqualTo("/keys/old");
    assertThat(result.sshConnectTimeoutSeconds()).isNull();assertThat(result.sshCommandTimeoutSeconds()).isNull();verifyNoInteractions(ssh);
    assertThat(new DeploymentServer().getConnectionMode()).isEqualTo("DIRECT");
  }
  @Test void configuredAliasUsesManagedKeyAndDoesNotRequireWebUsername(){
    var changes=new HashMap<String,Object>(Map.of("connectionMode","SSH_CONFIG","host","example-node-01","privateKeyPath","/ignored","sshConnectTimeoutSeconds",60,"sshCommandTimeoutSeconds",600));
    changes.put("username",null);
    var result=controller.create(request(changes));
    assertThat(result.host()).isEqualTo("example-node-01");assertThat(result.connectionMode()).isEqualTo("SSH_CONFIG");assertThat(result.privateKeyPath()).isNull();assertThat(result.passwordConfigured()).isFalse();verifyNoInteractions(crypto,ssh);
  }
  @Test void oldClientUpdateCannotSilentlySwitchAnAliasToDirect(){
    DeploymentServer server=new DeploymentServer();server.setConnectionMode("SSH_CONFIG");when(repository.findById(7L)).thenReturn(Optional.of(server));
    assertThat(controller.update(7L,request(Map.of("host","example-node-01"))).connectionMode()).isEqualTo("SSH_CONFIG");
  }
  @Test void rejectsExecutableAliasesPasswordsAndUnsafeOrInvalidLimits(){
    for(Map<String,Object> changes:List.of(
      Map.<String,Object>of("connectionMode","SSH_CONFIG","host","-oProxyCommand=evil"),
      Map.<String,Object>of("connectionMode","SSH_CONFIG","host","prod host"),
      Map.<String,Object>of("connectionMode","SSH_CONFIG","authType","PASSWORD","password","secret"),
      Map.<String,Object>of("sshConnectTimeoutSeconds",301),Map.<String,Object>of("sshCommandTimeoutSeconds",0),
      Map.<String,Object>of("businessUrl","javascript:alert(1)"),Map.<String,Object>of("businessUrl","https://user:password@example.com")))
      assertThatThrownBy(()->controller.create(request(changes))).isInstanceOf(IllegalArgumentException.class);
    verify(repository,never()).save(any());verifyNoInteractions(crypto,ssh);
  }
  @Test void testSeparatesComposeAfterConnectionAndCapsEachCheck()throws Exception{
    DeploymentServer server=new DeploymentServer();server.setComposeDirectory("/opt/app");server.setComposeUseSudo(true);when(repository.findById(7L)).thenReturn(Optional.of(server));
    when(ssh.executeCheck(eq(server),anyString(),any(),eq(true),eq(30L))).thenReturn("api\nui\n");
    assertThat(controller.test(7L)).containsEntry("sshStatus","SUCCESS").containsEntry("composeStatus","SUCCESS").containsEntry("services","api\nui");
    verify(ssh).executeCheck(eq(server),eq("true"),any(),eq(false),eq(30L));
  }
  @Test void connectionFailureReturnsSafeStructuredStage()throws Exception{
    DeploymentServer server=new DeploymentServer();when(repository.findById(7L)).thenReturn(Optional.of(server));
    when(ssh.executeCheck(any(),anyString(),any(),anyBoolean(),anyLong())).thenThrow(new SshExecutionException(SshExecutionException.Code.GOOGLE_AUTH,"Google 身份认证失效",255,false));
    mvc.perform(post("/api/servers/7/test")).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GOOGLE_AUTH")).andExpect(jsonPath("$.sshStatus").value("FAILED")).andExpect(jsonPath("$.composeStatus").value("NOT_RUN"));
  }
  @Test void composeFailurePreservesConnectionSuccessWithoutLeakingRawOutput()throws Exception{
    DeploymentServer server=new DeploymentServer();server.setComposeDirectory("/opt/app");when(repository.findById(7L)).thenReturn(Optional.of(server));
    when(ssh.executeCheck(any(),startsWith("cd "),any(),anyBoolean(),anyLong())).thenThrow(new IllegalStateException("password=do-not-echo"));
    String body=mvc.perform(post("/api/servers/7/test")).andExpect(status().isBadGateway()).andExpect(jsonPath("$.sshStatus").value("SUCCESS")).andExpect(jsonPath("$.composeStatus").value("FAILED")).andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("do-not-echo");
  }
}
