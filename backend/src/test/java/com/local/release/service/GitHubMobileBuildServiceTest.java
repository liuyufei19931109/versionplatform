package com.local.release.service;

import com.fasterxml.jackson.databind.*;
import com.local.release.domain.*;
import com.local.release.repo.MobileCloudBuildRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

class GitHubMobileBuildServiceTest {
  static final ObjectMapper JSON=new ObjectMapper();
  static final String SHA="a".repeat(40);
  @TempDir Path directory;
  MobileCloudSettings settings;GitHubActionsClient api;MobileCloudBuildRepository journal;GitHubMobileBuildService service;
  ReleaseTask task;FlutterBuildConfiguration config;AtomicReference<MobileCloudBuild> row;
  @BeforeEach void setup()throws Exception{
    settings=new MobileCloudSettings();settings.buildEngine="github";settings.repository="example-org/build-workflows";settings.pollSeconds=0;
    api=mock(GitHubActionsClient.class);journal=mock(MobileCloudBuildRepository.class);row=new AtomicReference<>();
    when(journal.findById(anyLong())).thenAnswer(a->Optional.ofNullable(row.get()));
    when(journal.save(any())).thenAnswer(a->{row.set(a.getArgument(0));return row.get();});
    when(journal.saveAndFlush(any())).thenAnswer(a->{row.set(a.getArgument(0));return row.get();});
    when(journal.findTop20ByStateOrderByDispatchedAtAsc(anyString())).thenAnswer(a->row.get()!=null&&row.get().getState().equals(a.getArgument(0))?List.of(row.get()):List.of());
    service=new GitHubMobileBuildService(settings,api,journal);
    AppService app=new AppService();app.setGitRepo("https://github.com/example-org/example-app");app.setWorkDirectory("");
    app.setConfigJson("{\"mobileBuild\":{\"flutterVersion\":\"3.47.7\",\"outputFormat\":\"APK\",\"environments\":{\"test\":{\"enabled\":true,\"applicationId\":\"com.example.mobile.pre\",\"expectedCertificateSha256\":\""+"b".repeat(64)+"\",\"signingProfile\":\"example-alt-pre\",\"versionName\":\"0.1.0-pre\",\"versionCode\":22,\"buildMode\":\"DEBUG\",\"allowDebugSigning\":true}}}}");
    task=new ReleaseTask(41L,TaskKind.BUILD,null,"test","feature/app",null,false);ReflectionTestUtils.setField(task,"id",1L);task.setDeliveryType("MOBILE");task.setMobileBuildEngine("GITHUB");task.setCommitId(SHA);task.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.snapshot(app,"test"));
    config=FlutterBuildConfiguration.fromTask(task);service.prepare(task,app);
  }
  JsonNode run(String status,String conclusion)throws Exception{
    return JSON.readTree(JSON.writeValueAsString(Map.of("id",42,"name","Release App build","display_title","Release-"+row.get().getRequestId(),"event","workflow_dispatch","repository",Map.of("full_name",settings.repository),"path",".github/workflows/"+settings.workflow,"status",status,"conclusion",conclusion)));
  }
  GitHubActionsClient.Response response(JsonNode data){return new GitHubActionsClient.Response(200,data);}
  @Test void dispatchOccursOnceAndExplicitRetryAllocatesAnotherExecutionIdentity()throws Exception{
    when(api.request(eq("POST"),endsWith("/dispatches"),any())).thenReturn(response(JSON.readTree("{\"workflow_run_id\":42}")));
    when(api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenAnswer(a->response(run("completed","failure")));
    assertThatThrownBy(()->service.build(task,config,directory,null)).hasMessageContaining("未成功");
    String original=row.get().getRequestId();
    assertThatThrownBy(()->service.build(task,config,directory,null)).hasMessageContaining("未成功");
    verify(api,times(1)).request(eq("POST"),endsWith("/dispatches"),any());
    service.prepareRetry(task);assertThat(row.get().getState()).isEqualTo("READY");assertThat(row.get().getRequestId()).isNotEqualTo(original);assertThat(row.get().getRunId()).isNull();
  }
  @Test void lostSubmissionResponseDiscoversOriginalRunWithoutSecondDispatch()throws Exception{
    when(api.request(eq("POST"),endsWith("/dispatches"),any())).thenThrow(new IOException("connection lost"));
    when(api.request(eq("GET"),contains("/runs?"),isNull())).thenAnswer(a->response(JSON.createObjectNode().set("workflow_runs",JSON.createArrayNode().add(run("in_progress","")))));
    when(api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenAnswer(a->response(run("completed","failure")));
    assertThatThrownBy(()->service.build(task,config,directory,null)).hasMessageContaining("未成功");
    verify(api,times(1)).request(eq("POST"),endsWith("/dispatches"),any());assertThat(row.get().getRunId()).isEqualTo(42);
  }
  @Test void restartAndGlobalSwitchReuseSuccessfulRunWhenDownloadFailed()throws Exception{
    row.get().dispatching(Instant.now().minusSeconds(5000),3600);row.get().attach(42);row.get().completed("success");settings.buildEngine="local";
    when(api.request(eq("GET"),contains("/artifacts?"),isNull())).thenReturn(response(JSON.readTree("{\"artifacts\":[]}")));
    var restarted=new GitHubMobileBuildService(settings,api,journal);
    assertThatThrownBy(()->restarted.build(task,config,directory,null)).hasMessageContaining("安装包不存在");restarted.prepareRetry(task);
    assertThatThrownBy(()->restarted.build(task,config,directory,null)).hasMessageContaining("安装包不存在");
    verify(api,never()).request(eq("POST"),anyString(),any());assertThat(row.get().getRunId()).isEqualTo(42);assertThat(task.getMobileBuildEngine()).isEqualTo("GITHUB");
  }
  @Test void timeoutRequestsDurableCancellationAndNeverFallsBackLocally()throws Exception{
    row.get().dispatching(Instant.now().minusSeconds(5000),3600);row.get().attach(42);
    assertThatThrownBy(()->service.build(task,config,directory,null)).hasMessageContaining("不会切回本机");assertThat(row.get().getState()).isEqualTo("CANCEL_REQUESTED");
    when(api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenAnswer(a->response(run("in_progress","")));
    new GitHubMobileBuildService(settings,api,journal).reconcileCancellations();verify(api).request(eq("POST"),endsWith("/42/cancel"),isNull());
    when(api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenAnswer(a->response(run("completed","cancelled")));
    service.reconcileCancellations();assertThat(row.get().getState()).isEqualTo("CANCELLED");
  }
  @Test void shutdownDetachesWithoutCancellingRemoteRun()throws Exception{
    row.get().dispatching(Instant.now(),3600);row.get().attach(42);service.detach();
    assertThatThrownBy(()->service.build(task,config,directory,null)).isInstanceOf(GitHubMobileBuildService.DetachedException.class);
    assertThat(row.get().getState()).isEqualTo("TRACKING");verify(api,never()).request(any(),any(),any());
  }
  @Test void wrongRunIdentityIsRejected()throws Exception{
    row.get().attach(42);var run=run("completed","success");((com.fasterxml.jackson.databind.node.ObjectNode)run).put("display_title","another task");
    assertThatThrownBy(()->GitHubMobileBuildService.verifyRun(row.get(),run)).hasMessageContaining("身份");
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
  void initialPlaceholderIsPolledUntilVerifiedThenDownloadsTheOriginalArtifact(boolean production)throws Exception{
    if(production){
      AppService app=new AppService();app.setGitRepo("https://github.com/example-org/example-app");
      config=new FlutterBuildConfiguration(config.flutterVersion(),config.flavor(),config.target(),"com.example.mobile","0.0.1",23L,config.expectedCertificateSha256(),"example-alt-prod",config.buildTimeoutSeconds(),config.aabPath(),"APK","build/app/outputs/flutter-apk/app-release.apk","prod","RELEASE",false,config.dartDefines(),config.environmentVariables());
      task=new ReleaseTask(41L,TaskKind.BUILD,null,"prod","feature/app",null,false);ReflectionTestUtils.setField(task,"id",1L);task.setDeliveryType("MOBILE");task.setMobileBuildEngine("GITHUB");task.setCommitId(SHA);task.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.assignedSnapshot(app,config,"0.0.1",91L));
      row.set(null);service.prepare(task,app);
    }
    var initial=(com.fasterxml.jackson.databind.node.ObjectNode)run("queued","");initial.put("display_title","Release App build");
    when(api.request(eq("POST"),endsWith("/dispatches"),any())).thenReturn(response(JSON.readTree("{\"workflow_run_id\":42}")));
    when(api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenReturn(response(initial),response(run("completed","success")));
    Path archive=zip(null,SHA,null);
    var artifact=JSON.createObjectNode().put("id",123).put("name","release-"+row.get().getRequestId()).put("expired",false).put("size_in_bytes",Files.size(archive)).put("digest","sha256:"+FlutterBuildService.sha256(archive));
    when(api.request(eq("GET"),contains("/artifacts?"),isNull())).thenReturn(response(JSON.createObjectNode().set("artifacts",JSON.createArrayNode().add(artifact))));
    doAnswer(a->{Files.copy(archive,a.<Path>getArgument(1));return null;}).when(api).download(anyString(),any(Path.class),anyLong(),any(Instant.class),any());
    var logs=new ArrayList<String>();
    assertThat(service.build(task,config,directory,logs::add)).contains("构建完成");
    assertThat(logs).anyMatch(line->line.contains("正在初始化任务信息"));
    assertThat(Files.readString(directory.resolve(config.outputPath()))).isEqualTo("test-package");
    assertThat(row.get().getState()).isEqualTo("SUCCEEDED");assertThat(row.get().getRunId()).isEqualTo(42);
    var order=inOrder(api);
    order.verify(api).request(eq("POST"),endsWith("/dispatches"),any());
    order.verify(api,times(2)).request(eq("GET"),endsWith("/actions/runs/42"),isNull());
    order.verify(api).request(eq("GET"),contains("/artifacts?"),isNull());
    order.verify(api).download(anyString(),any(Path.class),anyLong(),any(Instant.class),any());
    verify(api,times(1)).request(eq("POST"),endsWith("/dispatches"),any());
    var dispatched=org.mockito.ArgumentCaptor.forClass(Object.class);verify(api).request(eq("POST"),endsWith("/dispatches"),dispatched.capture());
    JsonNode sent=JSON.readTree(JSON.valueToTree(dispatched.getValue()).path("inputs").path("payload").asText()).path("config");
    assertThat(sent.path("versionName").asText()).isEqualTo(config.versionName());assertThat(sent.path("versionCode").asLong()).isEqualTo(config.versionCode());
  }
  @ParameterizedTest @ValueSource(strings={"Release App build",""})
  void onlyInitializingMetadataHasABoundedWait(String title)throws Exception{
    row.get().dispatching(Instant.now(),3600);row.get().attach(42);
    var initial=(com.fasterxml.jackson.databind.node.ObjectNode)run("in_progress","");initial.put("display_title",title);
    assertThatThrownBy(()->GitHubMobileBuildService.verifyRun(row.get(),initial)).isInstanceOf(GitHubMobileBuildService.RunMetadataPendingException.class);
    ReflectionTestUtils.setField(row.get(),"dispatchedAt",Instant.now().minusSeconds(181));
    assertThatThrownBy(()->GitHubMobileBuildService.verifyRun(row.get(),initial)).isInstanceOf(IllegalStateException.class).hasMessageContaining("超过 3 分钟");
  }
  @ParameterizedTest @ValueSource(strings={"id","event","repository","path","display_title"})
  void actualIdentityMismatchFailsImmediatelyEvenDuringInitialization(String field)throws Exception{
    row.get().dispatching(Instant.now(),3600);row.get().attach(42);
    var initial=(com.fasterxml.jackson.databind.node.ObjectNode)run("queued","");initial.put("display_title","Release App build");
    if(field.equals("id"))initial.put(field,99);
    else if(field.equals("repository"))initial.set(field,JSON.createObjectNode().put("full_name","another/repo"));
    else initial.put(field,"another-task");
    when(api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenReturn(response(initial));
    assertThatThrownBy(()->service.build(task,config,directory,null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("身份");
    verify(api,never()).request(eq("GET"),contains("/artifacts?"),any());
    verify(api,never()).download(anyString(),any(),anyLong(),any(),any());
    verify(api,never()).request(eq("POST"),anyString(),any());
  }
  @Test void completedRunWithPlaceholderNeverCountsAsSuccessfulBuild()throws Exception{
    row.get().dispatching(Instant.now(),3600);row.get().attach(42);
    var completed=(com.fasterxml.jackson.databind.node.ObjectNode)run("completed","success");completed.put("display_title","Release App build");
    assertThatThrownBy(()->GitHubMobileBuildService.verifyRun(row.get(),completed)).isInstanceOf(IllegalStateException.class).hasMessageContaining("任务专属标识");
  }
  @Test void cancellationWaitsForOriginalIdentityBeforeCancelling()throws Exception{
    row.get().dispatching(Instant.now(),3600);row.get().attach(42);service.requestCancel(task.getId());
    var initial=(com.fasterxml.jackson.databind.node.ObjectNode)run("queued","");initial.put("display_title","Release App build");
    when(api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenReturn(response(initial),response(run("in_progress","")));
    service.reconcileCancellations();assertThat(row.get().getState()).isEqualTo("CANCEL_REQUESTED");
    verify(api,never()).request(eq("POST"),anyString(),any());
    service.reconcileCancellations();verify(api).request(eq("POST"),endsWith("/42/cancel"),isNull());
  }
  Path zip(String extra,String commit,String digest)throws Exception{
    Path zip=directory.resolve(UUID.randomUUID()+".zip");byte[] data="test-package".getBytes();
    String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    var manifest=Map.of("request_id",row.get().getRequestId(),"task_id",task.getId(),"commit_id",commit,"config_sha256",FlutterBuildService.configHash(config),"format","APK","size_bytes",data.length,"sha256",digest==null?sha:digest);
    try(var stream=new ZipOutputStream(Files.newOutputStream(zip))){for(var part:Map.of("app.apk",data,"manifest.json",JSON.writeValueAsBytes(manifest)).entrySet()){stream.putNextEntry(new ZipEntry(part.getKey()));stream.write(part.getValue());stream.closeEntry();}if(extra!=null){stream.putNextEntry(new ZipEntry(extra));stream.write(data);stream.closeEntry();}}
    return zip;
  }
  @Test void boundedExtractionVerifiesIdentityAndHashBeforeReplacingOutput()throws Exception{
    Path project=directory.resolve("project");Files.createDirectory(project);
    GitHubMobileBuildService.extract(zip(null,SHA,null),project,row.get(),task,config);
    Path output=project.resolve(config.outputPath());assertThat(Files.readString(output)).isEqualTo("test-package");
    assertThatThrownBy(()->GitHubMobileBuildService.extract(zip("../escape",SHA,null),project,row.get(),task,config)).hasMessageContaining("非预期路径");
    assertThatThrownBy(()->GitHubMobileBuildService.extract(zip(null,"c".repeat(40),null),project,row.get(),task,config)).hasMessageContaining("当前任务");
    assertThatThrownBy(()->GitHubMobileBuildService.extract(zip(null,SHA,"0".repeat(64)),project,row.get(),task,config)).hasMessageContaining("SHA-256");
    assertThat(Files.readString(output)).isEqualTo("test-package");assertThat(directory.resolve("escape")).doesNotExist();
  }
  @Test void engineIsImmutableAndLegacyTasksAreLocal(){var old=new ReleaseTask(1L,TaskKind.BUILD,null,"test","main",null,false);assertThat(old.getMobileBuildEngine()).isEqualTo("LOCAL");assertThatThrownBy(()->task.setMobileBuildEngine("LOCAL")).hasMessageContaining("不可修改");settings.buildEngine="typo";assertThatThrownBy(settings::validate).hasMessageContaining("只能是");}
}
