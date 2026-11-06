package com.local.release.web;

import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.security.MenuPermissions;
import com.local.release.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BranchPolicyControllerTest {
  final BranchPolicyService branches=mock(BranchPolicyService.class);final ProductionMergeService merges=mock(ProductionMergeService.class);final AdminUserRepository users=mock(AdminUserRepository.class);final ReleaseTaskRepository tasks=mock(ReleaseTaskRepository.class);
  final BranchPolicyController controller=new BranchPolicyController(branches,merges,users,tasks);
  @Test void readOnlyReleasePermissionCannotRetryPush(){
    AdminUser reader=new AdminUser("reader","hash");reader.setMenuPermissions(MenuPermissions.forStorage(List.of("releases")));when(users.findByUsername("reader")).thenReturn(Optional.of(reader));
    controller.get(1L,()->"reader");verify(merges).get(1L);assertThatThrownBy(()->controller.retry(1L,()->"reader")).isInstanceOf(AccessDeniedException.class);verify(merges,never()).retry(anyLong());
  }
  @Test void disabledAndUnauthenticatedUsersCannotRead(){
    assertThatThrownBy(()->controller.get(1L,null)).isInstanceOf(AccessDeniedException.class);AdminUser disabled=new AdminUser("disabled","hash");disabled.setEnabled(false);when(users.findByUsername("disabled")).thenReturn(Optional.of(disabled));assertThatThrownBy(()->controller.checks(1L,()->"disabled")).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(branches,merges);
  }
  @Test void releaseReaderCanSeeBuildProofButServicesOnlyCannot(){
    AdminUser reader=new AdminUser("reader","hash");reader.setMenuPermissions(MenuPermissions.forStorage(List.of("releases")));when(users.findByUsername("reader")).thenReturn(Optional.of(reader));
    when(tasks.findById(1L)).thenReturn(Optional.of(new ReleaseTask(1L,ModelEnums.TaskKind.BUILD,null,"build","release",null,false)));controller.checks(1L,()->"reader");verify(branches).list(1L);
    reader.setMenuPermissions(MenuPermissions.forStorage(List.of("services")));assertThatThrownBy(()->controller.checks(1L,()->"reader")).isInstanceOf(AccessDeniedException.class);
  }
}
