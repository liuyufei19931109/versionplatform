package com.local.release.security;

import com.local.release.domain.AdminUser;
import com.local.release.repo.AdminUserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.local.release.service.MobileReleaseService;
import com.local.release.service.AppIntegrationAccessService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class BearerTokenFilter extends OncePerRequestFilter {
  private final TokenService tokens;private final AdminUserRepository users;
  private final ObjectProvider<MobileReleaseService> mobile;
  private final ObjectProvider<AppIntegrationAccessService> integrations;
  public BearerTokenFilter(TokenService tokens,AdminUserRepository users){this(tokens,users,null);}
  public BearerTokenFilter(TokenService tokens,AdminUserRepository users,ObjectProvider<MobileReleaseService> mobile){this(tokens,users,mobile,null);}
  @Autowired public BearerTokenFilter(TokenService tokens,AdminUserRepository users,ObjectProvider<MobileReleaseService> mobile,ObjectProvider<AppIntegrationAccessService> integrations){this.tokens=tokens;this.users=users;this.mobile=mobile;this.integrations=integrations;}
  @Override protected boolean shouldNotFilter(HttpServletRequest request){
    String path=request.getRequestURI();
    String header=request.getHeader("Authorization");
    if(header!=null&&header.startsWith("Bearer mapp_"))return false;
    return path.equals("/api/auth/login")||path.equals("/api/health")||path.equals("/error");
  }
  @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
    String header=req.getHeader("Authorization");
    if(header!=null&&header.startsWith("Bearer ")){
      if(header.startsWith("Bearer mapp_")){
        res.setHeader("Cache-Control","no-store");
        AppIntegrationAccessService.TokenPrincipal access=integrations==null?null:integrations.getObject().validateAccess(header.substring(7),req.getMethod(),req.getRequestURI());
        if(access==null){SecurityContextHolder.clearContext();deny(res,401,"App 对接凭据无效、已撤销或没有当前操作权限","APP_INTEGRATION_ACCESS_DENIED");return;}
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(access,null,List.of(new SimpleGrantedAuthority("ROLE_APP_INTEGRATION"))));
        chain.doFilter(req,res);return;
      }
      if(header.startsWith("Bearer mrel_")){
        MobileReleaseService.TokenPrincipal access=mobile==null?null:mobile.getObject().validateAccess(header.substring(7),req.getMethod(),req.getRequestURI());
        if(access==null){SecurityContextHolder.clearContext();deny(res,401,"分发凭证无效、已过期或没有当前操作权限","DELIVERY_ACCESS_DENIED");return;}
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(access.name(),null,List.of(new SimpleGrantedAuthority("ROLE_MOBILE_DELIVERY"))));
        chain.doFilter(req,res);return;
      }
      TokenService.SessionInfo session=tokens.validate(header.substring(7));
      if(session==null){SecurityContextHolder.clearContext();deny(res,401,"登录已过期，请重新登录","SESSION_EXPIRED");return;}
      if(session!=null){AdminUser user=users.findByUsername(session.username()).orElse(null);if(user==null||!user.isEnabled()){deny(res,401,"账号已停用或不存在","ACCOUNT_DISABLED");return;}String path=req.getRequestURI();if(session.setupOnly()&&!setupPath(path)){deny(res,403,"首次登录请先绑定 Google Authenticator","TOTP_SETUP_REQUIRED");return;}if(!user.isAdmin()&&!allowed(user,req)){deny(res,403,"没有当前菜单的访问权限","PERMISSION_DENIED");return;}List<SimpleGrantedAuthority> authorities=List.of(new SimpleGrantedAuthority(user.isAdmin()?"ROLE_ADMIN":"ROLE_USER"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getUsername(),null,authorities));}
    }
    chain.doFilter(req,res);
  }
  private boolean setupPath(String path){return path.equals("/api/auth/me")||path.startsWith("/api/auth/totp/");}
  private boolean allowed(AdminUser user,HttpServletRequest request){
    String path=request.getRequestURI(),method=request.getMethod();
    Set<String> permissions=MenuPermissions.effective(user.menuPermissionSet());
    if(path.startsWith("/api/auth/"))return true;
    if(path.startsWith("/api/users"))return false;
    if(path.equals("/api/miniprograms")||path.startsWith("/api/miniprograms/"))return permissions.contains("miniprograms");
    if(path.equals("/api/versions")||path.startsWith("/api/versions/"))return method.equals("GET")&&permissions.contains("versions");
    if(path.matches("/api/apps/services/[^/]+/integration-access"))return method.equals("GET")&&permissions.contains("apps");
    if(path.equals("/api/apps")||path.startsWith("/api/apps/"))return permissions.contains("apps");
    if(path.startsWith("/api/mobile/"))return permissions.contains("apps");
    if(path.matches("/api/tasks/[0-9]+/branch-checks"))return method.equals("GET")&&any(permissions,"builds","release","releases");
    if(path.equals("/api/runtime/restart-preview")||path.startsWith("/api/runtime/restarts")&&!method.equals("GET"))return method.equals("POST")&&(path.equals("/api/runtime/restart-preview")||path.equals("/api/runtime/restarts")||path.matches("/api/runtime/restarts/[1-9][0-9]*/cancel"))&&permissions.contains("runtime")&&permissions.contains("release");
    if(path.equals("/api/runtime")||path.startsWith("/api/runtime/"))return permissions.contains("runtime");
    if(path.equals("/api/dashboard"))return permissions.contains("dashboard");
    if(path.startsWith("/api/services"))return method.equals("GET")?any(permissions,"services","builds","release"):permissions.contains("services");
    if(path.startsWith("/api/servers"))return method.equals("GET")?any(permissions,"servers","services","release"):permissions.contains("servers");
    if(path.equals("/api/builds/queue")&&method.equals("GET"))return any(permissions,"builds","dashboard","runtime","release");
    if(path.startsWith("/api/builds"))return permissions.contains("builds");
    if(path.startsWith("/api/artifacts"))return method.equals("GET")?any(permissions,"builds","release"):permissions.contains("builds");
    if(path.startsWith("/api/releases"))return method.equals("GET")?any(permissions,"release","releases"):permissions.contains("release");
    return true;
  }
  private boolean any(Set<String> values,String... keys){return Arrays.stream(keys).anyMatch(values::contains);}
  private void deny(HttpServletResponse response,int status,String message,String code)throws IOException{response.setStatus(status);response.setCharacterEncoding(StandardCharsets.UTF_8.name());response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"message\":\""+message+"\",\"code\":\""+code+"\"}");}
}
