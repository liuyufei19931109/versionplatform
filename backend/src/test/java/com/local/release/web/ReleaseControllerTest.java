package com.local.release.web;

import com.local.release.domain.ModelEnums;
import com.local.release.domain.ReleaseTask;
import com.local.release.repo.ReleaseStepRepository;
import com.local.release.repo.ReleaseTaskRepository;
import com.local.release.repo.AppServiceRepository;
import com.local.release.security.TotpService;
import com.local.release.service.ReleaseExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReleaseControllerTest {
  private final ReleaseTaskRepository tasks = mock(ReleaseTaskRepository.class);
  private final ReleaseExecutor executor = mock(ReleaseExecutor.class);
  private final TotpService totp = mock(TotpService.class);
  private final ReleaseController controller = new ReleaseController(tasks, mock(ReleaseStepRepository.class), executor, totp, mock(AppServiceRepository.class));
  private final Principal admin = () -> "admin";

  @Test void runningCancelOnlyAcceptsOwnerOrAdminAndDoesNotConsumeTotp(){
    ReleaseTask task=deployment();task.setCreatedBy("owner");task.setStatus(ModelEnums.TaskStatus.RUNNING);
    when(tasks.findById(5L)).thenReturn(Optional.of(task));
    assertThatThrownBy(()->controller.cancel(5L,()->"other")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verify(executor,never()).cancelDeployment(any());
    assertThat(controller.cancel(5L,()->"owner").get("message")).contains("已请求","核对远端");
    controller.cancel(5L,admin);verify(executor,times(2)).cancelDeployment(5L);verifyNoInteractions(totp);
  }

  @BeforeEach
  void executeSubmissionCallback() {
    when(executor.withSubmissionLock(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
  }

  @Test
  void approvalStatusBelongsToTheLoggedInAccount() {
    var now = java.time.Instant.parse("2026-10-22T12:00:00Z");
    when(totp.availability("yuan")).thenReturn(new TotpService.Availability(true,now,now.plusSeconds(20),20));
    var result = controller.productionApproval(() -> "yuan");
    assertThat(result.approver()).isEqualTo("yuan");
    assertThat(result.retryAfterSeconds()).isEqualTo(20);
    verify(totp).availability("yuan");verifyNoMoreInteractions(totp);
  }

  @Test
  void nonAdminCreationAndRetryUseTheCurrentOperatorRatherThanAdminOrOriginalOwner() {
    Principal operator = () -> "yuan";
    controller.create(request(false),operator);
    ReleaseTask previous = deployment();previous.setCreatedBy("original-owner");
    when(tasks.findById(5L)).thenReturn(Optional.of(previous));
    controller.retry(5L,new ReleaseController.RetryRequest("654321","retry-other-user"),operator);
    verify(totp).verifyAndConsume("yuan","123456");
    verify(totp).verifyAndConsume("yuan","654321");verifyNoMoreInteractions(totp);
    verify(executor).retryDeployment(5L,"retry-other-user","yuan");
  }

  @Test
  void rejectedPersonalCodeCannotCreateOrRetryProductionTasks() {
    doThrow(new IllegalArgumentException("动态码不正确")).when(totp).verifyAndConsume(eq("yuan"),anyString());
    assertThatThrownBy(() -> controller.create(request(false),() -> "yuan")).hasMessage("动态码不正确");
    when(tasks.findById(5L)).thenReturn(Optional.of(deployment()));
    assertThatThrownBy(() -> controller.retry(5L,new ReleaseController.RetryRequest("123456","retry-rejected"),() -> "yuan")).hasMessage("动态码不正确");
    verify(executor,never()).createDeployment(any(),any(),any(),any(),any(),anyBoolean(),any(),any());
    verify(executor,never()).retryDeployment(any(),any(),any());
    verify(totp,never()).verifyAndConsume(eq("admin"),any());
  }

  @Test
  void preproductionStillDoesNotRequireADynamicCode() {
    var request = new ReleaseController.CreateRelease(1L,2L,"test",List.of(3L),"note",false,null,"pre-request");
    controller.create(request,() -> "yuan");verifyNoInteractions(totp);
    verify(executor).createDeployment(1L,2L,"test",List.of(3L),"note",false,"yuan","pre-request");
  }

  @Test
  void invalidProductionConfigurationDoesNotConsumeCode() {
    doThrow(new IllegalArgumentException("目标环境尚未配置"))
      .when(executor).precheckDeployment(1L, 2L, "prod", List.of(3L));
    assertThatThrownBy(() -> controller.create(request(false), admin)).hasMessage("目标环境尚未配置");
    verifyNoInteractions(totp);
  }

  @Test void changedReviewIsRejectedBeforeProductionOtpAndEnqueue() {
    var request = new ReleaseController.CreateRelease(1L,2L,"prod",List.of(3L),"note",false,"123456","request-1","old-token");
    doThrow(new IllegalArgumentException("请重新核对"))
        .when(executor).requireDeploymentReview(1L,2L,"prod",List.of(3L),"old-token");
    assertThatThrownBy(()->controller.create(request,admin)).hasMessageContaining("核对");
    verifyNoInteractions(totp);
    verify(executor,never()).createDeployment(any(),any(),any(),any(),any(),anyBoolean(),any(),any());
  }

  @Test
  void repeatedProductionRequestReturnsExistingTaskWithoutConsumingCode() {
    ReleaseTask task = deployment();
    when(executor.existingDeployment(1L, 2L, "prod", List.of(3L), "note", "request-1", "admin")).thenReturn(task);
    assertThat(controller.create(request(false), admin)).isSameAs(task);
    verifyNoInteractions(totp);
    verify(executor, never()).precheckDeployment(any(), any(), any(), any());
  }

  @Test
  void productionCreationPrechecksThenConsumesThenCreates() {
    controller.create(request(false), admin);
    var order = inOrder(executor, totp);
    order.verify(executor).existingDeployment(1L, 2L, "prod", List.of(3L), "note", "request-1", "admin");
    order.verify(executor).precheckDeployment(1L, 2L, "prod", List.of(3L));
    order.verify(totp).verifyAndConsume("admin", "123456");
    order.verify(executor).createDeployment(1L, 2L, "prod", List.of(3L), "note", false, "admin", "request-1");
  }

  @Test
  void simulationCannotBeUsedToBypassRealReleaseControls() {
    assertThatThrownBy(() -> controller.create(request(true), admin)).hasMessageContaining("模拟发布已停用");
    verifyNoInteractions(executor, totp);
  }

  @Test
  void retryRejectsBuildTaskBeforeConsumingCode() {
    ReleaseTask build = new ReleaseTask(1L, ModelEnums.TaskKind.BUILD, null, "prod", "main", "", false);
    when(tasks.findById(5L)).thenReturn(Optional.of(build));
    assertThatThrownBy(() -> controller.retry(5L, new ReleaseController.RetryRequest("123456", "retry-1"), admin))
      .hasMessage("不是发版任务");
    verifyNoInteractions(totp);
    verify(executor, never()).precheckDeploymentRetry(any());
    verify(executor, never()).retryDeployment(any(), any(), any());
  }

  @Test
  void retriedRequestIsIdempotentAndInvalidStateDoesNotConsumeCode() {
    when(tasks.findById(5L)).thenReturn(Optional.of(deployment()));
    when(executor.existingDeploymentRetry(5L, "retry-1", "admin")).thenReturn(true);
    controller.retry(5L, new ReleaseController.RetryRequest("123456", "retry-1"), admin);
    verifyNoInteractions(totp);
    verify(executor, never()).retryDeployment(any(), any(), any());
    when(executor.existingDeploymentRetry(5L, "retry-2", "admin")).thenReturn(false);
    doThrow(new IllegalArgumentException("只有失败任务可以重试")).when(executor).precheckDeploymentRetry(5L);
    assertThatThrownBy(() -> controller.retry(5L, new ReleaseController.RetryRequest("123456", "retry-2"), admin))
      .hasMessageContaining("失败任务");
    verifyNoInteractions(totp);
  }

  @Test void productionReadinessKeepsLatestPreAndProductionSuccessPerArtifact() {
    ReleaseTask olderPre = success(11L, 2L, "test", 1), newerPre = success(12L, 2L, "test", 3), prod = success(13L, 2L, "prod", 4),
      onlyPre = success(14L, 7L, "test", 2), app = success(15L, 8L, "test", 5);
    app.setDeliveryType("MOBILE");
    when(tasks.findByServiceIdAndTaskKindAndStatusAndSimulateFalseAndEnvironmentNameIn(1L, ModelEnums.TaskKind.DEPLOY,
      ModelEnums.TaskStatus.SUCCESS, List.of("test", "prod"))).thenReturn(List.of(newerPre, olderPre, prod, onlyPre, app));
    List<ReleaseController.ProductionReadiness> result = controller.productionReadiness(1L);
    assertThat(result).extracting(ReleaseController.ProductionReadiness::artifactId).containsExactly(7L, 2L);
    assertThat(result.get(1).preTaskId()).isEqualTo(12L);
    assertThat(result.get(1).prodTaskId()).isEqualTo(13L);
    assertThat(result.get(0).prodTaskId()).isNull();
  }

  private ReleaseTask success(Long id, Long artifactId, String environment, int hour) {
    ReleaseTask task = new ReleaseTask(1L, ModelEnums.TaskKind.DEPLOY, artifactId, environment, "main", "", false);
    org.springframework.test.util.ReflectionTestUtils.setField(task, "id", id);
    task.setStatus(ModelEnums.TaskStatus.SUCCESS);
    task.setFinishedAt(java.time.LocalDateTime.of(2026, 10, 19, hour, 0));
    return task;
  }

  private ReleaseController.CreateRelease request(boolean simulate) {
    return new ReleaseController.CreateRelease(1L, 2L, "prod", List.of(3L), "note", simulate, "123456", "request-1");
  }

  private ReleaseTask deployment() {
    return new ReleaseTask(1L, ModelEnums.TaskKind.DEPLOY, 2L, "prod", "main", "", false);
  }
}
