package com.local.release.service;

import com.local.release.domain.ModelEnums.ServiceType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BuildConfigurationTest {
  @Test void rejectsPreviousMavenFailureBeforeEnqueue() {
    assertThatIllegalArgumentException().isThrownBy(() -> BuildConfiguration.command(
      "mvn.cmd -s E:/example/settings.xml -Dmaven.repo.local=F:/example/cache clean package", ServiceType.BACKEND))
      .withMessageContaining("Windows 路径");
  }
  @Test void migratesSupportedLegacyCommandsAndUsesLinuxDefaults() {
    assertThat(BuildConfiguration.command(null, ServiceType.BACKEND)).isEqualTo("mvn -B clean package -DskipTests");
    assertThat(BuildConfiguration.command("mvn.cmd clean package -DskipTests", ServiceType.BACKEND)).isEqualTo("mvn clean package -DskipTests");
    assertThat(BuildConfiguration.command("cmd.exe /d /s /c \"npm ci && npm run build\"", ServiceType.FRONTEND)).isEqualTo("npm ci && npm run build");
    assertThat(BuildConfiguration.command("gradlew.bat clean build -x test", ServiceType.BACKEND)).isEqualTo("./gradlew clean build -x test");
  }
  @Test void acceptsLinuxSettingsAndRejectsUnsafeRepositoryPaths() {
    assertThat(BuildConfiguration.command("mvn -s /etc/maven/settings.xml clean package", ServiceType.BACKEND)).contains("/etc/maven/settings.xml");
    BuildConfiguration.repositoryPath("backend/target/*.jar", "输出路径");
    for (String invalid : new String[]{"E:/build/app.jar", "C:\\build\\app.jar", "../other/Dockerfile", "/etc/Dockerfile", "\\\\server\\share\\Dockerfile"})
      assertThatIllegalArgumentException().isThrownBy(() -> BuildConfiguration.repositoryPath(invalid, "路径"));
  }
}
