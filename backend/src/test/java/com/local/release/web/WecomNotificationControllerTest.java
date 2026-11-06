package com.local.release.web;
import com.local.release.domain.AdminUser;
import com.local.release.repo.AdminUserRepository;
import com.local.release.service.WecomNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WecomNotificationControllerTest {
  final WecomNotificationService service=mock(WecomNotificationService.class);
  final AdminUserRepository users=mock(AdminUserRepository.class);
  final WecomNotificationController controller=new WecomNotificationController(service,users);
  void user(String... permissions){AdminUser user=new AdminUser("reader","hash");user.setMenuPermissions(List.of(permissions));when(users.findByUsername("reader")).thenReturn(Optional.of(user));}
  @Test void onlyAdminCanReadChangeOrTestWebhook(){user("release");assertThatThrownBy(()->controller.get(()->"reader")).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->controller.save(null,()->"reader")).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->controller.test(()->"reader")).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(service);}
  @Test void historyViewerCanReadButOnlyReleasePermissionCanRetry(){user("releases");controller.list(1L,()->"reader");assertThatThrownBy(()->controller.retry(1L,2L,()->"reader")).isInstanceOf(AccessDeniedException.class);verify(service,never()).retry(any(),any());user("release");controller.retry(1L,2L,()->"reader");verify(service).retry(1L,2L);}
}
