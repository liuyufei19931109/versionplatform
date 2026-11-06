package com.local.release.service;

import com.fasterxml.jackson.databind.*;
import com.local.release.domain.*;
import com.local.release.repo.FrontendCloudBuildRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GitHubFrontendBuildServiceTest {
  static final ObjectMapper JSON=new ObjectMapper();static final String SHA="a".repeat(40);
  @TempDir Path directory;
  FrontendCloudSettings settings;GitHubActionsClient api;FrontendCloudBuildRepository journal;GitHubFrontendBuildService cloud;
  ReleaseTask task;AppService app;FrontendBuildConfiguration config;AtomicReference<FrontendCloudBuild> row;
  @BeforeEach void setup(){
    settings=new FrontendCloudSettings();settings.buildEngine="github";settings.repository="example-org/build-workflows";settings.serviceKeys="example-ui";settings.pollSeconds=0;
    api=mock(GitHubActionsClient.class);journal=mock(FrontendCloudBuildRepository.class);row=new AtomicReference<>();
    when(journal.findById(anyLong())).thenAnswer(a->Optional.ofNullable(row.get()));
    when(journal.save(any())).thenAnswer(a->{row.set(a.getArgument(0));return row.get();});when(journal.saveAndFlush(any())).thenAnswer(a->{row.set(a.getArgument(0));return row.get();});
    when(journal.findTop20ByStateOrderByDispatchedAtAsc(anyString())).thenAnswer(a->row.get().getState().equals(a.getArgument(0))?List.of(row.get()):List.of());
    cloud=new GitHubFrontendBuildService(settings,api,journal);
    app=new AppService();app.setServiceType(ServiceType.FRONTEND);app.setServiceKey("example-ui");app.setGitRepo("git@github.com:example-org/example-ui.git");app.setJarPath("dist");
    task=new ReleaseTask(5L,TaskKind.BUILD,null,"build","release20261105",null,false);ReflectionTestUtils.setField(task,"id",734L);task.setCommitId(SHA);task.setFrontendBuildEngine("GITHUB");
    app.setBuildCommand("cmd.exe /d /s /c \"npm ci && npm run build\"");
    config=FrontendBuildConfiguration.from(app,settings);cloud.prepare(task,app);
  }
  JsonNode run(String status,String conclusion){return JSON.valueToTree(Map.of("id",42,"name","Release frontend build","display_title","Release-Frontend-"+row.get().getRequestId(),"event","workflow_dispatch","repository",Map.of("full_name",settings.repository),"path",".github/workflows/"+settings.workflow,"status",status,"conclusion",conclusion,"run_attempt",1));}
  GitHubActionsClient.Response response(JsonNode body){return new GitHubActionsClient.Response(200,body);}
  @Test void scopeAndEngineAreExplicitAndOldTasksRemainLocal(){
    assertThat(cloud.engineForNewTask(app)).isEqualTo("GITHUB");settings.buildEngine="local";assertThat(cloud.engineForNewTask(app)).isEqualTo("LOCAL");assertThat(GitHubFrontendBuildService.usesGitHub(task)).isTrue();
    assertThat(new ReleaseTask(5L,TaskKind.BUILD,null,"build","master",null,false).getFrontendBuildEngine()).isEqualTo("LOCAL");
    settings.buildEngine="github";app.setServiceKey("other-ui");assertThatThrownBy(()->cloud.engineForNewTask(app)).hasMessageContaining("RELEASE_FRONTEND_SERVICE_KEYS");app.setServiceType(ServiceType.BACKEND);assertThat(cloud.engineForNewTask(app)).isEqualTo("LOCAL");
  }
  @Test void lostDispatchDiscoversOneRunAndExplicitRetryChangesIdentity()throws Exception{
    when(api.request(eq("POST"),endsWith("/dispatches"),any())).thenThrow(new IOException("connection lost"));
    when(api.request(eq("GET"),contains("/runs?"),isNull())).thenAnswer(a->response(JSON.createObjectNode().set("workflow_runs",JSON.createArrayNode().add(run("queued","")))));
    when(api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenAnswer(a->response(run("completed","failure")));
    assertThatThrownBy(()->cloud.build(task,directory,null)).hasMessageContaining("未成功");String original=row.get().getRequestId();
    assertThatThrownBy(()->cloud.build(task,directory,null)).hasMessageContaining("未成功");verify(api,times(1)).request(eq("POST"),endsWith("/dispatches"),any());
    cloud.prepareRetry(task);assertThat(row.get().getRequestId()).isNotEqualTo(original);assertThat(row.get().getRunId()).isNull();
  }
  @Test void serviceSelectionAndEmergencySwitchCannotChangeExistingCloudConfiguration()throws Exception{
    String snapshot=row.get().getBuildConfigSnapshot();app.setBuildEngine("LOCAL");settings.buildEngine="local";settings.serviceKeys="another-service";
    cloud.validateTask(task,app);row.get().completed("failure");cloud.prepareRetry(task);
    assertThat(task.getFrontendBuildEngine()).isEqualTo("GITHUB");assertThat(row.get().getBuildConfigSnapshot()).isEqualTo(snapshot);assertThat(row.get().getState()).isEqualTo("READY");
    verify(api,never()).request(anyString(),anyString(),any());
  }
  @Test void switchedOffRestartReusesSuccessForArtifactDownload()throws Exception{
    row.get().dispatching(Instant.now().minusSeconds(4000),1800);row.get().attach(42);row.get().completed("success");settings.buildEngine="local";
    when(api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenAnswer(a->response(run("completed","success")));
    when(api.request(eq("GET"),contains("/artifacts?"),isNull())).thenReturn(response(JSON.createObjectNode().set("artifacts",JSON.createArrayNode())));
    var restarted=new GitHubFrontendBuildService(settings,api,journal);
    assertThatThrownBy(()->restarted.build(task,directory,null)).hasMessageContaining("产物不存在");restarted.prepareRetry(task);
    verify(api,never()).request(eq("POST"),anyString(),any());assertThat(row.get().getRunId()).isEqualTo(42);
  }
  @Test void timeoutPersistsCancellationAndShutdownRetainsRun()throws Exception{
    row.get().dispatching(Instant.now().minusSeconds(4000),1800);row.get().attach(42);
    assertThatThrownBy(()->cloud.build(task,directory,null)).hasMessageContaining("不会切回本机");assertThat(row.get().getState()).isEqualTo("CANCEL_REQUESTED");
    when(api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenAnswer(a->response(run("in_progress","")));
    cloud.reconcileCancellations();verify(api).request(eq("POST"),endsWith("/42/cancel"),isNull());
    row.get().cancelled();cloud.prepareRetry(task);row.get().dispatching(Instant.now(),1800);row.get().attach(43);cloud.detach();
    assertThatThrownBy(()->cloud.build(task,directory,null)).isInstanceOf(GitHubFrontendBuildService.DetachedException.class);assertThat(row.get().getState()).isEqualTo("TRACKING");
  }
  @Test void wrongWorkflowIdentityRerunAndChangedConfigurationAreRejected()throws Exception{
    row.get().attach(42);var run=(com.fasterxml.jackson.databind.node.ObjectNode)run("completed","success");run.put("path",".github/workflows/release-mobile-build.yml");
    assertThatThrownBy(()->GitHubFrontendBuildService.verifyRun(row.get(),run)).hasMessageContaining("身份");run.put("path",".github/workflows/"+settings.workflow).put("run_attempt",2);
    assertThatThrownBy(()->GitHubFrontendBuildService.verifyRun(row.get(),run)).hasMessageContaining("运行尝试");
    app.setJarPath("different");assertThatThrownBy(()->cloud.validateTask(task,app)).hasMessageContaining("配置已改变");
  }
  @Test void placeholderTitleWaitsButDoesNotAcceptCompletedPlaceholder()throws Exception{
    row.get().dispatching(Instant.now(),1800);row.get().attach(42);var run=(com.fasterxml.jackson.databind.node.ObjectNode)run("in_progress","");run.put("display_title","Release frontend build");
    assertThatThrownBy(()->GitHubFrontendBuildService.verifyRun(row.get(),run)).isInstanceOf(GitHubFrontendBuildService.RunMetadataPendingException.class);
    run.put("status","completed");assertThatThrownBy(()->GitHubFrontendBuildService.verifyRun(row.get(),run)).hasMessageContaining("身份");
  }
  @Test void cleanVerifiedArtifactReplacesOldOutputButWrongIdentityKeepsOldOutput()throws Exception{
    row.get().attach(42);Files.createDirectories(directory.resolve("dist"));Files.writeString(directory.resolve("dist/old.js"),"old");
    Path zip=artifact("index.html",false);FrontendArtifactExtractor.extract(zip,directory,row.get(),task,config);
    assertThat(Files.readString(directory.resolve("dist/index.html"))).isEqualTo("hello");assertThat(directory.resolve("dist/old.js")).doesNotExist();
    Path bad=artifact("index.html",true);assertThatThrownBy(()->FrontendArtifactExtractor.extract(bad,directory,row.get(),task,config)).hasMessageContaining("身份");assertThat(directory.resolve("dist/index.html")).exists();
  }
  @Test void traversalAndDuplicateStaticPathsAreRejectedBeforeInstalling()throws Exception{
    row.get().attach(42);Path bad=artifact("../outside.txt",false);
    assertThatThrownBy(()->FrontendArtifactExtractor.extract(bad,directory,row.get(),task,config)).hasMessageContaining("路径");assertThat(directory.resolve("outside.txt")).doesNotExist();
    Path zip=directory.resolve("collision.zip");try(var out=new ZipOutputStream(Files.newOutputStream(zip))){for(String name:List.of("index.html","A.js","a.js")){out.putNextEntry(new ZipEntry(name));out.write(1);out.closeEntry();}}
    Path stage=Files.createDirectory(directory.resolve("stage"));assertThatThrownBy(()->FrontendArtifactExtractor.unpack(zip,stage)).hasMessageContaining("重复路径");
  }
  @Test void configRejectsDockerfileUnsupportedShellAndUnsafeOutput(){
    app.setBuildCommand("npm ci && npm run build; echo secret");assertThatThrownBy(()->FrontendBuildConfiguration.from(app,settings)).hasMessageContaining("构建命令");
    app.setBuildCommand(null);app.setJarPath("../outside");assertThatThrownBy(()->FrontendBuildConfiguration.from(app,settings)).hasMessageContaining("相对路径");
    app.setJarPath("dist");app.setConfigJson("{\"imageBuildMode\":\"REPOSITORY_DOCKERFILE\"}");assertThatThrownBy(()->FrontendBuildConfiguration.from(app,settings)).hasMessageContaining("Dockerfile");
  }
  Path artifact(String name,boolean wrong)throws Exception{
    Path site=directory.resolve(UUID.randomUUID()+".zip");try(var out=new ZipOutputStream(Files.newOutputStream(site))){out.putNextEntry(new ZipEntry(name));out.write("hello".getBytes());out.closeEntry();}
    var manifest=JSON.createObjectNode();manifest.put("schema",1);manifest.put("request_id",row.get().getRequestId());manifest.put("task_id",wrong?735:734);manifest.put("service_id",5);manifest.put("commit_id",SHA);manifest.put("source_repository",config.sourceRepository());manifest.put("config_sha256",config.hash());manifest.put("run_id",42);manifest.put("run_attempt",1);manifest.put("size_bytes",Files.size(site));manifest.put("sha256",FlutterBuildService.sha256(site));
    Path zip=directory.resolve(UUID.randomUUID()+".zip");try(var out=new ZipOutputStream(Files.newOutputStream(zip))){out.putNextEntry(new ZipEntry("manifest.json"));out.write(JSON.writeValueAsBytes(manifest));out.closeEntry();out.putNextEntry(new ZipEntry("site.zip"));Files.copy(site,out);out.closeEntry();}return zip;
  }
}
