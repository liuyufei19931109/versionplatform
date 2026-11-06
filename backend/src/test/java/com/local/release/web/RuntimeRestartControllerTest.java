package com.local.release.web;

import com.local.release.domain.RuntimeOperation;
import com.local.release.service.*;
import com.local.release.security.TotpService;
import org.junit.jupiter.api.*;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeRestartControllerTest {
  final RuntimeRestartService service=mock(RuntimeRestartService.class);
  final ReleaseExecutor executor=mock(ReleaseExecutor.class);
  final TotpService totp=mock(TotpService.class);
  final RuntimeRestartController controller=new RuntimeRestartController(service,executor,totp);
  final Principal admin=()->"admin";
  final RuntimeRestartController.RestartRequest request=new RuntimeRestartController.RestartRequest("confirmation","request-1","123456");
  @BeforeEach void setup(){when(executor.withSubmissionLock(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());}
  RuntimeRestartService.Preview preview(String environment){return new RuntimeRestartService.Preview("confirmation",2L,1L,"ui","ui",environment,3L,"node","host",List.of(),Instant.now().plusSeconds(120));}
  @Test void productionChecksTargetThenConsumesCodeThenQueues() {
    when(service.precheck("confirmation","admin")).thenReturn(preview("prod"));controller.restart(request,admin);
    var order=inOrder(service,totp);order.verify(service).existing("confirmation","request-1","admin");
    order.verify(service).precheck("confirmation","admin");order.verify(totp).verifyAndConsume("admin","123456");order.verify(service).create("confirmation","request-1","admin");
  }
  @Test void duplicateRequestDoesNotConsumeAnotherCodeOrRestartAgain() {
    RuntimeOperation operation=mock(RuntimeOperation.class);when(service.existing("confirmation","request-1","admin")).thenReturn(operation);
    assertThat(controller.restart(request,admin)).isSameAs(operation);verifyNoInteractions(totp);verify(service,never()).create(any(),any(),any());
  }
  @Test void productionRestartUsesTheCurrentOperatorsAuthenticator() {
    when(service.precheck("confirmation","yuan")).thenReturn(preview("prod"));controller.restart(request,()->"yuan");
    verify(totp).verifyAndConsume("yuan","123456");verifyNoMoreInteractions(totp);
    verify(service).create("confirmation","request-1","yuan");
  }
  @Test void invalidTargetAndBadCodeDoNotCreateOperations() {
    when(service.precheck("confirmation","admin")).thenThrow(new IllegalArgumentException("target changed"));
    assertThatThrownBy(()->controller.restart(request,admin)).hasMessage("target changed");verifyNoInteractions(totp);
    doReturn(preview("prod")).when(service).precheck("confirmation","admin");doThrow(new IllegalArgumentException("bad code")).when(totp).verifyAndConsume(any(),any());
    assertThatThrownBy(()->controller.restart(request,admin)).hasMessage("bad code");verify(service,never()).create(any(),any(),any());
  }
  @Test void preproductionDoesNotUseProductionApproval() {
    when(service.precheck("confirmation","admin")).thenReturn(preview("test"));controller.restart(request,admin);verifyNoInteractions(totp);verify(service).create("confirmation","request-1","admin");
  }
  @Test void cancellationUsesPrincipalAndDoesNotConsumeNewProductionApproval(){
    when(service.cancel(8L,"admin")).thenReturn("已请求中止，等待当前远程命令退出；远端状态需核对");
    assertThat(controller.cancel(8L,admin)).containsEntry("message","已请求中止，等待当前远程命令退出；远端状态需核对");
    verify(service).cancel(8L,"admin");verifyNoInteractions(totp,executor);
  }
}
