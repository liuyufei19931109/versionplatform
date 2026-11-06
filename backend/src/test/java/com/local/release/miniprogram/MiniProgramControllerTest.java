package com.local.release.miniprogram;

import com.local.release.web.ApiExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MiniProgramControllerTest {
  MiniProgramService service;MiniProgramSettings settings;MockMvc mvc;
  @BeforeEach void setup(){service=mock(MiniProgramService.class);settings=new MiniProgramSettings();mvc=MockMvcBuilders.standaloneSetup(new MiniProgramController(service,settings)).setControllerAdvice(new ApiExceptionHandler()).build();}
  @Test void disabledModuleIsNotReachableEvenForAdmin()throws Exception{
    mvc.perform(get("/api/miniprograms").principal(user("ROLE_ADMIN"))).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("FEATURE_DISABLED"));verifyNoInteractions(service);
    mvc.perform(get("/api/miniprograms/1/readiness").principal(user("ROLE_ADMIN"))).andExpect(status().isNotFound());
    mvc.perform(get("/api/miniprograms/1/history").principal(user("ROLE_ADMIN"))).andExpect(status().isNotFound());
    mvc.perform(post("/api/miniprograms/jobs/1/reconciliation").principal(user("ROLE_ADMIN")).contentType("application/json").content("{\"resolution\":\"CONFIRMED_UPLOADED\",\"note\":\"checked\"}")).andExpect(status().isNotFound());verifyNoInteractions(service);
  }
  @Test void integrationTokenCannotUseInteractiveMiniProgramApi()throws Exception{
    settings.enabled=true;mvc.perform(get("/api/miniprograms").principal(user("ROLE_APP_INTEGRATION"))).andExpect(status().isForbidden());verifyNoInteractions(service);
  }
  @Test void authenticatedUserIsCheckedByServiceAndCannotSupplyActor()throws Exception{
    settings.enabled=true;mvc.perform(get("/api/miniprograms").principal(user("ROLE_USER"))).andExpect(status().isOk());verify(service).projects("alice");
  }
  @Test void reconciliationUsesLoggedInActorAndConflictsAreExplicit()throws Exception{
    settings.enabled=true;when(service.reconcile(eq(1L),any(),eq("alice"))).thenThrow(new MiniProgramService.Conflict("已经核对"));
    mvc.perform(post("/api/miniprograms/jobs/1/reconciliation").principal(user("ROLE_USER")).contentType("application/json")
      .content("{\"resolution\":\"CONFIRMED_UPLOADED\",\"note\":\"checked\",\"actor\":\"admin\"}"))
      .andExpect(status().isConflict()).andExpect(jsonPath("code").value("MINIPROGRAM_CONFLICT"));
    verify(service).reconcile(eq(1L),any(),eq("alice"));
  }
  @Test void invalidHistoryFilterDoesNotReachService()throws Exception{
    settings.enabled=true;mvc.perform(get("/api/miniprograms/1/history?action=RELEASE").principal(user("ROLE_USER"))).andExpect(status().isBadRequest());verifyNoInteractions(service);
  }
  private UsernamePasswordAuthenticationToken user(String role){return new UsernamePasswordAuthenticationToken("alice",null,List.of(new SimpleGrantedAuthority(role)));}
}
