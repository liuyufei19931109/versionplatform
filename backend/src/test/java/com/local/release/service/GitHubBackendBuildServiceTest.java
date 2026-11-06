package com.local.release.service;

import com.fasterxml.jackson.databind.*;
import com.local.release.domain.*;
import com.local.release.repo.BackendCloudBuildRepository;
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

class GitHubBackendBuildServiceTest {
  static final ObjectMapper JSON=new ObjectMapper();static final String SHA="a".repeat(40);
  @TempDir Path directory;
  BackendCloudSettings settings;GitHubActionsClient api;BackendCloudBuildRepository journal;GitHubBackendBuildService cloud;
  ReleaseTask task;AppService app;BackendBuildConfiguration config;AtomicReference<BackendCloudBuild> row;
  @BeforeEach void setup(){
    settings=new BackendCloudSettings();settings.buildEngine="github";settings.repository="example-org/build-workflows";settings.serviceKeys="example-catalog";settings.pollSeconds=0;
    api=mock(GitHubActionsClient.class);journal=mock(BackendCloudBuildRepository.class);row=new AtomicReference<>();
    when(journal.findById(anyLong())).thenAnswer(a->Optional.ofNullable(row.get()));
    when(journal.save(any())).thenAnswer(a->{row.set(a.getArgument(0));return row.get();});when(journal.saveAndFlush(any())).thenAnswer(a->{row.set(a.getArgument(0));return row.get();});
    when(journal.findTop20ByStateOrderByDispatchedAtAsc(anyString())).thenAnswer(a->row.get().getState().equals(a.getArgument(0))?List.of(row.get()):List.of());
    cloud=new GitHubBackendBuildService(settings,api,journal);
    app=new AppService();app.setServiceType(ServiceType.BACKEND);app.setServiceKey("example-catalog");app.setGitRepo("git@github.com:example-org/example-catalog.git");app.setJarPath("target/*.jar");
    task=new ReleaseTask(5L,TaskKind.BUILD,null,"build","release20261105",null,false);ReflectionTestUtils.setField(task,"id",734L);task.setCommitId(SHA);task.setBackendBuildEngine("GITHUB");
    app.setBuildCommand("mvn.cmd clean package -DskipTests");
    config=BackendBuildConfiguration.from(app,settings);cloud.prepare(task,app);
  }
  JsonNode run(String status,String conclusion){return JSON.valueToTree(Map.of("id",42,"name","Release backend build","display_title","Release-Backend-"+row.get().getRequestId(),"event","workflow_dispatch","repository",Map.of("full_name",settings.repository),"path",".github/workflows/"+settings.workflow,"status",status,"conclusion",conclusion,"run_attempt",1));}
  GitHubActionsClient.Response response(JsonNode body){return new GitHubActionsClient.Response(200,body);}
  @Test void scopeAndEngineAreExplicitAndOldTasksRemainLocal(){
    assertThat(cloud.engineForNewTask(app)).isEqualTo("GITHUB");settings.buildEngine="local";assertThat(cloud.engineForNewTask(app)).isEqualTo("LOCAL");assertThat(GitHubBackendBuildService.usesGitHub(task)).isTrue();
    assertThat(new ReleaseTask(5L,TaskKind.BUILD,null,"build","master",null,false).getBackendBuildEngine()).isEqualTo("LOCAL");
    settings.buildEngine="github";app.setServiceKey("other-ui");assertThatThrownBy(()->cloud.engineForNewTask(app)).hasMessageContaining("RELEASE_BACKEND_SERVICE_KEYS");app.setServiceType(ServiceType.FRONTEND);assertThat(cloud.engineForNewTask(app)).isEqualTo("LOCAL");
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
    assertThat(task.getBackendBuildEngine()).isEqualTo("GITHUB");assertThat(row.get().getBuildConfigSnapshot()).isEqualTo(snapshot);assertThat(row.get().getState()).isEqualTo("READY");
    verify(api,never()).request(anyString(),anyString(),any());
  }
  @Test void switchedOffRestartReusesSuccessForArtifactDownload()throws Exception{
    row.get().dispatching(Instant.now().minusSeconds(4000),1800);row.get().attach(42);row.get().completed("success");settings.buildEngine="local";
    when(api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenAnswer(a->response(run("completed","success")));
    when(api.request(eq("GET"),contains("/artifacts?"),isNull())).thenReturn(response(JSON.createObjectNode().set("artifacts",JSON.createArrayNode())));
    var restarted=new GitHubBackendBuildService(settings,api,journal);
    assertThatThrownBy(()->restarted.build(task,directory,null)).hasMessageContaining("产物不存在");restarted.prepareRetry(task);
    verify(api,never()).request(eq("POST"),anyString(),any());assertThat(row.get().getRunId()).isEqualTo(42);
  }
  @Test void timeoutPersistsCancellationAndShutdownRetainsRun()throws Exception{
    row.get().dispatching(Instant.now().minusSeconds(4000),1800);row.get().attach(42);
    assertThatThrownBy(()->cloud.build(task,directory,null)).hasMessageContaining("不会切回本机");assertThat(row.get().getState()).isEqualTo("CANCEL_REQUESTED");
    when(api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenAnswer(a->response(run("in_progress","")));
    cloud.reconcileCancellations();verify(api).request(eq("POST"),endsWith("/42/cancel"),isNull());
    row.get().cancelled();cloud.prepareRetry(task);row.get().dispatching(Instant.now(),1800);row.get().attach(43);cloud.detach();
    assertThatThrownBy(()->cloud.build(task,directory,null)).isInstanceOf(GitHubBackendBuildService.DetachedException.class);assertThat(row.get().getState()).isEqualTo("TRACKING");
  }
  @Test void wrongWorkflowIdentityRerunAndChangedConfigurationAreRejected()throws Exception{
    row.get().attach(42);var run=(com.fasterxml.jackson.databind.node.ObjectNode)run("completed","success");run.put("path",".github/workflows/release-mobile-build.yml");
    assertThatThrownBy(()->GitHubBackendBuildService.verifyRun(row.get(),run)).hasMessageContaining("身份");run.put("path",".github/workflows/"+settings.workflow).put("run_attempt",2);
    assertThatThrownBy(()->GitHubBackendBuildService.verifyRun(row.get(),run)).hasMessageContaining("运行尝试");
    app.setJarPath("different");assertThatThrownBy(()->cloud.validateTask(task,app)).hasMessageContaining("配置已改变");
  }
  @Test void placeholderTitleWaitsButDoesNotAcceptCompletedPlaceholder()throws Exception{
    row.get().dispatching(Instant.now(),1800);row.get().attach(42);var run=(com.fasterxml.jackson.databind.node.ObjectNode)run("in_progress","");run.put("display_title","Release backend build");
    assertThatThrownBy(()->GitHubBackendBuildService.verifyRun(row.get(),run)).isInstanceOf(GitHubBackendBuildService.RunMetadataPendingException.class);
    run.put("status","completed");assertThatThrownBy(()->GitHubBackendBuildService.verifyRun(row.get(),run)).hasMessageContaining("身份");
  }
  @Test void verifiedModuleJarReplacesOldOutputAndWrongIdentityPreservesIt()throws Exception{
    row.get().attach(42);Path target=directory.resolve("server/target");Files.createDirectories(target);Files.writeString(target.resolve("obsolete.jar"),"old");
    Path zip=artifact("server/target/service.jar",false);BackendArtifactExtractor.extract(zip,directory,row.get(),task,config);
    assertThat(target.resolve("service.jar")).exists();assertThat(target.resolve("obsolete.jar")).doesNotExist();
    Path bad=artifact("server/target/service.jar",true);assertThatThrownBy(()->BackendArtifactExtractor.extract(bad,directory,row.get(),task,config)).hasMessageContaining("身份");assertThat(target.resolve("service.jar")).exists();
  }
  @Test void jarTraversalAndHashMismatchCannotReplaceOutput()throws Exception{
    row.get().attach(42);Path bad=artifact("../target/service.jar",false);assertThatThrownBy(()->BackendArtifactExtractor.extract(bad,directory,row.get(),task,config)).hasMessageContaining("路径");
    assertThat(directory.resolve("target")).doesNotExist();
  }
  @Test void configRejectsShellCommandsAndKeepsWindowsMavenCompatibility(){
    assertThat(config.command()).isEqualTo("mvn clean package -DskipTests");app.setBuildCommand("mvn clean package; echo token");assertThatThrownBy(()->BackendBuildConfiguration.from(app,settings)).hasMessageContaining("命令");
    app.setBuildCommand("mvn clean package -DskipTests");app.setWorkDirectory("../outside");assertThatThrownBy(()->BackendBuildConfiguration.from(app,settings)).hasMessageContaining("路径");
  }
  Path artifact(String name,boolean wrong)throws Exception{
    Path jar=directory.resolve(UUID.randomUUID()+".jar");try(var out=new ZipOutputStream(Files.newOutputStream(jar))){out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));out.write("Manifest-Version: 1.0\n".getBytes());out.closeEntry();}
    var manifest=JSON.createObjectNode();manifest.put("schema",1);manifest.put("request_id",row.get().getRequestId());manifest.put("task_id",wrong?735:734);manifest.put("service_id",5);manifest.put("commit_id",SHA);manifest.put("source_repository",config.sourceRepository());manifest.put("config_sha256",config.hash());manifest.put("run_id",42);manifest.put("run_attempt",1);
    manifest.putArray("files").addObject().put("path",name).put("size_bytes",Files.size(jar)).put("sha256",FlutterBuildService.sha256(jar));
    Path zip=directory.resolve(UUID.randomUUID()+".zip");try(var out=new ZipOutputStream(Files.newOutputStream(zip))){out.putNextEntry(new ZipEntry("manifest.json"));out.write(JSON.writeValueAsBytes(manifest));out.closeEntry();out.putNextEntry(new ZipEntry(name));Files.copy(jar,out);out.closeEntry();}return zip;
  }
}
