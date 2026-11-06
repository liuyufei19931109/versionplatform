package com.local.release.service;

import com.local.release.domain.RegistryCredential;
import com.local.release.repo.RegistryCredentialRepository;
import com.local.release.security.CredentialSecretCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
public class RegistryCredentialBootstrap {
  @Value("${release.registry-server:}") String server;
  @Value("${release.registry-username:}") String username;
  @Value("${release.registry-password:}") String password;
  private final RegistryCredentialRepository credentials;private final CredentialSecretCrypto crypto;
  public RegistryCredentialBootstrap(RegistryCredentialRepository credentials,CredentialSecretCrypto crypto){this.credentials=credentials;this.crypto=crypto;}
  @Order(-100) @EventListener(ApplicationReadyEvent.class) public void seed(){if(password==null||password.isBlank())return;String host=normalize(server);if(host.isBlank()||username==null||username.isBlank())throw new IllegalArgumentException("镜像仓库初始化信息不完整");RegistryCredential value=credentials.findByRegistryServer(host).orElseGet(()->new RegistryCredential(host,username.trim(),crypto.encrypt(password)));value.setUsername(username.trim());value.setEncryptedPassword(crypto.encrypt(password));credentials.save(value);}
  private String normalize(String value){if(value==null)return "";return value.trim().replaceFirst("(?i)^https?://","").replaceAll("/+$","");}
}
