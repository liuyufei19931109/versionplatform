package com.local.release.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity @Table(name="deployment_server")
public class DeploymentServer extends BaseEntity {
  @Column(nullable=false,length=100) private String name;
  @Column(nullable=false,length=200) private String host;
  @Column(nullable=false) private int port=22;
  @Column(nullable=false,length=80) private String username="root";
  @Column(nullable=false,length=20) private String authType="SSH_KEY";
  // Nullable additions keep existing rows untouched; missing mode means the original direct path.
  @Column(length=20) private String connectionMode;
  private Integer sshConnectTimeoutSeconds;
  private Integer sshCommandTimeoutSeconds;
  @Column(length=1000) private String businessUrl;
  @Column(length=500) private String credentialHint;
  @Column(length=500) private String privateKeyPath;
  @Column(length=1000) private String encryptedPassword;
  @Column(length=500) private String description;
  @Column(length=500) private String composeDirectory;
  @Column(length=300) private String composeFile="docker-compose.yml";
  private boolean composeUseSudo;
  private boolean enabled=true;
  public String getName(){return name;} public void setName(String v){name=v;}
  public String getHost(){return host;} public void setHost(String v){host=v;}
  public int getPort(){return port;} public void setPort(int v){port=v;}
  public String getUsername(){return username;} public void setUsername(String v){username=v;}
  public String getAuthType(){return authType==null?"SSH_KEY":authType;} public void setAuthType(String v){authType=v==null?"SSH_KEY":v;}
  public String getConnectionMode(){return connectionMode==null?"DIRECT":connectionMode;} public void setConnectionMode(String v){connectionMode=v;}
  public Integer getSshConnectTimeoutSeconds(){return sshConnectTimeoutSeconds;} public void setSshConnectTimeoutSeconds(Integer v){sshConnectTimeoutSeconds=v;}
  public Integer getSshCommandTimeoutSeconds(){return sshCommandTimeoutSeconds;} public void setSshCommandTimeoutSeconds(Integer v){sshCommandTimeoutSeconds=v;}
  public String getBusinessUrl(){return businessUrl;} public void setBusinessUrl(String v){businessUrl=v;}
  public String getCredentialHint(){return credentialHint;} public void setCredentialHint(String v){credentialHint=v;}
  public String getPrivateKeyPath(){return privateKeyPath;} public void setPrivateKeyPath(String v){privateKeyPath=v;}
  @JsonIgnore public String getEncryptedPassword(){return encryptedPassword;} public void setEncryptedPassword(String v){encryptedPassword=v;}
  public String getDescription(){return description;} public void setDescription(String v){description=v;}
  public String getComposeDirectory(){return composeDirectory;} public void setComposeDirectory(String v){composeDirectory=v;}
  public String getComposeFile(){return composeFile;} public void setComposeFile(String v){composeFile=v;}
  public boolean isComposeUseSudo(){return composeUseSudo;} public void setComposeUseSudo(boolean v){composeUseSudo=v;}
  public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
}
