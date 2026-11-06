package com.local.release.security;

import com.local.release.config.SecurityConfig;
import com.local.release.domain.AdminUser;
import com.local.release.repo.AdminUserRepository;
import com.local.release.service.*;
import com.local.release.web.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the real servlet filter chain and raw-byte verification, never synthetic authentication. */
@SpringJUnitConfig(GitHubWebhookSecurityTest.WebConfiguration.class)
@WebAppConfiguration
class GitHubWebhookSecurityTest {
  static final String PATH="/api/integrations/github/webhook";
  static final String SECRET="test-only-webhook-secret-not-a-production-credential";
  static final String DELIVERY="7fb47f1c-2b05-4bdf-a67d-957a1cdc61b5";
  static final byte[] BODY="{\"zen\":\"test\"}".getBytes(StandardCharsets.UTF_8);
  @Configuration @EnableWebMvc @EnableWebSecurity
  @Import({SecurityConfig.class,BearerTokenFilter.class,GitHubWebhookController.class,VersionCodeUpdatesController.class,ApiExceptionHandler.class})
  static class WebConfiguration {
    @Bean TokenService sessions(){return mock(TokenService.class);}
    @Bean AdminUserRepository users(){return mock(AdminUserRepository.class);}
    @Bean MobileReleaseService mobile(){return mock(MobileReleaseService.class);}
    @Bean AppIntegrationAccessService integrations(){return mock(AppIntegrationAccessService.class);}
    @Bean GitHubCodeUpdateService updates(){return mock(GitHubCodeUpdateService.class);}
  }
  @Autowired WebApplicationContext context;
  @Autowired TokenService sessions;
  @Autowired AdminUserRepository users;
  @Autowired MobileReleaseService mobile;
  @Autowired AppIntegrationAccessService integrations;
  @Autowired GitHubCodeUpdateService updates;
  @Autowired GitHubWebhookController controller;
  MockMvc mvc;
  @BeforeEach void setup(){
    reset(sessions,users,mobile,integrations,updates);
    GitHubWebhookSettings settings=new GitHubWebhookSettings(true,SECRET,"example-org");
    when(updates.receive(anyString(),anyString(),anyString(),any(byte[].class))).thenAnswer(call->{
      settings.verify(call.getArgument(2),call.getArgument(3));return new GitHubCodeUpdateService.Receipt("accepted");
    });
    mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void exactlyTheWebhookPostIsAnonymousButStillRequiresValidHmac()throws Exception{
    mvc.perform(signed(BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("accepted"));
    verify(updates).receive(eq(DELIVERY),eq("ping"),eq(signature(BODY)),aryEq(BODY));
    verifyNoInteractions(sessions,users,mobile,integrations);
    clearInvocations(updates);
    for(HttpMethod method:List.of(HttpMethod.GET,HttpMethod.PUT,HttpMethod.DELETE,HttpMethod.PATCH,HttpMethod.HEAD))
      mvc.perform(request(method,PATH)).andExpect(status().is4xxClientError());
    for(String path:List.of(PATH+"/",PATH+"/other","/api/integrations/github","/api/integrations/other/webhook"))
      mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().is4xxClientError());
    verifyNoInteractions(updates);
  }

  @Test void absentMalformedForgedAndChangedPayloadSignaturesAreRejected()throws Exception{
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("X-GitHub-Event","ping").header("X-GitHub-Delivery",DELIVERY).content(BODY))
      .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GITHUB_HEADER_INVALID"));
    verifyNoInteractions(updates);
    for(String signature:List.of("sha1="+"a".repeat(40),"sha256="+"0".repeat(64),"sha256=bad"))
      mvc.perform(headers(BODY,signature)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GITHUB_SIGNATURE_INVALID"));
    mvc.perform(headers("{\"zen\":\"changed\"}".getBytes(StandardCharsets.UTF_8),signature(BODY)))
      .andExpect(status().isUnauthorized());
  }

  @Test void verifierUsesTheExactUtf8BytesIncludingWhitespaceAndTrailingNewline()throws Exception{
    byte[] raw="{ \"zen\" : \"代码更新 🚀\" }\r\n".getBytes(StandardCharsets.UTF_8);
    mvc.perform(signed(raw)).andExpect(status().isOk());
    verify(updates).receive(eq(DELIVERY),eq("ping"),eq(signature(raw)),aryEq(raw));
    byte[] normalized="{\"zen\":\"代码更新 🚀\"}".getBytes(StandardCharsets.UTF_8);
    mvc.perform(headers(raw,signature(normalized))).andExpect(status().isUnauthorized());
  }

  @Test void requiredHeadersMustBeSingleAndBounded()throws Exception{
    for(String header:List.of("X-GitHub-Delivery","X-GitHub-Event","X-Hub-Signature-256")){
      mvc.perform(signed(BODY).header(header,"duplicate")).andExpect(status().isBadRequest());
      mvc.perform(signed(BODY).with(request->{request.removeHeader(header);return request;})).andExpect(status().isBadRequest());
      mvc.perform(signed(BODY).with(request->{request.removeHeader(header);request.addHeader(header,"x".repeat(121));return request;})).andExpect(status().isBadRequest());
    }
    verifyNoInteractions(updates);
  }

  @Test void nonJsonAndCompressedBodiesAreRejectedBeforeService()throws Exception{
    for(String type:List.of("text/plain","application/x-www-form-urlencoded","application/jsonp"))
      mvc.perform(signed(BODY).contentType(type)).andExpect(status().isUnsupportedMediaType());
    for(String encoding:List.of("gzip","br","identity, gzip"))
      mvc.perform(signed(BODY).header("Content-Encoding",encoding)).andExpect(status().isUnsupportedMediaType());
    verifyNoInteractions(updates);
    mvc.perform(signed(BODY).contentType("application/json; charset=UTF-8").header("Content-Encoding","identity")).andExpect(status().isOk());
  }

  @Test void boundedStreamingAlsoRejectsUnknownOrDishonestContentLength()throws Exception{
    byte[] oversized=new byte[GitHubWebhookSettings.MAX_BODY_BYTES+1];Arrays.fill(oversized,(byte)'x');
    mvc.perform(headers(oversized,"sha256="+"0".repeat(64))).andExpect(status().isPayloadTooLarge());
    for(long claimedLength:List.of(-1L,8L)){
      MockHttpServletRequest request=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return claimedLength;}};
      request.setContentType("application/json");request.addHeader("X-GitHub-Delivery",DELIVERY);request.addHeader("X-GitHub-Event","ping");request.addHeader("X-Hub-Signature-256","sha256="+"0".repeat(64));request.setContent(oversized);
      assertThatThrownBy(()->controller.receive(request)).isInstanceOfSatisfying(GitHubWebhookException.class,error->{assertThat(error.status()).isEqualTo(413);assertThat(error.code()).isEqualTo("GITHUB_BODY_LIMIT");});
    }
    verifyNoInteractions(updates);
  }

  @Test void bodyAtLimitIsAcceptedButEmptyBodyCannotBeSignedIntoAnEvent()throws Exception{
    byte[] atLimit=new byte[GitHubWebhookSettings.MAX_BODY_BYTES];Arrays.fill(atLimit,(byte)' ');
    mvc.perform(signed(atLimit)).andExpect(status().isOk());
    mvc.perform(signed(new byte[0])).andExpect(status().isPayloadTooLarge());
  }

  @Test void aSessionDoesNotReplaceTheWebhookSignature()throws Exception{
    when(sessions.validate("admin-session")).thenReturn(new TokenService.SessionInfo("admin",false));
    when(users.findByUsername("admin")).thenReturn(Optional.of(new AdminUser("admin","unused")));
    mvc.perform(post(PATH).header("Authorization","Bearer admin-session").contentType(MediaType.APPLICATION_JSON).content(BODY))
      .andExpect(status().isBadRequest());
    verifyNoInteractions(updates);
  }

  @Test void oldMobileTokensCannotEnterAnonymousWebhookOrOtherNewApis()throws Exception{
    for(String token:List.of("mapp_1_"+"a".repeat(43),"mrel_11_fetch_"+"b".repeat(43))){
      mvc.perform(signed(BODY).header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
      for(String path:List.of("/api/versions/1/code-updates","/api/integrations/github/status"))
        mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    verifyNoInteractions(updates,sessions,users);
  }

  @Test void readingCodeUpdatesRequiresExistingVersionMenuAndWritesStayForbidden()throws Exception{
    for(String name:List.of("version-reader","app-reader")){
      AdminUser user=new AdminUser(name,"unused");user.setMenuPermissions(List.of(name.equals("version-reader")?"versions":"apps"));
      when(sessions.validate(name)).thenReturn(new TokenService.SessionInfo(name,false));when(users.findByUsername(name)).thenReturn(Optional.of(user));
    }
    String path="/api/versions/1/code-updates";
    mvc.perform(get(path)).andExpect(status().is4xxClientError());
    mvc.perform(get(path).header("Authorization","Bearer app-reader")).andExpect(status().isForbidden());
    verifyNoInteractions(updates);
    when(updates.updates(1L,"version-reader")).thenReturn(new GitHubCodeUpdateService.Updates(true,List.of()));
    mvc.perform(get(path).header("Authorization","Bearer version-reader")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    verify(updates).updates(1L,"version-reader");clearInvocations(updates);
    for(HttpMethod method:List.of(HttpMethod.POST,HttpMethod.PUT,HttpMethod.DELETE))
      mvc.perform(request(method,path).header("Authorization","Bearer version-reader").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(updates);
  }

  @Test void disabledOrUnconfiguredSettingsFailClosedAndNeverEchoSecret()throws Exception{
    for(GitHubWebhookSettings settings:List.of(new GitHubWebhookSettings(false,SECRET,"example-org"),new GitHubWebhookSettings(true,"short","example-org"),new GitHubWebhookSettings(true,SECRET,"bad/owner")))
      assertThatThrownBy(()->settings.verify(signature(BODY),BODY)).isInstanceOfSatisfying(GitHubWebhookException.class,error->{assertThat(error.status()).isEqualTo(503);assertThat(error.getMessage()).doesNotContain(SECRET);});
    GitHubWebhookSettings settings=new GitHubWebhookSettings(true,SECRET,"EXAMPLE-ORG");assertThat(settings.owner()).isEqualTo("example-org");
    assertThatCode(()->settings.verify(signature(BODY),BODY)).doesNotThrowAnyException();
  }

  private MockHttpServletRequestBuilder signed(byte[] body)throws Exception{return headers(body,signature(body));}
  private MockHttpServletRequestBuilder headers(byte[] body,String signature){return post(PATH).contentType(MediaType.APPLICATION_JSON).header("X-GitHub-Delivery",DELIVERY).header("X-GitHub-Event","ping").header("X-Hub-Signature-256",signature).content(body);}
  private static String signature(byte[] body)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return "sha256="+HexFormat.of().formatHex(mac.doFinal(body));}
}
