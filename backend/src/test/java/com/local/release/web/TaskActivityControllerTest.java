package com.local.release.web;

import com.local.release.service.TaskActivityService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskActivityControllerTest {
  TaskActivityService activity;MockMvc mvc;
  @BeforeEach void setup(){activity=mock(TaskActivityService.class);mvc=MockMvcBuilders.standaloneSetup(new TaskActivityController(activity)).setControllerAdvice(new ApiExceptionHandler()).build();}
  @Test void ordinaryBrowserSessionsCanReadNoStoreSnapshotWithoutExtraMenuGrant()throws Exception{
    when(activity.snapshot("operator")).thenReturn(new TaskActivityService.Snapshot(Instant.parse("2026-10-18T02:00:00Z"),null,List.of(),List.of(),0,0,List.of(),1));
    for(String role:List.of("ROLE_ADMIN","ROLE_USER"))mvc.perform(get("/api/activity").principal(principal(role)))
      .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.pendingCount").value(0)).andExpect(jsonPath("$.recentCompletions.length()").value(0));
  }
  @Test void appIntegrationAndDeliveryTokensCannotReadOccupancyOrCompletionMetadata()throws Exception{
    for(String role:List.of("ROLE_APP_INTEGRATION","ROLE_MOBILE_DELIVERY"))mvc.perform(get("/api/activity").principal(principal(role))).andExpect(status().isForbidden());
    mvc.perform(get("/api/activity")).andExpect(status().isForbidden());verifyNoInteractions(activity);
  }
  private UsernamePasswordAuthenticationToken principal(String role){return new UsernamePasswordAuthenticationToken("operator",null,List.of(new SimpleGrantedAuthority(role)));}
}
