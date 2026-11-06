package com.local.release.service;

import com.local.release.domain.AppService;
import com.local.release.domain.ModelEnums.ServiceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FrontendMultiStepBuildTest {
  @TempDir Path root;
  AppService service(String command){var app=new AppService();app.setServiceType(ServiceType.FRONTEND);app.setGitRepo("git@github.com:example-org/example-web.git");app.setBuildCommand(command);app.setJarPath("dist/site");return app;}
  String command(String kind){return "npm ci && npm run type-check && npm run build:"+kind+" && mkdir -p nginx && cp deploy/nginx-"+kind+".conf nginx/default.conf";}
  @Test void namedBuildsKeepTheirOwnLockedNginxConfig()throws Exception{
    Files.createDirectories(root.resolve("deploy"));
    for(String kind:java.util.List.of("site","preview")){
      Files.writeString(root.resolve("deploy/nginx-"+kind+".conf"),kind);
      var config=FrontendBuildConfiguration.from(service(command(kind)),new FrontendCloudSettings());
      assertThat(config.nginxSourcePath()).isEqualTo("deploy/nginx-"+kind+".conf");
      GitHubFrontendBuildService.installNginxConfig(config,root);
      assertThat(Files.readString(root.resolve("nginx/default.conf"))).isEqualTo(kind);
      assertThat(config.json()).doesNotContain("nginxSourcePath");
      assertThat(FrontendBuildConfiguration.parse(config.json())).isEqualTo(config);
    }
  }
  @Test void oldCommandHasNoSideEffects()throws Exception{
    var config=FrontendBuildConfiguration.from(service("npm ci && npm run build"),new FrontendCloudSettings());
    GitHubFrontendBuildService.installNginxConfig(config,root);assertThat(root.resolve("nginx")).doesNotExist();
    Files.createDirectory(root.resolve("nginx"));Files.writeString(root.resolve("nginx/default.conf"),"existing");
    GitHubFrontendBuildService.installNginxConfig(config,root);assertThat(root.resolve("nginx/default.conf")).hasContent("existing");
  }
  @Test void historicalJsonAndHashRemainByteForByteCompatible(){
    String legacy="{\"sourceRepository\":\"example-org/example-ui\",\"workDirectory\":\"\",\"command\":\"npm ci && npm run build\",\"outputDirectory\":\"dist\",\"nodeVersion\":\"22\",\"heapMiB\":6144}";
    var config=FrontendBuildConfiguration.parse(legacy);
    assertThat(config.json()).isEqualTo(legacy);assertThat(config.hash()).isEqualTo("18ff030addc26a0f5eaca7e82e4745e1ebc8ce76fcb492d1eab19f7ee626cf46");
    assertThat(config.nginxSourcePath()).isNull();
  }
  @Test void upToFiveScriptsPreserveTheirOriginalOrderAndOptions(){
    String command="npm ci --no-audit --no-fund && npm run type-check && npm run lint && npm run prepare:assets && npm run build:site && npm run verify-build";
    var config=FrontendBuildConfiguration.from(service(command),new FrontendCloudSettings());
    assertThat(config.command()).isEqualTo(command);assertThat(FrontendBuildConfiguration.parse(config.json()).command()).isEqualTo(command);
  }
  @Test void shellAndUnboundedCommandsAreRejected(){
    for(String bad:java.util.List.of(command("site")+"; echo ok",command("../escape"),command("site").replace("deploy/","../"),"npm ci"+" && npm run build".repeat(6),"npm ci && npm run --silent","npm ci && npm run -x","npm ci && npm run build && npm run --ignore-scripts",command("site")+" && npm run build",command("site").replace("nginx/default.conf","other/default.conf"))){
      assertThatThrownBy(()->FrontendBuildConfiguration.from(service(bad),new FrontendCloudSettings())).hasMessageContaining("构建命令");
    }
  }
  @Test void missingOrDirectoryConfigIsRejected()throws Exception{
    var config=FrontendBuildConfiguration.from(service(command("site")),new FrontendCloudSettings());
    assertThatThrownBy(()->GitHubFrontendBuildService.installNginxConfig(config,root)).hasMessageContaining("不存在");
    Files.createDirectories(root.resolve("deploy/nginx-site.conf"));
    assertThatThrownBy(()->GitHubFrontendBuildService.installNginxConfig(config,root)).hasMessageContaining("不存在");
  }
  @ParameterizedTest @ValueSource(strings={"deploy","DEPLOY","deploy/nginx-site.conf","Deploy/nginx-Site.conf"})
  void outputCannotReplaceThePinnedNginxSource(String output){
    AppService app=service(command("site"));app.setJarPath(output);
    assertThatThrownBy(()->FrontendBuildConfiguration.from(app,new FrontendCloudSettings())).hasMessageContaining("不能覆盖锁定源码");
    var snapshot=new FrontendBuildConfiguration("example-org/example-ui","",command("site"),output,"22",6144);
    assertThatThrownBy(()->GitHubFrontendBuildService.installNginxConfig(snapshot,root)).hasMessageContaining("不能覆盖锁定源码");
    assertThat(root.resolve("nginx")).doesNotExist();
  }
  @Test void oversizedConfigCannotReplaceExistingTarget()throws Exception{
    Files.createDirectories(root.resolve("deploy"));Files.write(root.resolve("deploy/nginx-site.conf"),new byte[1024*1024+1]);
    Files.createDirectory(root.resolve("nginx"));Files.writeString(root.resolve("nginx/default.conf"),"existing");
    assertThatThrownBy(()->GitHubFrontendBuildService.installNginxConfig(FrontendBuildConfiguration.from(service(command("site")),new FrontendCloudSettings()),root)).isInstanceOf(IOException.class).hasMessageContaining("过大");
    assertThat(root.resolve("nginx/default.conf")).hasContent("existing");
  }
  @ParameterizedTest @ValueSource(strings={"source-file","source-directory","target-file","target-directory","project"})
  void symbolicLinksCannotReadOrOverwriteOutsideCheckout(String location)throws Exception{
    Path project=Files.createDirectory(root.resolve("project")),outside=Files.createDirectory(root.resolve("outside"));
    Files.createDirectory(project.resolve("deploy"));Files.writeString(project.resolve("deploy/nginx-site.conf"),"locked-source");
    Files.createDirectory(project.resolve("nginx"));Files.writeString(project.resolve("nginx/default.conf"),"existing");
    Files.writeString(outside.resolve("nginx-site.conf"),"outside-source");Files.writeString(outside.resolve("default.conf"),"outside-target");
    switch(location){
      case "source-file" -> {Files.delete(project.resolve("deploy/nginx-site.conf"));Files.createSymbolicLink(project.resolve("deploy/nginx-site.conf"),outside.resolve("nginx-site.conf"));}
      case "source-directory" -> {Files.delete(project.resolve("deploy/nginx-site.conf"));Files.delete(project.resolve("deploy"));Files.createSymbolicLink(project.resolve("deploy"),outside);}
      case "target-file" -> {Files.delete(project.resolve("nginx/default.conf"));Files.createSymbolicLink(project.resolve("nginx/default.conf"),outside.resolve("default.conf"));}
      case "target-directory" -> {Files.delete(project.resolve("nginx/default.conf"));Files.delete(project.resolve("nginx"));Files.createSymbolicLink(project.resolve("nginx"),outside);}
      case "project" -> {Files.createSymbolicLink(root.resolve("linked-project"),project);project=root.resolve("linked-project");}
    }
    Path pinnedProject=project;
    var config=FrontendBuildConfiguration.from(service(command("site")),new FrontendCloudSettings());
    assertThatThrownBy(()->GitHubFrontendBuildService.installNginxConfig(config,pinnedProject)).hasMessageContaining("符号链接");
    assertThat(outside.resolve("nginx-site.conf")).hasContent("outside-source");assertThat(outside.resolve("default.conf")).hasContent("outside-target");
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
  void artifactMustBeVerifiedBeforePinnedNginxConfigIsCopied(boolean wrongIdentity)throws Exception{
    var fixture=new GitHubFrontendBuildServiceTest();fixture.directory=root;fixture.setup();
    fixture.app.setBuildCommand(command("site"));fixture.config=FrontendBuildConfiguration.from(fixture.app,fixture.settings);fixture.cloud.prepare(fixture.task,fixture.app);
    fixture.row.get().attach(42);fixture.row.get().completed("success");
    Files.createDirectory(root.resolve("deploy"));Files.writeString(root.resolve("deploy/nginx-site.conf"),"locked-site");
    Files.writeString(root.resolve("deploy/nginx-preview.conf"),"locked-preview");
    Files.createDirectory(root.resolve("nginx"));Files.writeString(root.resolve("nginx/default.conf"),"existing");
    Path artifact=fixture.artifact("index.html",wrongIdentity);
    var item=GitHubFrontendBuildServiceTest.JSON.createObjectNode().put("id",123).put("name","release-frontend-"+fixture.row.get().getRequestId()).put("expired",false).put("size_in_bytes",Files.size(artifact)).put("digest","sha256:"+FlutterBuildService.sha256(artifact));
    when(fixture.api.request(eq("GET"),endsWith("/actions/runs/42"),isNull())).thenReturn(fixture.response(fixture.run("completed","success")));
    when(fixture.api.request(eq("GET"),contains("/artifacts?"),isNull())).thenReturn(fixture.response(GitHubFrontendBuildServiceTest.JSON.createObjectNode().set("artifacts",GitHubFrontendBuildServiceTest.JSON.createArrayNode().add(item))));
    doAnswer(call->{Files.copy(artifact,call.<Path>getArgument(1));return null;}).when(fixture.api).download(anyString(),any(Path.class),anyLong(),any(),any());
    // A later service edit must not redirect this completed task's frozen copy command.
    fixture.app.setBuildCommand(command("preview"));
    if(wrongIdentity){assertThatThrownBy(()->fixture.cloud.build(fixture.task,root,null)).hasMessageContaining("身份");assertThat(root.resolve("nginx/default.conf")).hasContent("existing");}
    else{assertThat(fixture.cloud.build(fixture.task,root,null)).contains("构建完成");assertThat(root.resolve("nginx/default.conf")).hasContent("locked-site");assertThat(root.resolve("dist/index.html")).hasContent("hello");}
    verify(fixture.api,never()).request(eq("POST"),anyString(),any());
  }
}
