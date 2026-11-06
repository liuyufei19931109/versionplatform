package com.local.release.web;

import com.local.release.service.GitHubEventService;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GitHubEventControllerTest {
  GitHubEventService events;MockMvc mvc;
  @BeforeEach void setup(){events=mock(GitHubEventService.class);mvc=MockMvcBuilders.standaloneSetup(new GitHubEventController(events)).setControllerAdvice(new ApiExceptionHandler()).build();}
  @Test void browserUsersGetPrivateFeedAndCanAcknowledgeExactlyTheirDisplayedSnapshot()throws Exception{
    when(events.feed("operator")).thenReturn(new GitHubEventService.Feed(true,false,null,List.of()));
    var login=principal("ROLE_USER");
    mvc.perform(get("/api/github/events").principal(login)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.items.length()").value(0));
    mvc.perform(post("/api/github/events/read").principal(login).contentType(MediaType.APPLICATION_JSON).content("{\"throughEventId\":12,\"eventIds\":[12,10]}"))
      .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store"));
    verify(events).markRead("operator",12L,List.of(12L,10L));
  }
  @Test void integrationAndDeliveryCredentialsCannotReadOrAcknowledgeBellEvenIfAuthenticated()throws Exception{
    for(String role:List.of("ROLE_APP_INTEGRATION","ROLE_MOBILE_DELIVERY")){
      mvc.perform(get("/api/github/events").principal(principal(role))).andExpect(status().isForbidden());
      mvc.perform(post("/api/github/events/read").principal(principal(role)).contentType(MediaType.APPLICATION_JSON).content("{\"throughEventId\":12,\"eventIds\":[12]}"))
        .andExpect(status().isForbidden());
    }
    mvc.perform(get("/api/github/events")).andExpect(status().isForbidden());verifyNoInteractions(events);
  }
  private UsernamePasswordAuthenticationToken principal(String role){return new UsernamePasswordAuthenticationToken("operator",null,List.of(new SimpleGrantedAuthority(role)));}
}
