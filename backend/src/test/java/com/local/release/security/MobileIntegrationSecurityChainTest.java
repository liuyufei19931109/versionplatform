package com.local.release.security;

import com.local.release.config.*;
import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.*;
import com.local.release.service.*;
import com.local.release.web.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real servlet SecurityFilterChain: never supplies a synthetic Principal to an unauthenticated request. */
@SpringJUnitConfig(MobileIntegrationSecurityChainTest.TestWebConfiguration.class)
@WebAppConfiguration
class MobileIntegrationSecurityChainTest {
  @Configuration @EnableWebMvc @EnableWebSecurity
  @Import({SecurityConfig.class,BearerTokenFilter.class,AppApiScope.class,AppApiSecurityConfig.class,MobileReleaseController.class,AppIntegrationAccessController.class,AppIntegrationReleaseController.class,ApiExceptionHandler.class})
  static class TestWebConfiguration {
    @Bean TokenService sessions(){return mock(TokenService.class);}
    @Bean AdminUserRepository users(){return mock(AdminUserRepository.class);}
    @Bean AppServiceRepository apps(){return mock(AppServiceRepository.class);}
    @Bean ReleaseTaskRepository tasks(){return mock(ReleaseTaskRepository.class);}
    @Bean ArtifactRepository artifacts(){return mock(ArtifactRepository.class);}
    @Bean MobileArtifactRepository metadata(){return mock(MobileArtifactRepository.class);}
    @Bean MobileReleaseService mobile(){return mock(MobileReleaseService.class);}
    @Bean AppIntegrationAccessService integration(){return mock(AppIntegrationAccessService.class);}
  }
  @Autowired WebApplicationContext context;
  @Autowired TokenService sessions;
  @Autowired AdminUserRepository users;
  @Autowired AppServiceRepository apps;
  @Autowired ReleaseTaskRepository tasks;
  @Autowired ArtifactRepository artifacts;
  @Autowired MobileArtifactRepository metadata;
  @Autowired MobileReleaseService mobile;
  @Autowired AppIntegrationAccessService integration;
  MockMvc mvc;
  @BeforeEach void setup(){
    reset(sessions,users,apps,tasks,artifacts,metadata,mobile,integration);
    for(String name:List.of("admin","app-user","ordinary")){
      AdminUser user=new AdminUser(name,"unused-hash");user.setMenuPermissions(name.equals("ordinary")?List.of("services"):List.of("apps"));
      when(sessions.validate(name)).thenReturn(new TokenService.SessionInfo(name,false));when(users.findByUsername(name)).thenReturn(Optional.of(user));
    }
    AppService app=new AppService();ReflectionTestUtils.setField(app,"id",1L);app.setName("App A");app.setServiceKey("app-a");app.setServiceType(ServiceType.FLUTTER_ANDROID);when(apps.findById(1L)).thenReturn(Optional.of(app));
    ReleaseTask task=new ReleaseTask(1L,TaskKind.DEPLOY,101L,"test","release/test",null,false);ReflectionTestUtils.setField(task,"id",11L);task.setDeliveryType("MOBILE");when(tasks.findById(11L)).thenReturn(Optional.of(task));
    mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void missingAuthorizationCannotReachProtectedControllers()throws Exception{
    for(String url:List.of("/api/mobile/releases/11","/api/mobile/releases/11/download","/api/mobile/apps/1/releases","/api/apps/services/1/integration-access"))
      mvc.perform(get(url)).andExpect(status().is4xxClientError());
    mvc.perform(post("/api/mobile/releases/11/confirm").contentType(MediaType.APPLICATION_JSON).content("{}"))
      .andExpect(status().is4xxClientError());
    verifyNoInteractions(mobile,integration);
  }

  @Test void invalidSessionAndWrongMenuCannotReachCredentialManagement()throws Exception{
    mvc.perform(get("/api/apps/services/1/integration-access").header("Authorization","Bearer expired"))
      .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    for(String method:List.of("GET","POST","DELETE")){
      var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),"/api/apps/services/1/integration-access");
      mvc.perform(request.header("Authorization","Bearer ordinary").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"version-center\",\"environments\":[\"test\"]}"))
        .andExpect(status().isForbidden());
    }
    verifyNoInteractions(mobile,integration);
  }

  @Test void existingShortLivedTokenRemainsAnExactEndpointCredential()throws Exception{
    String token="mrel_11_fetch_"+"a".repeat(43);
    when(mobile.validateAccess(token,"GET","/api/mobile/releases/11")).thenReturn(new MobileReleaseService.TokenPrincipal(11,"fetch","mobile:11:fetch"));
    mvc.perform(get("/api/mobile/releases/11").header("Authorization","Bearer "+token)).andExpect(status().isOk());verify(mobile).detail(11L);
    for(String url:List.of("/api/mobile/releases/12","/api/mobile/apps/1/releases","/api/apps/services/1/integration-access","/api/users","/api/servers"))
      mvc.perform(get(url).header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    verifyNoInteractions(sessions,users);
  }

  @Test void appOperatorCanReadOnlyCredentialStatusButOnlyAdminCanIssueOrRevoke()throws Exception{
    var state=new AppIntegrationAccessService.Status(1L,"version-center",List.of("test"),true,"mapp_1_redacted",null,null,null,"admin","admin");
    when(integration.status(1L)).thenReturn(state);
    mvc.perform(get("/api/apps/services/1/integration-access").header("Authorization","Bearer app-user"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.environments[0]").value("test"))
      .andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.tokenHash").doesNotExist());
    for(String method:List.of("POST","DELETE")){
      var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),"/api/apps/services/1/integration-access");
      mvc.perform(request.header("Authorization","Bearer app-user").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"version-center\",\"environments\":[\"test\"]}"))
        .andExpect(status().isForbidden());
    }
    mvc.perform(post("/api/apps/services/1/integration-access").header("Authorization","Bearer app-user").contentType(MediaType.APPLICATION_JSON).content("{}"))
      .andExpect(status().isForbidden());
    verify(integration,never()).issue(any(),any(),any(),any());verify(integration,never()).revoke(any(),any());
    when(integration.issue(eq(1L),eq("version-center"),eq(List.of("test")),eq("admin"))).thenReturn(new AppIntegrationAccessService.Issued(state,"one-time-secret"));
    mvc.perform(post("/api/apps/services/1/integration-access").header("Authorization","Bearer admin").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"version-center\",\"environments\":[\"test\"]}"))
      .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.token").value("one-time-secret"));
    when(integration.revoke(1L,"admin")).thenReturn(state);
    mvc.perform(delete("/api/apps/services/1/integration-access").header("Authorization","Bearer admin")).andExpect(status().isOk());
    verify(integration).issue(1L,"version-center",List.of("test"),"admin");verify(integration).revoke(1L,"admin");
  }

  @Test void permanentTokenCanListItsRecordsButCannotBecomeAUserOrManageCredentials()throws Exception{
    String token="mapp_1_"+"a".repeat(43),list="/api/mobile/apps/1/releases";
    var principal=new AppIntegrationAccessService.TokenPrincipal(1,Set.of("test"),"integration:1");
    when(integration.validateAccess(token,"GET",list)).thenReturn(principal);
    when(integration.listReleases(principal,"test",null,20)).thenReturn(new AppIntegrationAccessService.ReleasePage(List.of(),null,false));
    mvc.perform(get(list).queryParam("environmentName","test").queryParam("limit","20").header("Authorization","Bearer "+token))
      .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.hasMore").value(false));
    verify(integration).listReleases(principal,"test",null,20);
    for(String path:List.of("/api/auth/me","/api/users","/api/servers","/api/versions","/api/versions/1","/api/apps/signing-profiles","/api/apps/services/1/integration-access","/api/mobile/releases/12","/api/mobile/apps/2/releases"))
      mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    for(String path:List.of("/api/apps/services/1/integration-access","/api/mobile/releases","/api/runtime/restarts","/api/apps/releases/11/merge/retry"))
      mvc.perform(post(path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
    verify(integration,never()).issue(any(),any(),any(),any());verifyNoInteractions(sessions,users,mobile);
  }

  @Test void ordinaryAdminSessionCannotUseExternalCredentialOnlyListEndpoint()throws Exception{
    mvc.perform(get("/api/mobile/apps/1/releases").header("Authorization","Bearer admin"))
      .andExpect(status().isForbidden());
    verify(integration,never()).listReleases(any(),any(),any(),any());
  }

  @Test void authorizedLongTermConfirmationUsesIntegrationIdentityForAudit()throws Exception{
    String token="mapp_1_"+"a".repeat(43),url="/api/mobile/releases/11/confirm",digest="b".repeat(64);
    var principal=new AppIntegrationAccessService.TokenPrincipal(1,Set.of("test"),"app-integration:1");
    when(integration.validateAccess(token,"POST",url)).thenReturn(principal);
    mvc.perform(post(url).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
      .content("{\"artifactId\":101,\"sha256\":\""+digest+"\",\"success\":true,\"requestId\":\"external-publish-request-1\",\"message\":\"published\"}"))
      .andExpect(status().isOk());
    verify(mobile).confirm(eq(11L),argThat(body->body.artifactId()==101L&&body.sha256().equals(digest)&&body.success()),eq("app-integration:1"));
    verifyNoInteractions(sessions,users);
  }

  @Test void malformedCredentialScopeIsRejectedBeforeIssuingSecret()throws Exception{
    for(String body:List.of("{}","{\"name\":\"version-center\",\"environments\":[]}","{\"name\":\"version-center\",\"environments\":[null]}"))
      mvc.perform(post("/api/apps/services/1/integration-access").header("Authorization","Bearer admin").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
    verify(integration,never()).issue(any(),any(),any(),any());
  }
}
