package com.local.release.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.AppServiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReleaseTaskViewTest {
  @Test void batchesDistinctServicesAndPreservesTaskOrder() {
    AppServiceRepository services = mock(AppServiceRepository.class);
    when(services.findAllById(any())).thenReturn(List.of(service(35L, "示例前端", ServiceType.FRONTEND), service(33L, "示例后端", ServiceType.BACKEND)));
    List<ReleaseTaskView> result = ReleaseTaskView.list(List.of(task(9L,35L), task(8L,33L), task(7L,35L)), services);
    assertThat(result).extracting(ReleaseTaskView::id).containsExactly(9L,8L,7L);
    assertThat(result).extracting(ReleaseTaskView::serviceName).containsExactly("示例前端","示例后端","示例前端");
    assertThat(result).extracting(ReleaseTaskView::serviceKey).containsExactly("service-35","service-33","service-35");
    assertThat(result).extracting(ReleaseTaskView::serviceType).containsExactly(ServiceType.FRONTEND,ServiceType.BACKEND,ServiceType.FRONTEND);
    verify(services).findAllById(new LinkedHashSet<>(List.of(35L,33L)));
    verifyNoMoreInteractions(services);
  }

  @Test void explicitSerializationKeepsPublicFieldsAndNeverIncludesServiceConfigurationOrRequestKeys() throws Exception {
    AppServiceRepository services = mock(AppServiceRepository.class);
    AppService service = service(35L,"示例前端",ServiceType.FRONTEND);
    service.setGitRepo("https://secret-git-token@example.invalid/repo");service.setEnvJson("{\"password\":\"private-env-secret\"}");service.setConfigJson("{\"token\":\"private-config-secret\",\"displayIcon\":\"website\"}");
    when(services.findAllById(any())).thenReturn(List.of(service));
    ReleaseTask task=task(9L,35L);task.setRequestKey("private-request-key");task.setRequestFingerprint("private-request-fingerprint");task.setLastRetryRequestKey("private-retry-key");task.setImageTagUpgraded(true);
    task.setCreatedBy("admin");task.setTargetServerIds(List.of(3L,4L));task.setCommitId("1234567890abcdef1234567890abcdef12345678");
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();String json=mapper.writeValueAsString(ReleaseTaskView.one(task,services));JsonNode node=mapper.readTree(json);
    Set<String> names=new HashSet<>();node.fieldNames().forEachRemaining(names::add);
    assertThat(names).containsExactlyInAnyOrder("id","serviceId","serviceName","serviceKey","serviceType","taskKind","artifactId","environmentName","branch","commitId","status","currentStep","errorMessage","releaseNote","createdBy","targetServerIds","simulate","startedAt","finishedAt","createdAt","updatedAt","deliveryType","mobileOutputFormat","mobileBuildMode","versionName","versionCode","preBuildTaskId","mobileBuildEngine","displayIcon","frontendBuildEngine","backendBuildEngine");
    assertThat(node.get("displayIcon").asText()).isEqualTo("website");
    assertThat(node.get("deliveryType").asText()).isEqualTo("SERVER");
    assertThat(node.get("mobileOutputFormat").isNull()).isTrue();
    assertThat(node.get("mobileBuildMode").isNull()).isTrue();
    assertThat(node.get("versionName").isNull()).isTrue();assertThat(node.get("versionCode").isNull()).isTrue();assertThat(node.get("preBuildTaskId").isNull()).isTrue();
    assertThat(node.get("serviceKey").asText()).isEqualTo("service-35");
    assertThat(node.get("serviceName").asText()).isEqualTo("示例前端");assertThat(node.get("serviceType").asText()).isEqualTo("FRONTEND");assertThat(node.get("targetServerIds").asText()).isEqualTo("3,4");
    assertThat(json).doesNotContain("secret-git-token","private-env-secret","private-config-secret","private-request-key","private-request-fingerprint","private-retry-key","imageTagUpgraded");
  }

  @Test void deletedServiceRetainsReadableTaskHistoryAndEmptyListDoesNotQuery() {
    AppServiceRepository services=mock(AppServiceRepository.class);
    assertThat(ReleaseTaskView.list(List.of(),services)).isEmpty();verifyNoInteractions(services);
    when(services.findAllById(any())).thenReturn(List.of());ReleaseTaskView deleted=ReleaseTaskView.one(task(9L,35L),services);
    assertThat(deleted.serviceName()).isEqualTo("已删除服务 #35");assertThat(deleted.serviceType()).isNull();assertThat(deleted.id()).isEqualTo(9L);
    assertThat(deleted.serviceKey()).isNull();
  }
  @Test void mobileFormatUsesSnapshotWithoutExposingPrivateConfiguration()throws Exception{
    AppService app=service(1L,"App",ServiceType.FLUTTER_ANDROID);app.setGitRepo("https://private-token@example.invalid/app");
    app.setConfigJson("{\"mobileBuild\":{\"flutterVersion\":\"3.35.4\",\"applicationId\":\"com.example.app\",\"expectedCertificateSha256\":\""+"a".repeat(64)+"\",\"outputFormat\":\"APK\",\"signingProfile\":\"private-profile\"}}");
    ReleaseTask task=task(9L,1L);task.setDeliveryType("MOBILE");task.setMobileBuildConfigSnapshot(com.local.release.service.FlutterBuildConfiguration.snapshot(app));app.setConfigJson("{}");
    AppServiceRepository services=mock(AppServiceRepository.class);when(services.findAllById(any())).thenReturn(List.of(app));var view=ReleaseTaskView.one(task,services);
    assertThat(view.mobileOutputFormat()).isEqualTo("APK");String json=new ObjectMapper().findAndRegisterModules().writeValueAsString(view);
    assertThat(json).doesNotContain("private-token","private-profile","mobileBuildConfigSnapshot");
  }

  static AppService service(Long id,String name,ServiceType type){AppService service=new AppService();ReflectionTestUtils.setField(service,"id",id);service.setName(name);service.setServiceKey("service-"+id);service.setServiceType(type);return service;}
  static ReleaseTask task(Long id,Long serviceId){ReleaseTask task=new ReleaseTask(serviceId,TaskKind.BUILD,null,"build","main","release note",false);ReflectionTestUtils.setField(task,"id",id);ReflectionTestUtils.setField(task,"createdAt",LocalDateTime.of(2026,9,5,12,0));return task;}
}
