package com.local.release.web;

import com.local.release.service.VersionCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.security.Principal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VersionCompletionControllerTest {
  VersionCompletionService service;
  VersionCompletionController controller;
  @BeforeEach void setup(){service=mock(VersionCompletionService.class);controller=new VersionCompletionController(service);}
  Principal actor(String username,String role){return new UsernamePasswordAuthenticationToken(username,"unused",List.of(new SimpleGrantedAuthority(role)));}

  @Test void publicSummaryRequiresAnAuthenticatedPlatformUserRatherThanAnIntegrationToken(){
    for(Principal principal:new Principal[]{null,actor("external","ROLE_APP_INTEGRATION"),new UsernamePasswordAuthenticationToken("yuan","unused")}){
      assertThatThrownBy(()->controller.latest(principal,new MockHttpServletResponse())).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(()->controller.summary(21L,principal,new MockHttpServletResponse())).isInstanceOf(AccessDeniedException.class);
    }
    verifyNoInteractions(service);
  }

  @Test void ordinaryPlatformUsersCanReadPublicSummaryWithoutAnAdminRole(){
    MockHttpServletResponse response=new MockHttpServletResponse();
    controller.latest(actor("yuan","ROLE_USER"),response);
    controller.summary(21L,actor("yuan","ROLE_USER"),response);
    verify(service).latest("yuan");verify(service).publicDetail(21L,"yuan");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
  }

  @Test void ordinaryUsersAndIntegrationTokensCannotGenerateOrMutateCompletionRecords(){
    for(Principal principal:List.of(actor("yuan","ROLE_USER"),actor("integration","ROLE_APP_INTEGRATION"))){
      assertThatThrownBy(()->controller.options(21L,"prod",principal,new MockHttpServletResponse())).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(()->controller.preview(21L,null,principal,new MockHttpServletResponse())).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(()->controller.complete(21L,null,principal,new MockHttpServletResponse())).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(()->controller.edit(21L,null,principal,new MockHttpServletResponse())).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(()->controller.reopen(21L,null,principal,new MockHttpServletResponse())).isInstanceOf(AccessDeniedException.class);
    }
    verifyNoInteractions(service);
  }

  @Test void administratorsReachTheServiceWithTheirRealUsernameAndUncacheableResponses(){
    Principal admin=actor("admin","ROLE_ADMIN");MockHttpServletResponse response=new MockHttpServletResponse();
    controller.options(21L,"prod",admin,response);controller.preview(21L,null,admin,response);
    controller.complete(21L,null,admin,response);controller.edit(21L,null,admin,response);controller.reopen(21L,null,admin,response);
    verify(service).options(21L,"prod","admin");verify(service).preview(21L,null,"admin");
    verify(service).complete(21L,null,"admin");verify(service).edit(21L,null,"admin");verify(service).reopen(21L,null,"admin");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
  }
}
