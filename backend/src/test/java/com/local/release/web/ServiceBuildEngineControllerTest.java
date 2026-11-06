package com.local.release.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.local.release.domain.AppService;
import com.local.release.domain.ModelEnums.ServiceType;
import com.local.release.repo.AppServiceRepository;
import com.local.release.service.GitRepositoryInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ServiceBuildEngineControllerTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private AppServiceRepository services;
  private ServiceController controller;
  private MockMvc mvc;

  @BeforeEach void setup() {
    services = mock(AppServiceRepository.class);
    when(services.save(any())).thenAnswer(call -> call.getArgument(0));
    controller = new ServiceController(services, mock(GitRepositoryInspector.class));
    mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @ParameterizedTest @EnumSource(ServiceType.class)
  void omittedEngineCreatesGithubServiceForEveryType(ServiceType type) throws Exception {
    mvc.perform(post(endpoint(type)).contentType(MediaType.APPLICATION_JSON).content(request(type).toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.buildEngine").value("GITHUB"));
    verify(services).save(argThat(service -> service.getBuildEngine().equals("GITHUB") && service.getServiceType() == type));
  }

  @ParameterizedTest @EnumSource(ServiceType.class)
  void omittedEngineInOldJsonUpdatePreservesLocalSelection(ServiceType type) throws Exception {
    AppService existing = existing(type, "LOCAL");
    mvc.perform(put(endpoint(type) + "/7").contentType(MediaType.APPLICATION_JSON).content(request(type).toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.buildEngine").value("LOCAL"));
    assertThat(existing.getBuildEngine()).isEqualTo("LOCAL");
    verify(services).save(existing);
  }

  @Test void oldJavaConstructorRemainsCompatibleAndPreservesSelection() {
    var oldRequest = new ServiceController.ServiceRequest("service", "test-service", ServiceType.BACKEND,
      null, true, null, null, null, null, null, null, null, null, null, null, null, null, null);
    assertThat(oldRequest.buildEngine()).isNull();
    assertThat(controller.create(oldRequest).getBuildEngine()).isEqualTo("GITHUB");
    AppService existing = existing(ServiceType.BACKEND, "LOCAL");
    assertThat(controller.update(7L, oldRequest)).isSameAs(existing);
    assertThat(existing.getBuildEngine()).isEqualTo("LOCAL");
  }

  @Test void explicitEngineCanSwitchBothWaysAndSerializesCanonicalValue() throws Exception {
    AppService existing = existing(ServiceType.BACKEND, "GITHUB");
    mvc.perform(put("/api/services/7").contentType(MediaType.APPLICATION_JSON)
        .content(request(ServiceType.BACKEND).put("buildEngine", " local ").toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.buildEngine").value("LOCAL"));
    assertThat(existing.getBuildEngine()).isEqualTo("LOCAL");
    mvc.perform(put("/api/services/7").contentType(MediaType.APPLICATION_JSON)
        .content(request(ServiceType.BACKEND).put("buildEngine", " Github ").toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.buildEngine").value("GITHUB"));
    assertThat(existing.getBuildEngine()).isEqualTo("GITHUB");
  }

  @Test void nullJsonEngineIsTreatedAsAnOmittedOptionalField() throws Exception {
    existing(ServiceType.BACKEND, "LOCAL");
    mvc.perform(put("/api/services/7").contentType(MediaType.APPLICATION_JSON)
        .content(request(ServiceType.BACKEND).putNull("buildEngine").toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.buildEngine").value("LOCAL"));
  }

  @ParameterizedTest @ValueSource(strings = {"", "   ", "AUTO", "github-actions"})
  void invalidEngineIsRejectedBeforeCreateOrUpdateCanSave(String engine) throws Exception {
    AppService existing = existing(ServiceType.BACKEND, "LOCAL");
    String body = request(ServiceType.BACKEND).put("buildEngine", engine).toString();
    mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON).content(body))
      .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    mvc.perform(put("/api/services/7").contentType(MediaType.APPLICATION_JSON).content(body))
      .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    verify(services, never()).save(any());
    assertThat(existing.getBuildEngine()).isEqualTo("LOCAL");
  }

  @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "AUTO", "cloud"})
  void entityRejectsInvalidExplicitValuesWithoutChangingCurrentValue(String engine) {
    AppService service = new AppService();
    service.setBuildEngine("LOCAL");
    assertThatThrownBy(() -> service.setBuildEngine(engine)).isInstanceOf(IllegalArgumentException.class);
    assertThat(service.getBuildEngine()).isEqualTo("LOCAL");
  }

  private AppService existing(ServiceType type, String engine) {
    AppService service = new AppService();
    service.setName("service");service.setServiceKey("test-service");service.setServiceType(type);service.setBuildEngine(engine);
    when(services.findById(7L)).thenReturn(Optional.of(service));
    return service;
  }

  private ObjectNode request(ServiceType type) {
    // Disabled Flutter drafts are valid without credentials or a remote build configuration.
    return JSON.createObjectNode().put("name", "service").put("serviceKey", "test-service")
      .put("serviceType", type.name()).put("enabled", false).put("configJson", "{}");
  }

  private String endpoint(ServiceType type) {
    return type == ServiceType.FLUTTER_ANDROID ? "/api/apps/services" : "/api/services";
  }
}
