package com.local.release.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity @Table(name="registry_credential",uniqueConstraints=@UniqueConstraint(columnNames="registryServer"))
public class RegistryCredential extends BaseEntity {
  @Column(nullable=false,length=200) private String registryServer;
  @Column(nullable=false,length=200) private String username;
  @JsonIgnore @Lob @Column(nullable=false,columnDefinition="TEXT") private String encryptedPassword;
  protected RegistryCredential(){}
  public RegistryCredential(String server,String user,String password){registryServer=server;username=user;encryptedPassword=password;}
  public String getRegistryServer(){return registryServer;} public void setRegistryServer(String v){registryServer=v;}
  public String getUsername(){return username;} public void setUsername(String v){username=v;}
  public String getEncryptedPassword(){return encryptedPassword;} public void setEncryptedPassword(String v){encryptedPassword=v;}
}
