package com.local.release.config;

import com.local.release.security.BearerTokenFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.List;

@Configuration
public class SecurityConfig {
  @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
  @Bean CorsConfigurationSource corsConfigurationSource(){
    CorsConfiguration c=new CorsConfiguration();c.setAllowedOriginPatterns(List.of(
      "http://localhost:*","http://127.0.0.1:*","http://10.*:*","http://192.168.*:*",
      "http://172.16.*:*","http://172.17.*:*","http://172.18.*:*","http://172.19.*:*",
      "http://172.20.*:*","http://172.21.*:*","http://172.22.*:*","http://172.23.*:*",
      "http://172.24.*:*","http://172.25.*:*","http://172.26.*:*","http://172.27.*:*",
      "http://172.28.*:*","http://172.29.*:*","http://172.30.*:*","http://172.31.*:*"
    ));c.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));c.setAllowedHeaders(List.of("*"));c.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource s=new UrlBasedCorsConfigurationSource();s.registerCorsConfiguration("/**",c);return s;
  }
  @Bean SecurityFilterChain chain(HttpSecurity http,BearerTokenFilter filter)throws Exception{
    return http.csrf(c->c.disable()).cors(c->{}).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      // The original request of a log stream is authenticated and authorized; its completion dispatch carries no token.
      .authorizeHttpRequests(a->a.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
        .requestMatchers(HttpMethod.GET, "/api/branding/logo").permitAll()
        .requestMatchers("/api/settings/branding/logo").hasRole("ADMIN")
        .requestMatchers(request->"POST".equals(request.getMethod())&&"/api/integrations/github/webhook".equals(request.getRequestURI())).permitAll().requestMatchers("/api/auth/login","/api/health","/error").permitAll().anyRequest().authenticated())
      .addFilterBefore(filter,UsernamePasswordAuthenticationFilter.class).build();
  }
}
