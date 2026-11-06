package com.local.release.web;

import com.local.release.domain.AdminUser;
import com.local.release.repo.AdminUserRepository;
import com.local.release.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserAppsPermissionTest {
  @Test void administratorCanGrantAndRevokeOnlyAppsAndOldUsersAreNotAutomaticallyGranted(){
    var users=mock(AdminUserRepository.class);var encoder=mock(PasswordEncoder.class);var totp=mock(TotpService.class);var tokens=mock(TokenService.class);
    AdminUser admin=new AdminUser("admin","hash"),old=new AdminUser("old","hash");old.setMenuPermissions(List.of("builds","release","services"));ReflectionTestUtils.setField(old,"id",1L);
    when(users.findByUsername("admin")).thenReturn(Optional.of(admin));when(users.findById(1L)).thenReturn(Optional.of(old));when(users.save(any())).thenAnswer(call->call.getArgument(0));when(encoder.encode(any())).thenReturn("encoded");
    var controller=new UserController(users,encoder,totp,tokens);
    var created=controller.create(new UserController.UserRequest("appuser","App operator","initial-test-password",List.of("apps"),true),()->"admin");
    assertThat(created.permissions()).containsExactly("apps");
    var granted=controller.update(1L,new UserController.UserRequest(null,"Old",null,List.of("apps"),true),()->"admin");assertThat(granted.permissions()).containsExactly("apps");
    var revoked=controller.update(1L,new UserController.UserRequest(null,"Old",null,List.of("builds","release","services"),true),()->"admin");assertThat(revoked.permissions()).doesNotContain("apps");
    when(users.findAll()).thenReturn(List.of(admin,old));var listed=controller.list(()->"admin");assertThat(listed.get(0).permissions()).contains("apps");assertThat(listed.get(1).permissions()).doesNotContain("apps");
  }
}
