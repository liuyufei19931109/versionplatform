package com.local.release.service;

import com.local.release.domain.DeploymentServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class SshConnectionResolverTest {
  @TempDir Path directory;
  DeploymentServer server(){DeploymentServer server=new DeploymentServer();server.setHost("production-node");server.setUsername("deploy");return server;}
  SshConnectionResolver configured()throws Exception{Path file=directory.resolve("config");Files.writeString(file,"Host production-node\n  HostName 10.0.0.1\n  User ops\n  Port 2222\n  ProxyCommand /usr/bin/gcloud compute start-iap-tunnel vm 22 --listen-on-stdin\n");return new SshConnectionResolver(file.toString());}

  @Test void legacyNodesRetainExplicitUserPortAndIdentity()throws Exception{
    DeploymentServer server=server();server.setPrivateKeyPath("/keys/deployment");server.setPort(2222);
    var connection=new SshConnectionResolver().resolve(server);
    assertThat(connection.configured()).isFalse();assertThat(connection.connectSeconds()).isEqualTo(10);assertThat(connection.commandSeconds()).isEqualTo(600);
    assertThat(connection.command()).containsSubsequence("-p","2222","-i","/keys/deployment","deploy@production-node").doesNotContain("-F","StrictHostKeyChecking=yes");
  }
  @Test void configAliasDoesNotOverrideConfiguredIdentityUserPortOrProxy()throws Exception{
    DeploymentServer server=server();server.setConnectionMode("SSH_CONFIG");server.setPrivateKeyPath("/wrong/ignored");server.setUsername("wrong");server.setPort(9999);
    var command=configured().baseCommand(server);
    assertThat(command).contains("-F","BatchMode=yes","ConnectTimeout=60","StrictHostKeyChecking=yes","ControlPath=none","PermitLocalCommand=no","production-node");
    assertThat(command).doesNotContain("-i","-p","wrong@production-node","/wrong/ignored","9999");
  }
  @Test void aliasRejectsArgumentsShellSyntaxAndControlCharacters()throws Exception{
    DeploymentServer server=server();server.setConnectionMode("SSH_CONFIG");var resolver=configured();
    for(String host:new String[]{"-F","prod --x","ops@prod","prod;echo","prod\nHost *",""}){server.setHost(host);assertThatThrownBy(()->resolver.baseCommand(server)).isInstanceOf(SshExecutionException.class);}
  }
  @Test void configuredModeRequiresReadableOperationsConfig(){DeploymentServer server=server();server.setConnectionMode("SSH_CONFIG");assertThatThrownBy(()->new SshConnectionResolver(directory.resolve("missing").toString()).resolve(server)).hasMessageContaining("CONFIGURATION").hasMessageContaining("受控 SSH 配置");}
  @Test void passwordCannotSilentlyBypassConfiguredTunnel(){DeploymentServer server=server();server.setConnectionMode("SSH_CONFIG");server.setAuthType("PASSWORD");assertThatThrownBy(()->new SshConnectionResolver().resolve(server)).hasMessageContaining("仅支持 SSH Key");}
  @Test void acceptsSeparateBoundedTimeouts(){DeploymentServer server=server();server.setSshConnectTimeoutSeconds(90);server.setSshCommandTimeoutSeconds(900);assertThat(SshConnectionResolver.connectSeconds(server)).isEqualTo(90);assertThat(SshConnectionResolver.commandSeconds(server)).isEqualTo(900);server.setSshConnectTimeoutSeconds(0);assertThatThrownBy(()->SshConnectionResolver.connectSeconds(server)).isInstanceOf(SshExecutionException.class);server.setSshCommandTimeoutSeconds(10000);assertThatThrownBy(()->SshConnectionResolver.commandSeconds(server)).isInstanceOf(SshExecutionException.class);}
  @Test void directLoginNameCannotBecomeALocalSshOption(){DeploymentServer server=server();server.setUsername("-oProxyCommand=never");assertThatThrownBy(()->new SshConnectionResolver().baseCommand(server)).isInstanceOf(SshExecutionException.class);server.setUsername("DOMAIN\\operator");assertThat(new SshConnectionResolver().baseCommand(server)).contains("DOMAIN\\operator@production-node");}
  @Test void effectiveConfigParserKeepsOnlyNeededKeys(){var values=SshConnectionResolver.effectiveValues("host production\nhostname 10.1.2.3\nuser oslogin_user\nport 22\nproxycommand /usr/bin/gcloud --account fake\nidentityfile /private/key\nsetenv SECRET=fixture\n");assertThat(values).containsEntry("hostname","10.1.2.3").containsEntry("user","oslogin_user").containsEntry("port","22").containsKey("proxycommand").doesNotContainKeys("identityfile","setenv");}
}
