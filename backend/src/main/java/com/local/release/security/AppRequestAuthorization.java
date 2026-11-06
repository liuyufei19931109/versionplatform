package com.local.release.security;

import com.local.release.domain.ModelEnums.ServiceType;
import com.local.release.web.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.lang.reflect.Type;

/** Inspect the actual deserialized DTO before a controller can mutate data or submit a job. */
@ControllerAdvice
public class AppRequestAuthorization extends RequestBodyAdviceAdapter {
  private final AppApiScope scope;
  public AppRequestAuthorization(AppApiScope scope){this.scope=scope;}
  @Override public boolean supports(MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter){return parameter.getContainingClass().getPackageName().equals("com.local.release.web");}
  @Override public Object afterBodyRead(Object body,HttpInputMessage input,MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter){
    boolean app=AppApiScope.appRequest();
    if(body instanceof ServiceController.ServiceRequest r)AppApiScope.require((r.serviceType()==ServiceType.FLUTTER_ANDROID)==app);
    else if(body instanceof BuildController.CreateBuild r){if(r.serviceId()!=null)scope.service(r.serviceId(),app);}
    else if(body instanceof ReleaseController.CreateRelease r){AppApiScope.require(!app);if(r.serviceId()!=null)scope.service(r.serviceId(),false);if(r.artifactId()!=null)scope.artifact(r.artifactId(),false);}
    else if(body instanceof MobileReleaseController.Create r){AppApiScope.require(app);if(r.serviceId()!=null)scope.service(r.serviceId(),true);if(r.artifactId()!=null)scope.artifact(r.artifactId(),true);}
    else if(body instanceof MobileReleaseController.Confirm r){AppApiScope.require(app);if(r.artifactId()!=null)scope.artifact(r.artifactId(),true);}
    return body;
  }
}
