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
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The actual authentication chain and object interceptor protect both new read-only App endpoints. */
@SpringJUnitConfig(MobileVersionApiSecurityTest.WebConfig.class)
@WebAppConfiguration
class MobileVersionApiSecurityTest {
  static final String PREFIX="/api/apps/services/1/",SHA="a".repeat(40);
  @Configuration @EnableWebMvc @EnableWebSecurity
  @Import({SecurityConfig.class,BearerTokenFilter.class,AppApiScope.class,AppApiSecurityConfig.class,MobileVersionController.class,ApiExceptionHandler.class})
  static class WebConfig {
    @Bean TokenService sessions(){return mock(TokenService.class);}
    @Bean AdminUserRepository users(){return mock(AdminUserRepository.class);}
    @Bean AppServiceRepository apps(){return mock(AppServiceRepository.class);}
    @Bean ReleaseTaskRepository tasks(){return mock(ReleaseTaskRepository.class);}
    @Bean ArtifactRepository artifacts(){return mock(ArtifactRepository.class);}
    @Bean MobileArtifactRepository metadata(){return mock(MobileArtifactRepository.class);}
    @Bean MobileVersionService versions(){return mock(MobileVersionService.class);}
    @Bean MobileReleaseService mobile(){return mock(MobileReleaseService.class);}
    @Bean AppIntegrationAccessService integrations(){return mock(AppIntegrationAccessService.class);}
  }
  @Autowired WebApplicationContext context;
  @Autowired TokenService sessions;
  @Autowired AdminUserRepository users;
  @Autowired AppServiceRepository apps;
  @Autowired MobileVersionService versions;
  @Autowired MobileReleaseService mobile;
  @Autowired AppIntegrationAccessService integrations;
  MockMvc mvc;
  @BeforeEach void setup(){
    reset(sessions,users,apps,versions,mobile,integrations);
    for(String name:List.of("admin","app-user","server-user")){
      AdminUser user=new AdminUser(name,"fixture-hash");user.setMenuPermissions(name.equals("server-user")?List.of("services","builds"):List.of("apps"));
      when(sessions.validate(name)).thenReturn(new TokenService.SessionInfo(name,false));when(users.findByUsername(name)).thenReturn(Optional.of(user));
    }
    AppService app=new AppService();ReflectionTestUtils.setField(app,"id",1L);app.setServiceType(ServiceType.FLUTTER_ANDROID);app.setName("App");app.setServiceKey("app");when(apps.findById(1L)).thenReturn(Optional.of(app));
    AppService backend=new AppService();ReflectionTestUtils.setField(backend,"id",2L);backend.setServiceType(ServiceType.BACKEND);backend.setName("Backend");backend.setServiceKey("backend");when(apps.findById(2L)).thenReturn(Optional.of(backend));
    mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void unauthenticatedExpiredAndOtherMenuUsersNeverReachVersionReadService()throws Exception{
    for(String endpoint:List.of("build-version-preview","pre-build-candidates")){
      mvc.perform(get(PREFIX+endpoint).param("environmentName","test")).andExpect(status().is4xxClientError());
      mvc.perform(get(PREFIX+endpoint).param("environmentName","test").header("Authorization","Bearer expired")).andExpect(status().isUnauthorized());
      mvc.perform(get(PREFIX+endpoint).param("environmentName","test").header("Authorization","Bearer server-user")).andExpect(status().isForbidden());
    }
    verifyNoInteractions(versions);
  }

  @Test void bothExternalAppTokenKindsCannotElevateToVersionPreviewOrSourceSelection()throws Exception{
    for(String token:List.of("mapp_1_"+"a".repeat(43),"mrel_1_fetch_"+"b".repeat(43)))for(String endpoint:List.of("build-version-preview","pre-build-candidates"))
      mvc.perform(get(PREFIX+endpoint).param("environmentName","test").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    verifyNoInteractions(versions,sessions,users);
  }

  @Test void appSessionCanPreviewWithoutSourceAndResponsesCannotBeCached()throws Exception{
    when(versions.preview(1L,"test",null,null,null)).thenReturn(new MobileVersionService.Preview("AUTO","test","0.1.1-pre",22L,"0.1.1",null,true,"预计版本"));
    mvc.perform(get(PREFIX+"build-version-preview").param("environmentName","test").header("Authorization","Bearer app-user"))
      .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.versionName").value("0.1.1-pre")).andExpect(jsonPath("$.versionCode").value(22)).andExpect(jsonPath("$.allocationPending").value(true));
    verify(versions).preview(1L,"test",null,null,null);
  }

  @Test void chosenPreIdAndExactCommitReachPreviewAndCandidateFilters()throws Exception{
    when(versions.preview(1L,"prod","release20261018",SHA,88L)).thenReturn(new MobileVersionService.Preview("AUTO","prod","0.2.0-prd",3L,"0.2.0",88L,true,"预计版本"));
    when(versions.candidates(1L,"release20261018",SHA)).thenReturn(List.of());
    mvc.perform(get(PREFIX+"build-version-preview").param("environmentName","prod").param("branch","release20261018").param("commitId",SHA).param("preBuildTaskId","88").header("Authorization","Bearer app-user"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.preBuildTaskId").value(88));
    mvc.perform(get(PREFIX+"pre-build-candidates").param("branch","release20261018").param("commitId",SHA).header("Authorization","Bearer app-user"))
      .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().json("[]"));
    verify(versions).preview(1L,"prod","release20261018",SHA,88L);verify(versions).candidates(1L,"release20261018",SHA);
  }

  @Test void appRoutesCannotExposeServerServicesEvenToAdmin()throws Exception{
    for(String user:List.of("admin","app-user"))for(long id:List.of(2L,99L))for(String endpoint:List.of("build-version-preview","pre-build-candidates"))
      mvc.perform(get("/api/apps/services/"+id+"/"+endpoint).param("environmentName","test").header("Authorization","Bearer "+user)).andExpect(status().isForbidden());
    verifyNoInteractions(versions);
  }

  @Test void previewDoesNotExposeAMutationVerbAndInvalidIdDoesNotReachService()throws Exception{
    for(String endpoint:List.of("build-version-preview","pre-build-candidates"))
      mvc.perform(post(PREFIX+endpoint).header("Authorization","Bearer admin")).andExpect(status().isMethodNotAllowed());
    mvc.perform(get(PREFIX+"build-version-preview").param("environmentName","prod").param("preBuildTaskId","not-an-id").header("Authorization","Bearer app-user")).andExpect(status().isBadRequest());
    verifyNoInteractions(versions);
  }
}
