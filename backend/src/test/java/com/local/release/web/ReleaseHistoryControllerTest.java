package com.local.release.web;

import com.local.release.domain.AdminUser;
import com.local.release.repo.*;
import com.local.release.security.BearerTokenFilter;
import com.local.release.security.TokenService;
import com.local.release.service.ReleaseHistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReleaseHistoryControllerTest {
  private final ReleaseTaskRepository tasks = mock(ReleaseTaskRepository.class);
  private final AppServiceRepository services = mock(AppServiceRepository.class);
  private final ServiceEnvironmentRepository environments = mock(ServiceEnvironmentRepository.class);
  private final ArtifactRepository artifacts = mock(ArtifactRepository.class);
  private final TokenService tokens = mock(TokenService.class);
  private final AdminUserRepository users = mock(AdminUserRepository.class);
  private final AdminUser reader = mock(AdminUser.class);
  private MockMvc mvc;

  @BeforeEach void setup() {
    when(tokens.validate("history-reader")).thenReturn(new TokenService.SessionInfo("reader", false));
    when(users.findByUsername("reader")).thenReturn(Optional.of(reader));
    when(reader.isEnabled()).thenReturn(true); when(reader.getUsername()).thenReturn("reader");
    mvc = MockMvcBuilders.standaloneSetup(new ReleaseHistoryController(new ReleaseHistoryService(tasks, services, environments, artifacts)))
      .setControllerAdvice(new ApiExceptionHandler()).addFilters(new BearerTokenFilter(tokens, users)).build();
  }
  @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

  @Test void historyReaderNeedsNoServiceOrArtifactManagementPermission() throws Exception {
    when(reader.menuPermissionSet()).thenReturn(Set.of("releases"));
    mvc.perform(get("/api/releases/history").header("Authorization", "Bearer history-reader"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.environment").value("test"))
      .andExpect(jsonPath("$.services").isArray()).andExpect(jsonPath("$.records").isArray());
  }

  @Test void releaseOperatorCanReadChosenEnvironment() throws Exception {
    when(reader.menuPermissionSet()).thenReturn(Set.of("release"));
    mvc.perform(get("/api/releases/history").param("environment", "prod").header("Authorization", "Bearer history-reader"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.environment").value("prod"));
  }

  @Test void unrelatedPermissionCannotReadAndHistoryOnlyPermissionCannotPost() throws Exception {
    when(reader.menuPermissionSet()).thenReturn(Set.of("services", "dashboard"));
    mvc.perform(get("/api/releases/history").header("Authorization", "Bearer history-reader"))
      .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    when(reader.menuPermissionSet()).thenReturn(Set.of("releases"));
    mvc.perform(post("/api/releases/history").header("Authorization", "Bearer history-reader"))
      .andExpect(status().isForbidden());
    verifyNoInteractions(tasks, services, environments, artifacts);
  }

  @Test void invalidEnvironmentIsBadRequestInsteadOfFallingBackToAllHistory() throws Exception {
    when(reader.menuPermissionSet()).thenReturn(Set.of("releases"));
    mvc.perform(get("/api/releases/history").param("environment", "all").header("Authorization", "Bearer history-reader"))
      .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    verifyNoInteractions(tasks, services, environments, artifacts);
  }
}
