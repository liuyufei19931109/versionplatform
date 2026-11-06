package com.local.release.web;

import com.local.release.domain.AdminUser;
import com.local.release.repo.AdminUserRepository;
import com.local.release.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.*;

@RestController @RequestMapping("/api/auth")
public class AuthController {
  public static final List<String> ALL_MENUS=MenuPermissions.ALL;
  private final AdminUserRepository users;private final PasswordEncoder encoder;private final TokenService tokens;private final TotpService totp;
  public AuthController(AdminUserRepository u,PasswordEncoder e,TokenService t,TotpService a){users=u;encoder=e;tokens=t;totp=a;}
  public record LoginRequest(@NotBlank String username,@NotBlank String password,String totpCode){}
  public record LoginResponse(String token,String username,String displayName,boolean admin,List<String> permissions,boolean requiresTotp,boolean requiresTotpSetup,int expiresIn){}
  public record CurrentUser(String username,String displayName,boolean admin,List<String> permissions,boolean totpEnabled){}

  @PostMapping("/login") public LoginResponse login(@Valid @RequestBody LoginRequest request){AdminUser user=users.findByUsername(request.username()).filter(AdminUser::isEnabled).orElseThrow(()->new IllegalArgumentException("用户名或密码错误"));if(!encoder.matches(request.password(),user.getPasswordHash()))throw new IllegalArgumentException("用户名或密码错误");List<String> permissions=permissions(user);if(!totp.isEnabled(user.getUsername()))return new LoginResponse(tokens.issueSetup(user.getUsername()),user.getUsername(),user.getDisplayName(),user.isAdmin(),permissions,false,true,900);if(request.totpCode()==null||request.totpCode().isBlank())return new LoginResponse(null,user.getUsername(),user.getDisplayName(),user.isAdmin(),permissions,true,false,0);totp.verifyAndConsume(user.getUsername(),request.totpCode());user.setLastLoginAt(LocalDateTime.now());users.save(user);return new LoginResponse(tokens.issue(user.getUsername()),user.getUsername(),user.getDisplayName(),user.isAdmin(),permissions,false,false,43200);}
  @GetMapping("/me") public CurrentUser me(Principal principal){AdminUser user=users.findByUsername(principal.getName()).orElseThrow(()->new IllegalArgumentException("用户不存在"));return current(user);}
  public CurrentUser current(AdminUser user){return new CurrentUser(user.getUsername(),user.getDisplayName(),user.isAdmin(),permissions(user),totp.isEnabled(user.getUsername()));}
  private List<String> permissions(AdminUser user){return user.isAdmin()?ALL_MENUS:new ArrayList<>(MenuPermissions.effective(user.menuPermissionSet()));}
}
