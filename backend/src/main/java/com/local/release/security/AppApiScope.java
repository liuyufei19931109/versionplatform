package com.local.release.security;

import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.ServiceType;
import com.local.release.repo.*;
import jakarta.servlet.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import java.util.*;

/** Object-level isolation supplements menu checks; aliases cannot grant access to the other service family. */
@Component
public class AppApiScope implements HandlerInterceptor {
  private final AppServiceRepository services;private final ReleaseTaskRepository tasks;private final ArtifactRepository artifacts;private final MobileArtifactRepository mobile;
  public AppApiScope(AppServiceRepository services,ReleaseTaskRepository tasks,ArtifactRepository artifacts,MobileArtifactRepository mobile){this.services=services;this.tasks=tasks;this.artifacts=artifacts;this.mobile=mobile;}
  public static boolean appRequest(){return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes&&appPath(attributes.getRequest().getRequestURI());}
  public static boolean appPath(String path){return path.startsWith("/api/apps/")||path.startsWith("/api/mobile/");}
  public static boolean isApp(AppService service){return service.getServiceType()==ServiceType.FLUTTER_ANDROID;}
  public static boolean isApp(ReleaseTask task){return "MOBILE".equals(task.getDeliveryType());}
  public boolean isApp(Artifact artifact){
    if(mobile.existsByArtifactId(artifact.getId()))return true;
    if(artifact.getBuildTaskId()!=null){Optional<ReleaseTask> build=tasks.findById(artifact.getBuildTaskId());if(build.isPresent())return isApp(build.get());}
    return artifact.getServiceId()!=null&&services.findById(artifact.getServiceId()).map(AppApiScope::isApp).orElse(false);
  }
  public AppService service(Long id,boolean app){AppService value=services.findById(id).orElseThrow(AppApiScope::denied);require(isApp(value)==app);return value;}
  public ReleaseTask task(Long id,boolean app){ReleaseTask value=tasks.findById(id).orElseThrow(AppApiScope::denied);require(isApp(value)==app);return value;}
  public Artifact artifact(Long id,boolean app){Artifact value=artifacts.findById(id).orElseThrow(AppApiScope::denied);require(isApp(value)==app);return value;}
  public boolean taskVisible(Long id,boolean app){return id!=null&&tasks.findById(id).map(task->isApp(task)==app).orElse(false);}
  @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
    String path=request.getRequestURI();boolean app=appPath(path);
    // These APIs use literal names and numeric IDs; reject alternate encodings before scope matching.
    require(!path.contains("%")&&!path.contains(";")&&!path.contains("//"));
    // External delivery tokens are already checked against an exact release, verb and endpoint by the bearer filter.
    var auth=SecurityContextHolder.getContext().getAuthentication();
    if(auth!=null&&auth.getAuthorities().stream().anyMatch(a->Set.of("ROLE_MOBILE_DELIVERY","ROLE_APP_INTEGRATION").contains(a.getAuthority())))return true;
    String canonical=path.startsWith("/api/apps/")?"/api/"+path.substring("/api/apps/".length()):path;
    if(path.equals("/api/apps/signing-profiles")&&request.getMethod().equals("GET"))return true;
    if(path.startsWith("/api/apps/")&&!(canonical.startsWith("/api/services")||canonical.startsWith("/api/builds")||canonical.startsWith("/api/artifacts")||canonical.startsWith("/api/releases")||canonical.startsWith("/api/tasks")||canonical.startsWith("/api/mobile/")))throw denied();
    Map<?,?> variables=request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?,?> values?values:Map.of();
    Long id=objectId(variables,"id","taskId","serviceId");
    if(id!=null){
      if(canonical.startsWith("/api/services/"))service(id,app);
      else if(canonical.startsWith("/api/builds/")||canonical.startsWith("/api/releases/")||canonical.startsWith("/api/tasks/"))task(id,app);
      else if(canonical.startsWith("/api/artifacts/"))artifact(id,app);
      else if(canonical.startsWith("/api/mobile/releases/"))task(id,true);
      else if(canonical.startsWith("/api/mobile/artifacts/"))artifact(id,true);
    }
    if(Set.of("/api/builds","/api/artifacts","/api/releases").contains(canonical)&&request.getParameter("serviceId")!=null){
      try{service(Long.valueOf(request.getParameter("serviceId")),app);}catch(NumberFormatException invalid){throw denied();}
    }
    // App delivery never executes the server-deployment controller's create/retry/cancel flows.
    if(app&&canonical.startsWith("/api/releases")&&!request.getMethod().equals("GET")&&(!canonical.contains("/merge/")&&!canonical.contains("/notifications/")))throw denied();
    return true;
  }
  private static Long objectId(Map<?,?> variables,String... names){for(String name:names)if(variables.containsKey(name))try{return Long.valueOf(String.valueOf(variables.get(name)));}catch(NumberFormatException invalid){throw denied();}return null;}
  public static void require(boolean allowed){if(!allowed)throw denied();}
  private static AccessDeniedException denied(){return new AccessDeniedException("当前入口无权访问此服务或任务");}
}
