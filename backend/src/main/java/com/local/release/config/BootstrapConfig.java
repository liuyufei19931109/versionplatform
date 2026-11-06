package com.local.release.config;

import com.local.release.domain.*; import com.local.release.repo.*;
import org.springframework.boot.CommandLineRunner; import org.springframework.context.annotation.Bean; import org.springframework.context.annotation.Configuration; import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class BootstrapConfig {
  @Bean
  CommandLineRunner admin(AdminUserRepository users, PasswordEncoder encoder,
      @org.springframework.beans.factory.annotation.Value("${release.bootstrap-admin-password:}") String initialPassword) {
    return args -> {
      if (users.findByUsername("admin").isPresent()) return;
      if (initialPassword.isBlank() || initialPassword.codePointCount(0, initialPassword.length()) < 12
          || initialPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
        throw new IllegalStateException("首次启动请设置 RELEASE_BOOTSTRAP_ADMIN_PASSWORD，至少 12 个字符，最多 72 个 UTF-8 字节。");
      }
      AdminUser admin = new AdminUser("admin", encoder.encode(initialPassword));
      admin.setDisplayName("管理员");
      users.save(admin);
    };
  }
  @Bean CommandLineRunner artifactOperators(ArtifactRepository artifacts,ReleaseTaskRepository tasks){return args->{for(Artifact artifact:artifacts.findAll())if(artifact.getCreatedBy()==null||artifact.getCreatedBy().isBlank())tasks.findById(artifact.getBuildTaskId()).map(ReleaseTask::getCreatedBy).filter(v->v!=null&&!v.isBlank()).ifPresent(v->{artifact.setCreatedBy(v);artifacts.save(artifact);});};}
}
