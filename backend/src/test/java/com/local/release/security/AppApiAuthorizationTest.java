package com.local.release.security;

import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.*;
import com.local.release.service.*;
import com.local.release.web.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.net.URI;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AppApiAuthorizationTest {
  AppServiceRepository services;ReleaseTaskRepository tasks;ArtifactRepository artifacts;MobileArtifactRepository metadata;
  ReleaseExecutor executor;MobileReleaseService mobile;GitRepositoryInspector git;BranchPolicyService branches;ProductionMergeService merges;MobileSigningCatalog signingCatalog;
  TotpService totp;
  AppService server,app;ReleaseTask serverBuild,appBuild,serverRelease,appRelease;Artifact serverArtifact,appArtifact;MockMvc mvc;
  @BeforeEach void setup(){
    services=mock(AppServiceRepository.class);tasks=mock(ReleaseTaskRepository.class);artifacts=mock(ArtifactRepository.class);metadata=mock(MobileArtifactRepository.class);
    executor=mock(ReleaseExecutor.class);mobile=mock(MobileReleaseService.class);git=mock(GitRepositoryInspector.class);branches=mock(BranchPolicyService.class);merges=mock(ProductionMergeService.class);
    signingCatalog=mock(MobileSigningCatalog.class);totp=mock(TotpService.class);
    var users=mock(AdminUserRepository.class);var tokens=mock(TokenService.class);var steps=mock(ReleaseStepRepository.class);
    server=service(1L,ServiceType.BACKEND);app=service(2L,ServiceType.FLUTTER_ANDROID);
    serverBuild=task(10L,1L,TaskKind.BUILD,false);appBuild=task(20L,2L,TaskKind.BUILD,true);serverRelease=task(11L,1L,TaskKind.DEPLOY,false);appRelease=task(21L,2L,TaskKind.DEPLOY,true);
    serverArtifact=artifact(100L,1L,10L);appArtifact=artifact(200L,2L,20L);
    for(AppService s:List.of(server,app))when(services.findById(s.getId())).thenReturn(Optional.of(s));
    when(services.findAll()).thenReturn(List.of(server,app));when(services.findAllById(any())).thenAnswer(call->{Set<Long> ids=new HashSet<>();((Iterable<Long>)call.getArgument(0)).forEach(ids::add);return List.of(server,app).stream().filter(s->ids.contains(s.getId())).toList();});
    when(services.save(any())).thenAnswer(call->{AppService s=call.getArgument(0);if(s.getId()==null)ReflectionTestUtils.setField(s,"id",50L);return s;});
    for(ReleaseTask t:List.of(serverBuild,appBuild,serverRelease,appRelease))when(tasks.findById(t.getId())).thenReturn(Optional.of(t));
    when(tasks.findByTaskKindOrderByCreatedAtDesc(TaskKind.BUILD)).thenReturn(List.of(serverBuild,appBuild));when(tasks.findByTaskKindOrderByCreatedAtDesc(TaskKind.DEPLOY)).thenReturn(List.of(serverRelease,appRelease));
    when(tasks.findByServiceIdAndTaskKindOrderByCreatedAtDesc(2L,TaskKind.BUILD)).thenReturn(List.of(appBuild));
    when(artifacts.findAll()).thenReturn(List.of(serverArtifact,appArtifact));for(Artifact a:List.of(serverArtifact,appArtifact))when(artifacts.findById(a.getId())).thenReturn(Optional.of(a));when(metadata.existsByArtifactId(200L)).thenReturn(true);
    for(String name:List.of("apps","ordinary","admin")){
      AdminUser user=new AdminUser(name,"unused");user.setMenuPermissions(name.equals("apps")?List.of("apps"):List.of("dashboard","services","servers","builds","release","releases"));
      when(tokens.validate(name)).thenReturn(new TokenService.SessionInfo(name,false));when(users.findByUsername(name)).thenReturn(Optional.of(user));
    }
    AppApiScope scope=new AppApiScope(services,tasks,artifacts,metadata);
    ObjectProvider<MobileReleaseService> deliveryAccess=mock(ObjectProvider.class);when(deliveryAccess.getObject()).thenReturn(mobile);
    mvc=MockMvcBuilders.standaloneSetup(new ServiceController(services,git),new BuildController(tasks,steps,services,executor),new ArtifactController(artifacts,metadata),new ReleaseController(tasks,steps,executor,totp,services),new MobileReleaseController(mobile),new BranchPolicyController(branches,merges,users,tasks),new MobileSigningController(signingCatalog))
      .setControllerAdvice(new ApiExceptionHandler(),new AppRequestAuthorization(scope),new AppResponseIsolation(scope,services,tasks,artifacts))
      .addInterceptors(scope).addFilters(new BearerTokenFilter(tokens,users,deliveryAccess)).build();
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  @Test void approvalUsesTheSessionAccountAndMenuPermissionsStillApply()throws Exception{
    var now=java.time.Instant.now();
    for(String user:List.of("apps","ordinary"))when(totp.availability(user)).thenReturn(new TotpService.Availability(true,now,now,0));
    request(get("/api/apps/releases/production-approval?approver=admin"),"apps").andExpect(status().isOk()).andExpect(jsonPath("$.approver").value("apps"));
    request(get("/api/releases/production-approval?approver=admin"),"ordinary").andExpect(status().isOk()).andExpect(jsonPath("$.approver").value("ordinary"));
    request(get("/api/apps/releases/production-approval"),"ordinary").andExpect(status().isForbidden());
    request(get("/api/releases/production-approval"),"apps").andExpect(status().isForbidden());
    request(post("/api/releases").contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":1,\"artifactId\":100,\"environment\":\"prod\",\"requestId\":\"denied\",\"totpCode\":\"123456\"}"),"apps").andExpect(status().isForbidden());
    verify(totp).availability("apps");verify(totp).availability("ordinary");verifyNoMoreInteractions(totp);verifyNoInteractions(executor);
  }
  @Test void separateListsFilterEvenForAdminAndLegacyUsers()throws Exception{
    request(get("/api/apps/services"),"apps").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(2));
    request(get("/api/services"),"ordinary").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(1));
    request(get("/api/services"),"admin").andExpect(jsonPath("$.length()").value(1));
    request(get("/api/apps/builds"),"apps").andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(20)).andExpect(jsonPath("$.length()").value(1));
    request(get("/api/builds"),"ordinary").andExpect(jsonPath("$[0].id").value(10)).andExpect(jsonPath("$.length()").value(1));
    request(get("/api/apps/artifacts"),"apps").andExpect(jsonPath("$[0].id").value(200)).andExpect(jsonPath("$.length()").value(1));
    request(get("/api/artifacts"),"ordinary").andExpect(jsonPath("$[0].id").value(100)).andExpect(jsonPath("$.length()").value(1));
    request(get("/api/apps/releases"),"apps").andExpect(jsonPath("$[0].id").value(21)).andExpect(jsonPath("$.length()").value(1));
  }
  @Test void legacyPermissionsCannotReachAnyAppDetailsThroughGenericAliases()throws Exception{
    for(String path:List.of("/api/services/2","/api/services/+2","/api/services/2/branches","/api/services/2/branches/latest-commit?branch=master","/api/builds/20","/api/builds/+20","/api/tasks/20/branch-checks","/api/releases/21","/api/releases/21/steps","/api/releases/21/merge","/api/mobile/artifacts/200","/api/mobile/releases/21"))request(get(path),"ordinary").andExpect(status().isForbidden());
    for(String path:List.of("/api/services/2","/api/artifacts/200"))request(delete(path),"ordinary").andExpect(status().isForbidden());
    request(post("/api/builds/20/retry").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"r\"}"),"ordinary").andExpect(status().isForbidden());
    request(post("/api/releases/21/cancel"),"ordinary").andExpect(status().isForbidden());
    verifyNoInteractions(git,branches,merges,mobile,executor);
  }
  @Test void appsAliasNeverAcceptsServerIdsIncludingPlusAndQueryIds()throws Exception{
    for(String path:List.of("/api/apps/services/1","/api/apps/services/+1","/api/apps/services/1/branches","/api/apps/builds/10","/api/apps/builds/+10","/api/apps/tasks/10/branch-checks","/api/apps/releases/11","/api/apps/releases/11/steps","/api/apps/releases/11/merge","/api/apps/mobile/artifacts/100","/api/apps/mobile/releases/11","/api/apps/artifacts?serviceId=1","/api/apps/builds?serviceId=1","/api/apps/releases?serviceId=1"))request(get(path),"apps").andExpect(status().isForbidden());
    request(post("/api/apps/builds/10/retry").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"r\"}"),"apps").andExpect(status().isForbidden());
    verifyNoInteractions(git,branches,merges,mobile,executor);
  }
  @Test void requestBodyCannotSwitchServiceFamiliesOrSmuggleForeignArtifacts()throws Exception{
    String appJson="{\"name\":\"App\",\"serviceKey\":\"app\",\"serviceType\":\"FLUTTER_ANDROID\",\"enabled\":false}";
    request(post("/api/services").contentType(MediaType.APPLICATION_JSON).content(appJson),"ordinary").andExpect(status().isForbidden());
    request(put("/api/services/1").contentType(MediaType.APPLICATION_JSON).content(appJson),"ordinary").andExpect(status().isForbidden());
    request(post("/api/apps/services").contentType(MediaType.APPLICATION_JSON).content(appJson.replace("FLUTTER_ANDROID","BACKEND")),"apps").andExpect(status().isForbidden());
    request(post("/api/apps/builds").contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":1,\"commitId\":\"abc\",\"requestId\":\"r\"}"),"apps").andExpect(status().isForbidden());
    request(post("/api/builds").contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":2,\"commitId\":\"abc\",\"requestId\":\"r\"}"),"ordinary").andExpect(status().isForbidden());
    request(post("/api/apps/mobile/releases").contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":2,\"artifactId\":100,\"environmentName\":\"test\",\"requestId\":\"r\"}"),"apps").andExpect(status().isForbidden());
    request(post("/api/releases").contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":1,\"artifactId\":200,\"environment\":\"test\",\"requestId\":\"r\"}"),"ordinary").andExpect(status().isForbidden());
    request(post("/api/apps/releases").contentType(MediaType.APPLICATION_JSON).content("{}"),"apps").andExpect(status().isForbidden());
    verifyNoInteractions(executor,mobile);verify(services,never()).save(any());
  }
  @Test void appOperatorCanBuildReadMetadataAndCreateDeliveryWithoutServerGrant()throws Exception{
    when(executor.createBuild(eq(2L),any(),any(),eq(false),eq("apps"),eq("abc"),eq("r"),eq("test"))).thenReturn(appBuild);
    request(post("/api/apps/builds").contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":2,\"commitId\":\"abc\",\"requestId\":\"r\",\"environmentName\":\"test\"}"),"apps").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(20));
    request(get("/api/apps/builds/20"),"apps").andExpect(status().isOk()).andExpect(jsonPath("$.task.id").value(20));
    request(get("/api/apps/tasks/20/branch-checks"),"apps").andExpect(status().isOk());verify(branches).list(20L);
    request(get("/api/apps/mobile/artifacts/200"),"apps").andExpect(status().isOk());verify(mobile).metadata(200L);
    request(post("/api/apps/mobile/releases").contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":2,\"artifactId\":200,\"environmentName\":\"test\",\"requestId\":\"deliver\"}"),"apps").andExpect(status().isOk());verify(mobile).create(any(),eq("apps"));
    request(get("/api/apps/releases/21/merge"),"apps").andExpect(status().isOk());verify(merges).get(21L);
    request(post("/api/apps/releases/21/merge/retry"),"apps").andExpect(status().isOk());verify(merges).retry(21L);
  }
  @Test void globalQueueShowsBusyButHidesOtherTaskIdentifiers()throws Exception{
    when(executor.queue()).thenReturn(new ReleaseExecutor.QueueSnapshot(10L,TaskKind.BUILD,1L,2,List.of(new ReleaseExecutor.QueuedTask(20L,TaskKind.BUILD,2L,1),new ReleaseExecutor.QueuedTask(10L,TaskKind.BUILD,1L,2)),1,null,0));
    request(get("/api/apps/builds/queue"),"apps").andExpect(status().isOk()).andExpect(jsonPath("$.runningTaskId").isEmpty()).andExpect(jsonPath("$.runningServiceId").isEmpty()).andExpect(jsonPath("$.runningRuntimeOperation").value("其他任务执行中")).andExpect(jsonPath("$.pendingCount").value(2)).andExpect(jsonPath("$.pendingTasks.length()").value(1)).andExpect(jsonPath("$.pendingTasks[0].taskId").value(20));
  }
  @Test void parallelQueueFiltersCloudTasksByAppScope()throws Exception{
    when(executor.queue()).thenReturn(new ReleaseExecutor.QueueSnapshot(null,null,null,0,List.of(),100,null,0,List.of(new ReleaseExecutor.QueuedTask(10L,TaskKind.BUILD,1L,0),new ReleaseExecutor.QueuedTask(20L,TaskKind.BUILD,2L,0)),3));
    request(get("/api/apps/builds/queue"),"apps").andExpect(status().isOk()).andExpect(jsonPath("$.cloudConcurrency").value(3)).andExpect(jsonPath("$.cloudRunningTasks.length()").value(1)).andExpect(jsonPath("$.cloudRunningTasks[0].taskId").value(20)).andExpect(jsonPath("$.runningRuntimeOperation").value("其他任务执行中"));
  }
  @Test void incompleteDisabledAppDraftSavesButCannotBeEnabledAndInvalidPathsStayBlocked()throws Exception{
    String draft="{\"name\":\"New App\",\"serviceKey\":\"new-app\",\"serviceType\":\"FLUTTER_ANDROID\",\"enabled\":false,\"buildCommand\":\"touch /tmp/should-not-run\",\"configJson\":\"{\\\"mobileBuild\\\":{\\\"outputFormat\\\":\\\"APK\\\"}}\"}";
    request(post("/api/apps/services").contentType(MediaType.APPLICATION_JSON).content(draft),"apps").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(50)).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.buildCommand").value("flutter build apk --release")).andExpect(jsonPath("$.jarPath").value("build/app/outputs/flutter-apk/app-release.apk"));
    request(post("/api/apps/services").contentType(MediaType.APPLICATION_JSON).content(draft.replace("\"enabled\":false","\"enabled\":true")),"apps").andExpect(status().isBadRequest());
    request(post("/api/apps/services").contentType(MediaType.APPLICATION_JSON).content(draft.replace("\\\"outputFormat\\\":\\\"APK\\\"","\\\"outputFormat\\\":\\\"APK\\\",\\\"apkPath\\\":\\\"../unsafe.apk\\\"")),"apps").andExpect(status().isBadRequest());
    verify(services,times(1)).save(any());
  }
  @Test void encodedAliasesCannotBypassPermissionOrScopeMatching()throws Exception{
    request(get(URI.create("/api/%61pps/services")),"ordinary").andExpect(status().isForbidden());
    request(get(URI.create("/api/services/%2B2")),"ordinary").andExpect(status().isForbidden());
  }
  @Test void signingCatalogIsReadOnlyAndRequiresAppMenuPermission()throws Exception{
    when(signingCatalog.list()).thenReturn(new MobileSigningCatalog.Catalog(true,List.of(new MobileSigningCatalog.Profile("app-pre","a".repeat(64),true,true,"已登记的测试证书"))));
    request(get("/api/apps/signing-profiles"),"ordinary").andExpect(status().isForbidden());verifyNoInteractions(signingCatalog);
    request(get("/api/apps/signing-profiles"),"apps").andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true))
      .andExpect(jsonPath("$.profiles[0].name").value("app-pre")).andExpect(jsonPath("$.profiles[0].certificateSha256").value("a".repeat(64)))
      .andExpect(jsonPath("$.profiles[0].storePassword").doesNotExist()).andExpect(jsonPath("$.profiles[0].storeFile").doesNotExist());
    request(post("/api/apps/signing-profiles"),"apps").andExpect(status().isMethodNotAllowed());
    request(put("/api/apps/signing-profiles"),"apps").andExpect(status().isMethodNotAllowed());
    request(delete("/api/apps/signing-profiles"),"apps").andExpect(status().isMethodNotAllowed());
    verify(signingCatalog,times(1)).list();
  }
  @Test void externalDeliveryTokensCannotListSigningProfilesEvenWithValidTokenShape()throws Exception{
    // Run the real endpoint/scope gate; this unrelated route is denied before accessing token storage.
    when(mobile.validateAccess(anyString(),anyString(),anyString())).thenCallRealMethod();
    for(String scope:List.of("fetch","confirm"))request(get("/api/apps/signing-profiles"),"mrel_21_"+scope+"_"+"a".repeat(43))
      .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("DELIVERY_ACCESS_DENIED"));
    verifyNoInteractions(signingCatalog);
  }
  private ResultActions request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,String user)throws Exception{return mvc.perform(builder.header("Authorization","Bearer "+user).principal(()->user));}
  private AppService service(Long id,ServiceType type){AppService s=new AppService();ReflectionTestUtils.setField(s,"id",id);s.setName("service-"+id);s.setServiceKey("service-"+id);s.setServiceType(type);return s;}
  private ReleaseTask task(Long id,Long serviceId,TaskKind kind,boolean mobile){ReleaseTask t=new ReleaseTask(serviceId,kind,null,"test","master",null,false);ReflectionTestUtils.setField(t,"id",id);t.setDeliveryType(mobile?"MOBILE":"SERVER");return t;}
  private Artifact artifact(Long id,Long serviceId,Long build){Artifact a=new Artifact(serviceId,build,"v1","master","a".repeat(40),null);ReflectionTestUtils.setField(a,"id",id);return a;}
}
