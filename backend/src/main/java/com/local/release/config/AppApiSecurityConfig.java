package com.local.release.config;

import com.local.release.security.AppApiScope;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class AppApiSecurityConfig implements WebMvcConfigurer {
  private final AppApiScope scope;
  public AppApiSecurityConfig(AppApiScope scope){this.scope=scope;}
  @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(scope).addPathPatterns("/api/**");}
}
