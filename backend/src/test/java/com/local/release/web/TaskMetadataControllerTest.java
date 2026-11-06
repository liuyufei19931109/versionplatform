package com.local.release.web;

import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.*;
import com.local.release.security.TotpService;
import com.local.release.service.ReleaseExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskMetadataControllerTest {
  private final ReleaseTaskRepository tasks=mock(ReleaseTaskRepository.class);
  private final ReleaseStepRepository steps=mock(ReleaseStepRepository.class);
  private final AppServiceRepository services=mock(AppServiceRepository.class);
  private final ReleaseExecutor executor=mock(ReleaseExecutor.class);
  private final ReleaseTask build=ReleaseTaskViewTest.task(9L,35L);
  private final ReleaseTask deployment=new ReleaseTask(35L,TaskKind.DEPLOY,4L,"prod","main","",false);
  private MockMvc mvc;

  @BeforeEach void setup(){
    ReflectionTestUtils.setField(deployment,"id",10L);
    when(services.findAllById(any())).thenReturn(List.of(ReleaseTaskViewTest.service(35L,"示例前端",ServiceType.FRONTEND)));
    mvc=MockMvcBuilders.standaloneSetup(new BuildController(tasks,steps,services,executor),new ReleaseController(tasks,steps,executor,mock(TotpService.class),services),new SystemController(services,tasks,mock(ArtifactRepository.class))).build();
  }

  @Test void buildListAndDetailExposeTheSameNamedTaskShape() throws Exception {
    when(tasks.findByTaskKindOrderByCreatedAtDesc(TaskKind.BUILD)).thenReturn(List.of(build));when(tasks.findById(9L)).thenReturn(Optional.of(build));
    mvc.perform(get("/api/builds")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(9)).andExpect(jsonPath("$[0].serviceName").value("示例前端")).andExpect(jsonPath("$[0].serviceType").value("FRONTEND"));
    mvc.perform(get("/api/builds/9")).andExpect(status().isOk()).andExpect(jsonPath("$.task.serviceName").value("示例前端")).andExpect(jsonPath("$.task.serviceType").value("FRONTEND")).andExpect(jsonPath("$.serviceType").value("FRONTEND")).andExpect(jsonPath("$.steps").isArray());
    verify(services,times(2)).findAllById(Set.of(35L));verify(services,never()).findById(anyLong());
  }

  @Test void releaseListDetailAndDashboardExposeConsistentMetadataWithoutCallingExecution() throws Exception {
    when(tasks.findByServiceIdAndTaskKindOrderByCreatedAtDesc(35L,TaskKind.DEPLOY)).thenReturn(List.of(deployment));when(tasks.findById(10L)).thenReturn(Optional.of(deployment));when(tasks.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(deployment,build));
    mvc.perform(get("/api/releases").param("serviceId","35")).andExpect(status().isOk()).andExpect(jsonPath("$[0].serviceName").value("示例前端")).andExpect(jsonPath("$[0].serviceType").value("FRONTEND"));
    mvc.perform(get("/api/releases/10")).andExpect(status().isOk()).andExpect(jsonPath("$.task.serviceName").value("示例前端")).andExpect(jsonPath("$.task.serviceType").value("FRONTEND")).andExpect(jsonPath("$.steps").isArray());
    mvc.perform(get("/api/dashboard")).andExpect(status().isOk()).andExpect(jsonPath("$.recentReleases[0].id").value(10)).andExpect(jsonPath("$.recentReleases[0].serviceName").value("示例前端")).andExpect(jsonPath("$.recentReleases[0].serviceType").value("FRONTEND"));
    verify(services,times(3)).findAllById(Set.of(35L));verify(services,never()).findById(anyLong());verifyNoInteractions(executor);
  }

  @Test void historicalBuildDetailStaysReadableAfterServiceDeletion() throws Exception {
    when(tasks.findById(9L)).thenReturn(Optional.of(build));when(services.findAllById(any())).thenReturn(List.of());
    mvc.perform(get("/api/builds/9")).andExpect(status().isOk()).andExpect(jsonPath("$.task.id").value(9)).andExpect(jsonPath("$.task.serviceName").value("已删除服务 #35")).andExpect(jsonPath("$.task.serviceType").isEmpty());
  }
}
