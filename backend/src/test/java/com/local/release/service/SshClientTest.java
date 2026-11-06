package com.local.release.service;

import com.local.release.domain.DeploymentServer;
import com.local.release.security.CredentialSecretCrypto;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import com.jcraft.jsch.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshClientTest {
  @Test void previouslyCancelledNodeLoopCannotResolveOrStartAnotherConnection(){
    var crypto=mock(CredentialSecretCrypto.class);var resolver=mock(SshConnectionResolver.class);
    var client=new SshClient(crypto,resolver);var server=new DeploymentServer();server.setHost("never-start");
    Thread.currentThread().interrupt();
    try{assertThatThrownBy(()->client.execute(server,"true",Path.of("."))).isInstanceOf(InterruptedException.class);assertThat(Thread.currentThread().isInterrupted()).isTrue();verifyNoInteractions(crypto,resolver);}
    finally{Thread.interrupted();}
  }
  @Test void passwordHandshakeCancellationDoesNotOpenRemoteCommandChannel()throws Exception{
    var crypto=mock(CredentialSecretCrypto.class);when(crypto.decrypt("encrypted-fixture")).thenReturn("fixture-password");
    var session=mock(Session.class);doAnswer(call->{Thread.currentThread().interrupt();return null;}).when(session).connect(anyInt());
    var server=new DeploymentServer();server.setAuthType("PASSWORD");server.setHost("fixture");server.setEncryptedPassword("encrypted-fixture");
    try(var ignored=mockConstruction(JSch.class,(client,context)->when(client.getSession(anyString(),anyString(),anyInt())).thenReturn(session))){
      try{assertThatThrownBy(()->new SshClient(crypto).execute(server,"must-not-run",Path.of("."))).isInstanceOf(InterruptedException.class);verify(session,never()).openChannel(anyString());verify(session).disconnect();assertThat(Thread.currentThread().isInterrupted()).isTrue();}
      finally{Thread.interrupted();}
    }
  }
  @Test void passwordCancellationBeforeChannelConnectDoesNotStartCommand()throws Exception{
    var crypto=mock(CredentialSecretCrypto.class);when(crypto.decrypt("encrypted-fixture")).thenReturn("fixture-password");
    var session=mock(Session.class);var channel=mock(ChannelExec.class);when(session.openChannel("exec")).thenReturn(channel);
    doAnswer(call->{Thread.currentThread().interrupt();return null;}).when(channel).setErrStream(any(java.io.OutputStream.class));
    var server=new DeploymentServer();server.setAuthType("PASSWORD");server.setHost("fixture");server.setEncryptedPassword("encrypted-fixture");
    try(var ignored=mockConstruction(JSch.class,(client,context)->when(client.getSession(anyString(),anyString(),anyInt())).thenReturn(session))){
      try{assertThatThrownBy(()->new SshClient(crypto).execute(server,"must-not-run",Path.of("."))).isInstanceOf(InterruptedException.class);verify(channel,never()).connect(anyInt());verify(channel).disconnect();verify(session).disconnect();}
      finally{Thread.interrupted();}
    }
  }
  @Test void passwordCancellationDuringChannelConnectNeverReportsSuccess()throws Exception{
    var crypto=mock(CredentialSecretCrypto.class);when(crypto.decrypt("encrypted-fixture")).thenReturn("fixture-password");
    var session=mock(Session.class);var channel=mock(ChannelExec.class);when(session.openChannel("exec")).thenReturn(channel);
    doAnswer(call->{Thread.currentThread().interrupt();return null;}).when(channel).connect(anyInt());
    var server=new DeploymentServer();server.setAuthType("PASSWORD");server.setHost("fixture");server.setEncryptedPassword("encrypted-fixture");
    try(var ignored=mockConstruction(JSch.class,(client,context)->when(client.getSession(anyString(),anyString(),anyInt())).thenReturn(session))){
      try{assertThatThrownBy(()->new SshClient(crypto).execute(server,"already-started",Path.of("."))).isInstanceOf(InterruptedException.class);verify(channel,never()).getExitStatus();verify(channel).disconnect();verify(session).disconnect();}
      finally{Thread.interrupted();}
    }
  }
}
