package com.local.release.service;

import com.local.release.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ServiceBuildEngineTaskTest {
  ReleaseExecutorTest fixture;
  GitHubBackendBuildService backend;
  GitHubFrontendBuildService frontend;
  GitHubMobileBuildService mobile;
  BackendCloudSettings backendSettings;
  FrontendCloudSettings frontendSettings;
  MobileCloudSettings mobileSettings;

  @BeforeEach void setup()throws Exception{
    fixture=new ReleaseExecutorTest();fixture.setup();
    backendSettings=new BackendCloudSettings();backendSettings.repository="example-org/build-workflows";backendSettings.buildEngine="github";backendSettings.serviceKeys="*";
    frontendSettings=new FrontendCloudSettings();frontendSettings.repository="example-org/build-workflows";frontendSettings.buildEngine="github";frontendSettings.serviceKeys="*";
    mobileSettings=new MobileCloudSettings();mobileSettings.repository="example-org/build-workflows";mobileSettings.buildEngine="github";
    backend=mock(GitHubBackendBuildService.class);frontend=mock(GitHubFrontendBuildService.class);mobile=mock(GitHubMobileBuildService.class);
    when(backend.engineForNewTask(any())).thenAnswer(call->backendSettings.engineForNewTask(call.getArgument(0)));
    when(frontend.engineForNewTask(any())).thenAnswer(call->frontendSettings.engineForNewTask(call.getArgument(0)));
    when(mobile.engineForNewTask(any())).thenAnswer(call->new GitHubMobileBuildService(mobileSettings,null,null).engineForNewTask(call.getArgument(0)));
    fixture.executor.setBackendCloud(backend);fixture.executor.setFrontendCloud(frontend);fixture.executor.setCloudBuild(mobile);
  }

  @ParameterizedTest @CsvSource({"BACKEND,GITHUB","BACKEND,LOCAL","FRONTEND,GITHUB","FRONTEND,LOCAL","FLUTTER_ANDROID,GITHUB","FLUTTER_ANDROID,LOCAL"})
  void creationSnapshotsChoiceAndReplayAndRetryIgnoreLaterServiceChanges(ServiceType type,String initial)throws Exception{
    AppService app=app(type);app.setBuildEngine(initial);when(fixture.services.findById(1L)).thenReturn(Optional.of(app));
    String request=fixture.request();ReleaseTask original=create(type,request);String configuration=original.getMobileBuildConfigSnapshot();
    assertThat(engine(original,type)).isEqualTo(initial);
    String changed=initial.equals("GITHUB")?"LOCAL":"GITHUB";app.setBuildEngine(changed);
    assertThat(create(type,request)).isSameAs(original);
    original.setStatus(TaskStatus.FAILED);fixture.executor.retryBuild(original.getId(),fixture.request(),"admin");
    assertThat(original.getStatus()).isEqualTo(TaskStatus.PENDING);assertThat(engine(original,type)).isEqualTo(initial);
    assertThat(original.getMobileBuildConfigSnapshot()).isEqualTo(configuration);
    if(type==ServiceType.BACKEND){verify(backend,times(1)).engineForNewTask(app);verify(backend,times(initial.equals("GITHUB")?1:0)).prepareRetry(original);}
    if(type==ServiceType.FRONTEND){verify(frontend,times(1)).engineForNewTask(app);verify(frontend,times(initial.equals("GITHUB")?1:0)).prepareRetry(original);}
    if(type==ServiceType.FLUTTER_ANDROID){verify(mobile,times(1)).engineForNewTask(app);verify(mobile,times(initial.equals("GITHUB")?1:0)).prepareRetry(original);}
    assertThat(engine(create(type,fixture.request()),type)).isEqualTo(changed);
    verifyNoInteractions(fixture.runner,fixture.flutter);
  }

  @ParameterizedTest @CsvSource({"BACKEND","FRONTEND","FLUTTER_ANDROID"})
  void emergencyLocalSwitchAffectsNewTasksAndPreservesCloudRetry(ServiceType type){
    AppService app=app(type);when(fixture.services.findById(1L)).thenReturn(Optional.of(app));
    ReleaseTask original=create(type,fixture.request());assertThat(engine(original,type)).isEqualTo("GITHUB");
    backendSettings.buildEngine="local";frontendSettings.buildEngine="local";mobileSettings.buildEngine="local";
    original.setStatus(TaskStatus.FAILED);fixture.executor.retryBuild(original.getId(),fixture.request(),"admin");
    assertThat(engine(original,type)).isEqualTo("GITHUB");assertThat(engine(create(type,fixture.request()),type)).isEqualTo("LOCAL");
    verifyNoInteractions(fixture.runner,fixture.flutter);
  }

  private ReleaseTask create(ServiceType type,String request){return type==ServiceType.FLUTTER_ANDROID?fixture.mobileBuild(1L,"test",request):fixture.build(1L,request);}
  private String engine(ReleaseTask task,ServiceType type){return switch(type){case BACKEND->task.getBackendBuildEngine();case FRONTEND->task.getFrontendBuildEngine();case FLUTTER_ANDROID->task.getMobileBuildEngine();};}
  private AppService app(ServiceType type){
    AppService app=new AppService();ReflectionTestUtils.setField(app,"id",1L);app.setName("test");app.setServiceKey("test-service");app.setServiceType(type);app.setImageRepository("registry/test");
    app.setBuildCommand(type==ServiceType.FRONTEND?"npm ci && npm run build":"mvn clean package -DskipTests");
    if(type==ServiceType.FLUTTER_ANDROID)app.setConfigJson("{\"mobileBuild\":{\"flutterVersion\":\"3.35.4\",\"environments\":{\"test\":{\"enabled\":true,\"applicationId\":\"com.example.app.pre\",\"expectedCertificateSha256\":\""+"a".repeat(64)+"\",\"signingProfile\":\"test-profile\"}}}}");
    return app;
  }
}
