package com.local.release.web;

import com.local.release.service.VersionManagementService;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDate;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VersionDateControllerTest {
  VersionManagementService versions;
  MockMvc mvc;
  @BeforeEach void setup(){
    versions=mock(VersionManagementService.class);
    mvc=MockMvcBuilders.standaloneSetup(new VersionController(versions)).setControllerAdvice(new ApiExceptionHandler()).build();
  }
  @Test void optionalIsoDateReachesPreviewWithoutChangingCreateContract()throws Exception{
    mvc.perform(post("/api/versions/preview").principal(()->"admin").contentType(MediaType.APPLICATION_JSON)
      .content("{\"serviceIds\":[12],\"versionDate\":\"2026-11-05\"}")).andExpect(status().isOk());
    verify(versions).preview(List.of(12L),null,LocalDate.of(2026,11,5),"admin");
    mvc.perform(post("/api/versions").principal(()->"admin").contentType(MediaType.APPLICATION_JSON)
      .content("{\"previewId\":\"locked-preview\",\"versionDate\":\"2099-12-31\"}")).andExpect(status().isOk());
    verify(versions).create("locked-preview","admin");verifyNoMoreInteractions(versions);
  }
  @Test void omittingDatePreservesExistingPreviewAndRetryClients()throws Exception{
    mvc.perform(post("/api/versions/preview").principal(()->"admin").contentType(MediaType.APPLICATION_JSON)
      .content("{\"serviceIds\":[12]}")).andExpect(status().isOk());
    mvc.perform(post("/api/versions/preview").principal(()->"admin").contentType(MediaType.APPLICATION_JSON)
      .content("{\"serviceIds\":[12],\"versionId\":3}")).andExpect(status().isOk());
    verify(versions).preview(List.of(12L),null,null,"admin");verify(versions).preview(List.of(12L),3L,null,"admin");
  }
  @Test void malformedAndImpossibleDatesAreRejectedBeforeAnyServiceCall()throws Exception{
    for(String value:List.of("2026/11/05","2026-02-30","not-a-date")){
      mvc.perform(post("/api/versions/preview").principal(()->"admin").contentType(MediaType.APPLICATION_JSON)
        .content("{\"serviceIds\":[12],\"versionDate\":\""+value+"\"}")).andExpect(status().isBadRequest());
    }
    verifyNoInteractions(versions);
  }
  @Test void explicitLinkEndpointsBindVersionServiceAndPrincipal()throws Exception{
    mvc.perform(post("/api/versions/3/services/12/link-preview").principal(()->"admin")).andExpect(status().isOk());
    verify(versions).previewLink(3L,12L,"admin");
    mvc.perform(post("/api/versions/3/link-existing").principal(()->"admin").contentType(MediaType.APPLICATION_JSON)
      .content("{\"previewId\":\"locked-link-preview\"}")).andExpect(status().isOk());
    verify(versions).linkExisting(3L,"locked-link-preview","admin");verifyNoMoreInteractions(versions);
  }
}
