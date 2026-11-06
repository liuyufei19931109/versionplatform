package com.local.release.service;

import com.local.release.domain.AppService;
import com.local.release.domain.ModelEnums.ServiceType;
import com.local.release.repo.AppServiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class ServiceBuildEnginePersistenceTest {
  @Autowired AppServiceRepository services;
  @Autowired TestEntityManager entities;
  @Autowired JdbcTemplate jdbc;

  @ParameterizedTest @EnumSource(ServiceType.class)
  void defaultAndExplicitSelectionsSurviveReloadForEveryType(ServiceType type) {
    AppService service = service(type);
    Long id = services.saveAndFlush(service).getId();
    entities.clear();
    assertThat(rawEngine(id)).isEqualTo("GITHUB");
    service = services.findById(id).orElseThrow();
    assertThat(service.getBuildEngine()).isEqualTo("GITHUB");

    service.setBuildEngine(" local ");
    services.saveAndFlush(service);entities.clear();
    assertThat(rawEngine(id)).isEqualTo("LOCAL");
    service = services.findById(id).orElseThrow();
    assertThat(service.getBuildEngine()).isEqualTo("LOCAL");

    service.setBuildEngine(" Github ");
    services.saveAndFlush(service);entities.clear();
    assertThat(rawEngine(id)).isEqualTo("GITHUB");
    assertThat(services.findById(id).orElseThrow().getBuildEngine()).isEqualTo("GITHUB");
  }

  @Test void nullableLegacyRowReadsAsGithubAndCanBeExplicitlyChanged() {
    Long id = services.saveAndFlush(service(ServiceType.BACKEND)).getId();
    jdbc.update("update app_service set build_engine = null where id = ?", id);
    entities.clear();
    AppService legacy = services.findById(id).orElseThrow();
    assertThat(rawEngine(id)).isNull();
    assertThat(legacy.getBuildEngine()).isEqualTo("GITHUB");
    legacy.setName("updated legacy service");
    services.saveAndFlush(legacy);entities.clear();
    assertThat(services.findById(id).orElseThrow().getBuildEngine()).isEqualTo("GITHUB");

    AppService reloaded = services.findById(id).orElseThrow();
    reloaded.setBuildEngine("LOCAL");
    services.saveAndFlush(reloaded);entities.clear();
    assertThat(rawEngine(id)).isEqualTo("LOCAL");
    assertThat(services.findById(id).orElseThrow().getBuildEngine()).isEqualTo("LOCAL");
  }

  @Test void databaseColumnAllowsLegacyNullAndHasBoundedLength() {
    String condition = " from information_schema.columns where table_name = 'APP_SERVICE' and column_name = 'BUILD_ENGINE'";
    assertThat(jdbc.queryForObject("select is_nullable" + condition, String.class)).isEqualTo("YES");
    assertThat(jdbc.queryForObject("select character_maximum_length" + condition, Long.class)).isEqualTo(16L);
  }

  private AppService service(ServiceType type) {
    AppService service = new AppService();
    service.setName("service");service.setServiceKey("service-" + type.name());service.setServiceType(type);
    return service;
  }

  private String rawEngine(Long id) {
    return jdbc.queryForObject("select build_engine from app_service where id = ?", String.class, id);
  }
}
