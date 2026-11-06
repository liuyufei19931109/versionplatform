package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.repo.RegistryCredentialRepository;
import com.local.release.security.CredentialSecretCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandRunnerTest {
  @TempDir Path directory;

  @Test void failedSecondServerKeepsFirstServerProgressAndIdentifiesOnlyFailedTarget() throws Exception {
    var ssh = mock(SshClient.class);
    var runner = new CommandRunner(new ObjectMapper(), ssh, mock(RegistryCredentialRepository.class), mock(CredentialSecretCrypto.class));
    runner.workspaceRoot = directory.toString();
    var first = new DeploymentServer(); first.setName("A"); ReflectionTestUtils.setField(first, "id", 4L);
    var second = new DeploymentServer(); second.setName("B"); ReflectionTestUtils.setField(second, "id", 9L);
    var environment = new ServiceEnvironment(); environment.setEnabled(true);
    var task = task(22, "0123456789012345678901234567890123456789");
    when(ssh.execute(eq(first), eq("true"), any(Path.class))).thenReturn("");
    var failure = new SshExecutionException(SshExecutionException.Code.SSH_AUTH, "SSH 登录认证失败", 255, false);
    when(ssh.execute(eq(second), eq("true"), any(Path.class))).thenThrow(failure);
    List<String> log = new ArrayList<>();

    assertThatThrownBy(() -> runner.execute(StepType.SSH_CONNECT, new AppService(), task, null, environment, List.of(first, second), log::add))
      .hasMessageStartingWith("[失败节点 #9] [SSH_AUTH]").hasCause(failure);
    assertThat(log).contains("节点 #4 已完成本步骤").doesNotContain("节点 #9 已完成本步骤");
  }
  String git(Path path,String... args)throws Exception{List<String> command=new ArrayList<>(List.of("git"));command.addAll(List.of(args));return BoundedProcessRunner.run(command,path,null,20,null).trim();}
  CommandRunner runner(){CommandRunner runner=new CommandRunner(new ObjectMapper(),mock(SshClient.class),mock(RegistryCredentialRepository.class),mock(CredentialSecretCrypto.class));runner.workspaceRoot=directory.resolve("workspaces").toString();return runner;}
  ReleaseTask task(long id,String commit){ReleaseTask task=new ReleaseTask(1L,TaskKind.BUILD,null,"build","main","",false);ReflectionTestUtils.setField(task,"id",id);task.setCommitId(commit);return task;}

  @Test void queuedBuildChecksOutConfirmedCommitEvenAfterBranchMoves()throws Exception{
    Path repo=Files.createDirectory(directory.resolve("repository"));git(repo,"init","-b","main");git(repo,"config","user.name","Release Test");git(repo,"config","user.email","release-test@example.invalid");
    Files.writeString(repo.resolve("version.txt"),"confirmed");git(repo,"add",".");git(repo,"commit","-m","confirmed");String confirmed=git(repo,"rev-parse","HEAD");
    Files.writeString(repo.resolve("version.txt"),"newer");git(repo,"add",".");git(repo,"commit","-m","newer");
    AppService service=new AppService();service.setGitRepo(repo.toString());CommandRunner runner=runner();ReleaseTask task=task(1L,confirmed);
    runner.execute(StepType.GIT_PULL,service,task,null,null,List.of(),line->{});
    Path checkout=directory.resolve("workspaces/1/source");assertThat(git(checkout,"rev-parse","HEAD")).isEqualTo(confirmed);assertThat(Files.readString(checkout.resolve("version.txt"))).isEqualTo("confirmed");
    assertThat(task.getCommitId()).isEqualTo(confirmed);
  }

  @Test void sameCommitBuildsGetDistinctImmutableVersionNames(){
    CommandRunner runner=runner();String commit="1234567890abcdef1234567890abcdef12345678";
    assertThat(runner.imageVersion(task(1L,commit))).isNotEqualTo(runner.imageVersion(task(2L,commit))).endsWith("-b1");
  }

  @Test void simulationCannotCreateFalseSuccess(){
    CommandRunner runner=runner();runner.forceSimulate=true;
    assertThatThrownBy(()->runner.execute(StepType.GIT_PULL,new AppService(),task(1L,"1234567890abcdef1234567890abcdef12345678"),null,null,List.of(),null)).hasMessageContaining("模拟");
  }

  @ParameterizedTest
  @CsvSource({"FRONTEND,npm ci && npm run build", "BACKEND,mvn -B clean package -DskipTests"})
  void legacyBuildStepStillSelectsActualCommandByServiceTypeWithoutStartingAProcess(ServiceType serviceType,String expectedCommand)throws Exception{
    AppService service=new AppService();service.setServiceType(serviceType);service.setBuildCommand(null);
    service.setJarPath(serviceType==ServiceType.FRONTEND?"dist":"target/*.jar");
    Path source=directory.resolve("workspaces/1/source");
    if(serviceType==ServiceType.FRONTEND){Files.createDirectories(source.resolve("dist"));Files.writeString(source.resolve("dist/index.html"),"test fixture");}
    CommandRunner runner=runner();
    try(var processes=mockStatic(BoundedProcessRunner.class)){
      processes.when(()->BoundedProcessRunner.run(anyList(),any(Path.class),isNull(),anyLong(),any())).thenReturn("command output\n");
      String output=runner.execute(StepType.BUILD_JAR,service,task(1L,"1234567890abcdef1234567890abcdef12345678"),null,null,List.of(),line->{});
      processes.verify(()->BoundedProcessRunner.run(argThat(command->command.getLast().equals(expectedCommand)),eq(source),isNull(),eq(1800L),any()));
      if(serviceType==ServiceType.FRONTEND)assertThat(output).contains("前端输出目录验证通过：dist");
      else assertThat(output).isEqualTo("command output\n");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans={true,false})
  void frontendImagePreservesNestedAssetsAndNormalizesImagePermissionsWithEitherNginxConfiguration(boolean repositoryConfig)throws Exception{
    AppService service=new AppService();service.setServiceType(ServiceType.FRONTEND);service.setWorkDirectory("web");service.setJarPath("dist");service.setImageRepository("fixture/frontend");
    Path work=directory.resolve("workspaces/1/source/web"),dist=work.resolve("dist");
    Files.createDirectories(dist.resolve("assets/branding"));
    Files.writeString(dist.resolve("index.html"),"<html><img src=\"/assets/branding/logo.svg\"></html>");
    Files.writeString(dist.resolve("assets/branding/logo.svg"),"<svg xmlns=\"http://www.w3.org/2000/svg\"><title>permission fixture</title></svg>");
    Files.writeString(dist.resolve("assets/app.js"),"console.log('fixture');\n");
    byte[] binary={0,17,(byte)255,42};Files.write(dist.resolve("assets/font.woff2"),binary);
    Files.writeString(work.resolve(".env"),"PRIVATE_SOURCE=not-for-image\n");
    if(Files.getFileStore(dist).supportsFileAttributeView("posix"))try(var paths=Files.walk(dist)){
      for(Path path:paths.toList())Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(Files.isDirectory(path)?"rwx------":"rw-------"));
    }
    String customNginx="server { listen 80; root /usr/share/nginx/html; location = /healthz { return 200 'ok'; } location / { try_files $uri $uri/ /index.html; } }\n";
    if(repositoryConfig){Files.createDirectories(work.resolve("nginx"));Files.writeString(work.resolve("nginx/default.conf"),customNginx);}
    CommandRunner runner=runner();Path context=work.resolve(".release-platform/frontend-image");
    try(var processes=mockStatic(BoundedProcessRunner.class)){
      processes.when(()->BoundedProcessRunner.run(anyList(),any(Path.class),isNull(),anyLong(),any())).thenReturn("fixture image command\n");
      String output=runner.execute(StepType.BUILD_IMAGE,service,task(1L,"1234567890abcdef1234567890abcdef12345678"),null,null,List.of(),line->{});
      processes.verify(()->BoundedProcessRunner.run(eq(List.of("docker","build","--load","--provenance=false","--sbom=false","-f",context.resolve("Dockerfile").toString(),"-t","fixture/frontend:main-123456-b1",context.toString())),eq(work),isNull(),eq(1800L),any()));
      processes.verifyNoMoreInteractions();assertThat(output).contains("静态 Nginx 模式","自动生成 Nginx 镜像");
    }
    assertThat(Files.readString(context.resolve("html/index.html"))).isEqualTo(Files.readString(dist.resolve("index.html")));
    assertThat(Files.readString(context.resolve("html/assets/branding/logo.svg"))).isEqualTo(Files.readString(dist.resolve("assets/branding/logo.svg")));
    assertThat(Files.readString(context.resolve("html/assets/app.js"))).isEqualTo(Files.readString(dist.resolve("assets/app.js")));
    assertThat(Files.readAllBytes(context.resolve("html/assets/font.woff2"))).containsExactly(binary);
    assertThat(context.resolve(".env")).doesNotExist();assertThat(context.resolve("html/.env")).doesNotExist();
    String dockerfile=Files.readString(context.resolve("Dockerfile"));
    assertThat(dockerfile.lines().toList()).containsExactly("FROM nginx:1.27-alpine","COPY html/ /usr/share/nginx/html/","COPY default.conf /etc/nginx/conf.d/default.conf","RUN find /usr/share/nginx/html -type d -exec chmod 755 {} + && find /usr/share/nginx/html -type f -exec chmod 644 {} + && chmod 644 /etc/nginx/conf.d/default.conf","EXPOSE 80");
    String generatedNginx=Files.readString(context.resolve("default.conf"));
    if(repositoryConfig)assertThat(generatedNginx).isEqualTo(customNginx);
    else assertThat(generatedNginx).contains("root /usr/share/nginx/html;","try_files $uri $uri/ /index.html;");
  }

  @Test void frontendImagePrefersRepositoryDockerfileAndUsesRepositoryRootAsContext()throws Exception{
    AppService service=new AppService();service.setServiceType(ServiceType.FRONTEND);service.setConfigJson("{\"imageBuildMode\":\"REPOSITORY_DOCKERFILE\"}");service.setWorkDirectory("web");service.setJarPath("dist");service.setDockerfilePath("deploy/Dockerfile");service.setImageRepository("fixture/frontend");
    Path source=directory.resolve("workspaces/1/source"),work=source.resolve("web"),dockerfile=source.resolve("deploy/Dockerfile");
    Files.createDirectories(work.resolve("dist"));Files.writeString(work.resolve("dist/index.html"),"built output");
    Files.createDirectories(dockerfile.getParent());String original="FROM node:22-alpine\nCOPY web/package.json ./\n";Files.writeString(dockerfile,original);
    CommandRunner runner=runner();
    try(var processes=mockStatic(BoundedProcessRunner.class)){
      processes.when(()->BoundedProcessRunner.run(anyList(),any(Path.class),isNull(),anyLong(),any())).thenReturn("repository dockerfile command\n");
      String output=runner.execute(StepType.BUILD_IMAGE,service,task(1L,"1234567890abcdef1234567890abcdef12345678"),null,null,List.of(),line->{});
      processes.verify(()->BoundedProcessRunner.run(eq(List.of("docker","build","--load","--provenance=false","--sbom=false","-f",dockerfile.toString(),"-t","fixture/frontend:main-123456-b1",source.toString())),eq(source),isNull(),eq(1800L),any()));
      processes.verifyNoMoreInteractions();assertThat(output).contains("使用仓库 Dockerfile","源码根目录");
    }
    assertThat(Files.readString(dockerfile)).isEqualTo(original);assertThat(work.resolve(".release-platform")).doesNotExist();
  }

  @Test void repositoryDockerfileModeSkipsDuplicateHostNodeBuild()throws Exception{
    AppService service=new AppService();service.setServiceType(ServiceType.FRONTEND);service.setConfigJson("{\"imageBuildMode\":\"REPOSITORY_DOCKERFILE\"}");service.setWorkDirectory("web");service.setDockerfilePath("Dockerfile");
    Path source=directory.resolve("workspaces/1/source");Files.createDirectories(source.resolve("web"));Files.writeString(source.resolve("Dockerfile"),"FROM node:22-alpine\n");
    CommandRunner runner=runner();
    try(var processes=mockStatic(BoundedProcessRunner.class)){
      String output=runner.execute(StepType.BUILD_JAR,service,task(1L,"1234567890abcdef1234567890abcdef12345678"),null,null,List.of(),line->{});
      processes.verifyNoInteractions();assertThat(output).contains("仓库 Dockerfile 模式","依赖安装和应用构建将在镜像构建步骤内完成");
    }
  }

  @Test void legacyFrontendWithoutStoredModeKeepsStaticNginxDefault(){
    AppService service=new AppService();service.setServiceType(ServiceType.FRONTEND);
    assertThat(BuildConfiguration.imageBuildMode(service)).isEqualTo(ImageBuildMode.STATIC_NGINX);
  }

  @Test void backendImageUsesExistingDockerfileAndContextWithoutPermissionRewrite()throws Exception{
    AppService service=new AppService();service.setServiceType(ServiceType.BACKEND);service.setWorkDirectory("server");service.setDockerfilePath("deploy/Dockerfile");service.setImageRepository("fixture/backend");
    Path work=directory.resolve("workspaces/1/source/server"),dockerfile=work.resolve("deploy/Dockerfile");Files.createDirectories(dockerfile.getParent());
    String original="FROM eclipse-temurin:21-jre\nCOPY target/app.jar /app.jar\nENTRYPOINT [\"java\",\"-jar\",\"/app.jar\"]\n";Files.writeString(dockerfile,original);
    CommandRunner runner=runner();
    try(var processes=mockStatic(BoundedProcessRunner.class)){
      processes.when(()->BoundedProcessRunner.run(anyList(),any(Path.class),isNull(),anyLong(),any())).thenReturn("backend fixture command\n");
      assertThat(runner.execute(StepType.BUILD_IMAGE,service,task(1L,"1234567890abcdef1234567890abcdef12345678"),null,null,List.of(),line->{})).isEqualTo("backend fixture command\n");
      processes.verify(()->BoundedProcessRunner.run(eq(List.of("docker","build","--load","--provenance=false","--sbom=false","-f",dockerfile.toString(),"-t","fixture/backend:main-123456-b1",work.toString())),eq(work),isNull(),eq(1800L),any()));
      processes.verifyNoMoreInteractions();
    }
    assertThat(Files.readString(dockerfile)).isEqualTo(original);assertThat(work.resolve(".release-platform")).doesNotExist();
  }
}
