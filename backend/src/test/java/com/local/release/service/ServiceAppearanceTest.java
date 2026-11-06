package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.AppService;
import com.local.release.domain.ServiceAppearance;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ServiceAppearanceTest {
  private final ObjectMapper json=new ObjectMapper();
  @Test void onlyTheTwentyBuiltInIconsAreAcceptedAndBadHistoricValuesFallBack() {
    assertThat(ServiceAppearance.ICONS).hasSize(20);
    for(String key:ServiceAppearance.ICONS)assertThat(ServiceAppearance.icon(ServiceAppearance.configuration("{}","{\"displayIcon\":\""+key+"\"}"))).isEqualTo(key);
    for(String config:new String[]{"{}","invalid","null","{\"displayIcon\":\"https://example.test/icon.svg\"}","{\"displayIcon\":17}"})assertThat(ServiceAppearance.icon(config)).isNull();
    for(String config:new String[]{"{\"displayIcon\":17}","{\"displayIcon\":null}","{\"displayIcon\":\"unknown\"}"})assertThatThrownBy(()->ServiceAppearance.configuration("{}",config)).hasMessageContaining("内置服务图标");
  }
  @Test void olderClientsPreserveSavedChoiceAndAutomaticMatchingCanBeExplicitlyRestored()throws Exception {
    String previous="{\"displayIcon\":\"operations\"}";
    var saved=json.readTree(ServiceAppearance.configuration(previous,"{\"mobileBuild\":{\"flutterVersion\":\"3.47.3\"},\"custom\":true}"));
    assertThat(saved.path("displayIcon").asText()).isEqualTo("operations");assertThat(saved.path("mobileBuild").path("flutterVersion").asText()).isEqualTo("3.47.3");assertThat(saved.path("custom").asBoolean()).isTrue();
    assertThat(ServiceAppearance.icon(ServiceAppearance.configuration(previous,"{\"displayIcon\":\"\"}"))).isNull();
    AppService service=new AppService();service.setConfigJson(saved.toString());assertThat(service.getDisplayIcon()).isEqualTo("operations");
    assertThat(json.readTree(json.writeValueAsString(service)).path("displayIcon").asText()).isEqualTo("operations");
  }
}
