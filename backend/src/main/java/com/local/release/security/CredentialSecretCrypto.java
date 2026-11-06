package com.local.release.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class CredentialSecretCrypto {
  private static final SecureRandom RANDOM=new SecureRandom();
  private final Path keyPath;private SecretKeySpec key;
  public CredentialSecretCrypto(@Value("${release.credential-key-file:./data/credential-encryption.key}") String keyFile){keyPath=Path.of(keyFile).toAbsolutePath().normalize();}
  @PostConstruct void initialize()throws Exception{byte[] bytes;if(Files.exists(keyPath))bytes=Base64.getDecoder().decode(Files.readString(keyPath,StandardCharsets.UTF_8).trim());else{bytes=new byte[32];RANDOM.nextBytes(bytes);if(keyPath.getParent()!=null)Files.createDirectories(keyPath.getParent());Files.writeString(keyPath,Base64.getEncoder().encodeToString(bytes),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}if(bytes.length!=32)throw new IllegalStateException("凭据加密密钥格式不正确");key=new SecretKeySpec(bytes,"AES");}
  public String encrypt(String plaintext){try{byte[] iv=new byte[12];RANDOM.nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));byte[] encrypted=cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));byte[] result=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,result,0,iv.length);System.arraycopy(encrypted,0,result,iv.length,encrypted.length);return Base64.getEncoder().encodeToString(result);}catch(Exception e){throw new IllegalStateException("无法加密服务器密码",e);}}
  public String decrypt(String encoded){try{byte[] input=Base64.getDecoder().decode(encoded);byte[] iv=new byte[12],encrypted=new byte[input.length-12];System.arraycopy(input,0,iv,0,12);System.arraycopy(input,12,encrypted,0,encrypted.length);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));return new String(cipher.doFinal(encrypted),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("无法读取服务器密码",e);}}
}
