package com.local.release.web;

import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.security.CredentialSecretCrypto;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController @RequestMapping("/api/settings/registry")
public class RegistrySettingsController {
  private final RegistryCredentialRepository credentials;private final AdminUserRepository users;private final CredentialSecretCrypto crypto;
  public RegistrySettingsController(RegistryCredentialRepository credentials,AdminUserRepository users,CredentialSecretCrypto crypto){this.credentials=credentials;this.users=users;this.crypto=crypto;}
  public record RegistryRequest(String registryServer,String username,String password){}
  public record RegistryResponse(String registryServer,String username,boolean passwordConfigured){}
  @GetMapping public RegistryResponse get(Principal principal){admin(principal);return credentials.findAll().stream().findFirst().map(this::response).orElse(new RegistryResponse("","",false));}
  @PutMapping public RegistryResponse save(@RequestBody RegistryRequest request,Principal principal){admin(principal);String server=normalize(required(request.registryServer(),"请填写镜像仓库地址"));String username=required(request.username(),"请填写镜像仓库用户名").trim();RegistryCredential value=credentials.findAll().stream().findFirst().orElse(null);boolean creating=value==null;if(creating)value=new RegistryCredential(server,username,crypto.encrypt(required(request.password(),"请填写镜像仓库密码")));value.setRegistryServer(server);value.setUsername(username);if(request.password()!=null&&!request.password().isBlank())value.setEncryptedPassword(crypto.encrypt(request.password()));else if(creating)throw new IllegalArgumentException("请填写镜像仓库密码");return response(credentials.save(value));}
  private RegistryResponse response(RegistryCredential value){return new RegistryResponse(value.getRegistryServer(),value.getUsername(),value.getEncryptedPassword()!=null&&!value.getEncryptedPassword().isBlank());}
  private void admin(Principal principal){AdminUser user=users.findByUsername(principal.getName()).orElseThrow();if(!user.isAdmin())throw new IllegalArgumentException("仅 admin 可以管理镜像仓库凭证");}
  private String required(String value,String message){if(value==null||value.isBlank())throw new IllegalArgumentException(message);return value;}
  private String normalize(String value){return value.trim().replaceFirst("(?i)^https?://","").replaceAll("/+$","");}
}
