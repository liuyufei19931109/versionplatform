package com.local.release.security;

import com.local.release.domain.AdminUser;
import com.local.release.repo.AdminUserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BearerTokenFilterTest {
  @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

  @Test void versionManagementHasExplicitReadPermissionAndNeverAllowsOrdinaryMutations()throws Exception{
    for(String path:new String[]{"/api/versions","/api/versions/latest","/api/versions/candidates","/api/versions/12","/api/versions/preview"}){
      check("GET",path,Set.of("versions"),true);
      check("GET",path,Set.of("dashboard","services","builds","release","releases","apps"),false);
      check("GET",path,Set.of("apps"),false);
      for(String method:new String[]{"POST","PUT","PATCH","DELETE"})check(method,path,Set.of("versions","services","apps"),false);
    }
    check("POST","/api/versions/preview",Set.of("versions","services"),false);
    check("GET","/api/builds",Set.of("builds"),true);
    check("POST","/api/builds",Set.of("builds"),true);
    check("GET","/api/apps/services",Set.of("apps"),true);
  }

  @Test void restartCancellationRequiresBothPermissionsAndExactPostRoute()throws Exception{
    check("POST","/api/runtime/restarts/12/cancel",Set.of("runtime","release"),true);
    check("POST","/api/runtime/restarts/12/cancel",Set.of("runtime"),false);
    check("POST","/api/runtime/restarts/12/cancel",Set.of("release"),false);
    check("POST","/api/runtime/restarts/12/cancel-extra",Set.of("runtime","release"),false);
    check("DELETE","/api/runtime/restarts/12/cancel",Set.of("runtime","release"),false);
  }

  @Test void mobileRequiresIndependentAppsGrantAndBranchChecksRetainTaskReadPermission()throws Exception{
    for(String path:new String[]{"/api/mobile/releases","/api/mobile/releases/1/confirm","/api/mobile/releases/1/access-token","/api/apps/releases/1/merge/retry"}){
      check("POST",path,Set.of("releases"),false);check("POST",path,Set.of("builds"),false);check("POST",path,Set.of("release"),false);check("POST",path,Set.of("apps"),true);
    }
    check("GET","/api/mobile/releases/1",Set.of("releases"),false);
    check("GET","/api/mobile/releases/1",Set.of("apps"),true);
    check("GET","/api/mobile/releases/1/download",Set.of("dashboard"),false);
    check("GET","/api/tasks/1/branch-checks",Set.of("builds"),true);
    check("GET","/api/tasks/1/branch-checks",Set.of("dashboard"),false);
  }

  @Test void appsOnlyNeverGrantsOrdinaryMenusOrServerOperations()throws Exception{
    for(String path:new String[]{"/api/services","/api/builds","/api/artifacts","/api/releases","/api/servers","/api/runtime","/api/dashboard","/api/users"})check("GET",path,Set.of("apps"),false);
    check("POST","/api/servers",Set.of("apps"),false);
    check("POST","/api/runtime/restarts",Set.of("apps"),false);
    check("GET","/api/apps/services",Set.of("services","builds","release","releases"),false);
    check("GET","/api/apps/services",Set.of("apps"),true);
  }

  @Test void scopedDeliveryTokenDoesNotBecomeOrdinaryUserSession()throws Exception{
    var mobile=mock(com.local.release.service.MobileReleaseService.class);
    @SuppressWarnings("unchecked") var provider=(org.springframework.beans.factory.ObjectProvider<com.local.release.service.MobileReleaseService>)mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getObject()).thenReturn(mobile);var tokens=mock(TokenService.class);var users=mock(AdminUserRepository.class);
    String token="mrel_1_fetch_"+"a".repeat(43);var filter=new BearerTokenFilter(tokens,users,provider);
    when(mobile.validateAccess(token,"GET","/api/mobile/releases/1/download")).thenReturn(new com.local.release.service.MobileReleaseService.TokenPrincipal(1,"fetch","mobile:1:fetch"));
    var request=new MockHttpServletRequest("GET","/api/mobile/releases/1/download");request.addHeader("Authorization","Bearer "+token);
    var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);filter.doFilter(request,response,chain);
    verify(chain).doFilter(request,response);verifyNoInteractions(tokens,users);
    assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("mobile:1:fetch");
    var other=new MockHttpServletRequest("GET","/api/services");other.addHeader("Authorization","Bearer "+token);var denied=new MockHttpServletResponse();
    filter.doFilter(other,denied,mock(FilterChain.class));assertThat(denied.getStatus()).isEqualTo(401);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @ParameterizedTest
  @CsvSource({"POST,/api/releases", "POST,/api/releases/1/retry", "POST,/api/releases/1/cancel",
    "PUT,/api/releases/1", "PATCH,/api/releases/1", "DELETE,/api/releases/1"})
  void releaseRecordsPermissionCannotMutateReleaseTasks(String method, String path) throws Exception {
    check(method, path, Set.of("releases"), false);
  }

  @Test void releaseRecordsPermissionCanReadHistory() throws Exception {
    check("GET", "/api/releases/1", Set.of("releases"), true);
  }

  @Test void releasePermissionAllowsAuthorizedRetryEntry() throws Exception {
    check("POST", "/api/releases/1/retry", Set.of("release"), true);
  }

  @Test void dashboardCanReadQueueSnapshotWithoutPermissionToCreateBuilds() throws Exception {
    check("GET", "/api/builds/queue", Set.of("dashboard"), true);
    check("POST", "/api/builds", Set.of("dashboard"), false);
  }

  @Test void expiredBearerTokenReturns401ForProtectedEndpoint() throws Exception {
    TokenService tokens = mock(TokenService.class);
    AdminUserRepository users = mock(AdminUserRepository.class);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
    request.addHeader("Authorization", "Bearer expired-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    new BearerTokenFilter(tokens, users).doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("SESSION_EXPIRED");
    verify(tokens).validate("expired-token");
    verifyNoInteractions(users, chain);
  }

  @Test void loginRemainsAccessibleWhenBrowserSendsExpiredBearerToken() throws Exception {
    checkPublicEndpointWithExpiredToken("POST", "/api/auth/login");
  }

  @Test void healthRemainsAccessibleWithExpiredBearerToken() throws Exception {
    checkPublicEndpointWithExpiredToken("GET", "/api/health");
  }

  @ParameterizedTest
  @CsvSource({"GET,/api/runtime", "POST,/api/runtime/check"})
  void runtimeOverviewRequiresDashboardPermissionForReadAndRefresh(String method, String path) throws Exception {
    check(method, path, Set.of("dashboard"), true);
    check(method, path, Set.of("release", "releases", "builds", "services", "servers"), false);
    check(method, path, Set.of(), false);
  }
  @Test void independentRuntimePermissionControlsReadOnlyAccess()throws Exception {
    check("GET","/api/runtime",Set.of("runtime"),true);
    check("POST","/api/runtime/check",Set.of("runtime"),true);
    check("GET","/api/runtime/restarts",Set.of("runtime"),true);
    check("GET","/api/runtime",Set.of("dashboard","_runtime_explicit"),false);
  }
  @Test void restartNeedsBothRuntimeAndReleasePermissionsOnTheBackend()throws Exception {
    for(String path: new String[]{"/api/runtime/restart-preview","/api/runtime/restarts"}) {
      check("POST",path,Set.of("runtime"),false);
      check("POST",path,Set.of("release","releases"),false);
      check("POST",path,Set.of("runtime","release"),true);
      check("POST",path,Set.of("dashboard","release"),true);
      check("POST",path,Set.of("dashboard","release","_runtime_explicit"),false);
    }
    check("DELETE","/api/runtime/restarts/1",Set.of("runtime","release"),false);
  }
  private void checkPublicEndpointWithExpiredToken(String method, String path) throws Exception {
    TokenService tokens = mock(TokenService.class);
    AdminUserRepository users = mock(AdminUserRepository.class);
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.addHeader("Authorization", "Bearer expired-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    new BearerTokenFilter(tokens, users).doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(200);
    verify(chain).doFilter(request, response);
    verifyNoInteractions(tokens, users);
  }

  private void check(String method, String path, Set<String> permissions, boolean allowed) throws Exception {
    TokenService tokens = mock(TokenService.class);
    AdminUserRepository users = mock(AdminUserRepository.class);
    AdminUser user = mock(AdminUser.class);
    when(tokens.validate("test-token")).thenReturn(new TokenService.SessionInfo("reader", false));
    when(users.findByUsername("reader")).thenReturn(Optional.of(user));
    when(user.isEnabled()).thenReturn(true);
    when(user.getUsername()).thenReturn("reader");
    when(user.menuPermissionSet()).thenReturn(permissions);
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.addHeader("Authorization", "Bearer test-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    new BearerTokenFilter(tokens, users).doFilter(request, response, chain);
    if (allowed) {
      assertThat(response.getStatus()).isEqualTo(200);
      verify(chain).doFilter(request, response);
    } else {
      assertThat(response.getStatus()).isEqualTo(403);
      verifyNoInteractions(chain);
    }
  }
}
