package com.local.release.web;

import com.local.release.domain.AdminUser;
import com.local.release.repo.AdminUserRepository;
import com.local.release.security.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.*;

@RestController @RequestMapping("/api/users")
public class UserController {
  private static final Set<String> ALLOWED_MENUS=new LinkedHashSet<>(AuthController.ALL_MENUS);
  private final AdminUserRepository users;private final PasswordEncoder encoder;private final TotpService totp;private final TokenService tokens;
  public UserController(AdminUserRepository u,PasswordEncoder e,TotpService a,TokenService t){users=u;encoder=e;totp=a;tokens=t;}
  public record UserRequest(String username,String displayName,String password,List<String> permissions,Boolean enabled){}
  public record UserResponse(Long id,String username,String displayName,boolean admin,boolean enabled,List<String> permissions,boolean totpEnabled,LocalDateTime lastLoginAt,LocalDateTime createdAt){}

  @GetMapping public List<UserResponse> list(Principal principal){admin(principal);return users.findAll().stream().map(this::response).toList();}
  @PostMapping public UserResponse create(@RequestBody UserRequest request,Principal principal){admin(principal);String username=required(request.username(),"请填写用户名").toLowerCase(Locale.ROOT);if(!username.matches("[a-z0-9_.-]{3,32}"))throw new IllegalArgumentException("用户名只能使用 3-32 位字母、数字、点、横线或下划线");if(users.existsByUsername(username))throw new IllegalArgumentException("用户名已存在");String password=required(request.password(),"请设置初始密码");if(password.length()<6)throw new IllegalArgumentException("初始密码至少 6 位");AdminUser user=new AdminUser(username,encoder.encode(password));user.setDisplayName(request.displayName());user.setEnabled(request.enabled()==null||request.enabled());user.setMenuPermissions(sanitize(request.permissions()));return response(users.save(user));}
  @PutMapping("/{id}") public UserResponse update(@PathVariable Long id,@RequestBody UserRequest request,Principal principal){admin(principal);AdminUser user=find(id);if(user.isAdmin()&&Boolean.FALSE.equals(request.enabled()))throw new IllegalArgumentException("不能停用 admin");user.setDisplayName(request.displayName());user.setEnabled(request.enabled()==null||request.enabled());if(!user.isAdmin())user.setMenuPermissions(sanitize(request.permissions()));if(request.password()!=null&&!request.password().isBlank()){if(request.password().length()<6)throw new IllegalArgumentException("密码至少 6 位");user.setPasswordHash(encoder.encode(request.password()));tokens.revokeUser(user.getUsername());}if(!user.isEnabled())tokens.revokeUser(user.getUsername());return response(users.save(user));}
  private AdminUser admin(Principal principal){AdminUser user=users.findByUsername(principal.getName()).orElseThrow(()->new IllegalArgumentException("用户不存在"));if(!user.isAdmin())throw new IllegalArgumentException("仅 admin 可以管理用户");return user;}
  private AdminUser find(Long id){return users.findById(id).orElseThrow(()->new IllegalArgumentException("用户不存在"));}
  private UserResponse response(AdminUser u){List<String> permissions=u.isAdmin()?AuthController.ALL_MENUS:new ArrayList<>(MenuPermissions.effective(u.menuPermissionSet()));return new UserResponse(u.getId(),u.getUsername(),u.getDisplayName(),u.isAdmin(),u.isEnabled(),permissions,totp.isEnabled(u.getUsername()),u.getLastLoginAt(),u.getCreatedAt());}
  private List<String> sanitize(List<String> values){return MenuPermissions.forStorage(values);}
  private String required(String value,String message){if(value==null||value.isBlank())throw new IllegalArgumentException(message);return value.trim();}
}
