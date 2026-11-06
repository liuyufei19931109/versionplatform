package com.local.release.service;

import com.local.release.domain.AppService;
import com.local.release.domain.ModelEnums.ServiceType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class ServiceBuildEngineSelectionTest {
  @ParameterizedTest
  @CsvSource({
    "BACKEND,github,GITHUB,GITHUB", "BACKEND,github,LOCAL,LOCAL", "BACKEND,local,GITHUB,LOCAL", "BACKEND,local,LOCAL,LOCAL",
    "FRONTEND,github,GITHUB,GITHUB", "FRONTEND,github,LOCAL,LOCAL", "FRONTEND,local,GITHUB,LOCAL", "FRONTEND,local,LOCAL,LOCAL",
    "FLUTTER_ANDROID,github,GITHUB,GITHUB", "FLUTTER_ANDROID,github,LOCAL,LOCAL", "FLUTTER_ANDROID,local,GITHUB,LOCAL", "FLUTTER_ANDROID,local,LOCAL,LOCAL"
  })
  void globalLocalOverridesAndGithubDefersToEachService(ServiceType type,String global,String chosen,String expected){
    AppService app=new AppService();app.setServiceType(type);app.setServiceKey("test-service");app.setBuildEngine(chosen);
    assertThat(select(app,global,"*")).isEqualTo(expected);
  }

  @ParameterizedTest @EnumSource(value=ServiceType.class,names={"BACKEND","FRONTEND"})
  void unlistedCloudChoiceFailsExplicitlyAndWildcardAllowsIt(ServiceType type){
    AppService app=new AppService();app.setServiceType(type);app.setServiceKey("new-service");
    assertThatThrownBy(()->select(app,"github","allowed-service")).isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("new-service").hasMessageContaining("SERVICE_KEYS");
    assertThat(select(app,"github","allowed-service, * ")).isEqualTo("GITHUB");
    assertThat(select(app,"github","new-service")).isEqualTo("GITHUB");
    assertThat(select(app,"local","allowed-service")).isEqualTo("LOCAL");
    app.setBuildEngine("LOCAL");assertThat(select(app,"github","allowed-service")).isEqualTo("LOCAL");
  }

  @ParameterizedTest @EnumSource(value=ServiceType.class,names={"BACKEND","FRONTEND","FLUTTER_ANDROID"})
  void invalidGlobalSwitchCannotBeBypassedByServiceLocalChoice(ServiceType type){
    AppService app=new AppService();app.setServiceType(type);app.setServiceKey("test-service");app.setBuildEngine("LOCAL");
    assertThatThrownBy(()->select(app,"typo","*")).hasMessageContaining("只能是 local 或 github");
  }

  private String select(AppService app,String global,String keys){
    return switch(app.getServiceType()){
      case BACKEND -> {var settings=new BackendCloudSettings();settings.repository="example-org/build-workflows";settings.buildEngine=global;settings.serviceKeys=keys;settings.validate();yield new GitHubBackendBuildService(settings,null,null).engineForNewTask(app);}
      case FRONTEND -> {var settings=new FrontendCloudSettings();settings.repository="example-org/build-workflows";settings.buildEngine=global;settings.serviceKeys=keys;settings.validate();yield new GitHubFrontendBuildService(settings,null,null).engineForNewTask(app);}
      case FLUTTER_ANDROID -> {var settings=new MobileCloudSettings();settings.repository="example-org/build-workflows";settings.buildEngine=global;settings.validate();yield new GitHubMobileBuildService(settings,null,null).engineForNewTask(app);}
    };
  }
}
