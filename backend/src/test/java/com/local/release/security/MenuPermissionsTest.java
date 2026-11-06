package com.local.release.security;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class MenuPermissionsTest {
  @Test void legacyDashboardGrantSurvivesWithoutRewritingStoredPermissions() {
    Set<String> old=Set.of("dashboard","builds");
    assertThat(MenuPermissions.effective(old)).contains("runtime","dashboard","builds");
    assertThat(old).containsExactlyInAnyOrder("dashboard","builds");
    assertThat(MenuPermissions.effective(Set.of("builds","release"))).doesNotContain("runtime");
  }
  @Test void explicitlyUncheckedRuntimeStaysUncheckedEvenWithDashboard() {
    var stored=MenuPermissions.forStorage(List.of("dashboard","release","untrusted"));
    assertThat(MenuPermissions.effective(stored)).containsExactly("dashboard","release");
    assertThat(stored).doesNotContain("untrusted");
  }
  @Test void runtimeCanBeGrantedWithoutDashboardAndMetadataIsNotDisplayed() {
    var result=MenuPermissions.effective(MenuPermissions.forStorage(List.of("runtime","runtime")));
    assertThat(result).containsExactly("runtime");
  }
  @Test void appsIsIndependentAndNeverBackfilledFromExistingMenus(){
    assertThat(MenuPermissions.effective(Set.of("dashboard","services","servers","builds","release","releases"))).doesNotContain("apps");
    assertThat(MenuPermissions.effective(MenuPermissions.forStorage(List.of("apps")))).containsExactly("apps");
    assertThat(MenuPermissions.ALL).contains("apps");
  }
}
