package com.local.release.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FlutterBuildConfigurationTest {
  static String json(String extra){return "{\"mobileBuild\":{\"flutterVersion\":\"3.35.4\",\"applicationId\":\"com.example.app\",\"expectedCertificateSha256\":\""+"ab".repeat(32)+"\""+extra+"}}";}
  @Test void requiresPinnedToolchainAndSigningIdentity(){
    var config=FlutterBuildConfiguration.parse(json(""));assertThat(config.buildTimeoutSeconds()).isEqualTo(600);assertThat(config.aabPath()).isEqualTo("build/app/outputs/bundle/release/app-release.aab");
    assertThatThrownBy(()->FlutterBuildConfiguration.parse(json("").replace("3.35.4","stable"))).hasMessageContaining("具体版本");
    assertThatThrownBy(()->FlutterBuildConfiguration.parse(json("").replace("ab".repeat(32),"bad"))).hasMessageContaining("64 位");
    assertThatThrownBy(()->FlutterBuildConfiguration.parse("{}")).hasMessageContaining("Flutter Android");
  }
  @Test void flavorDerivesOutputWithoutChangingVersionAndAcceptsOverrides(){
    var config=FlutterBuildConfiguration.parse(json(",\"flavor\":\"production\",\"target\":\"lib/main_prod.dart\",\"versionName\":\"1.4.0\",\"versionCode\":31,\"signingProfile\":\"app-prod\""));
    assertThat(config.aabPath()).isEqualTo("build/app/outputs/bundle/productionRelease/app-production-release.aab");assertThat(config.versionCode()).isEqualTo(31L);assertThat(config.versionName()).isEqualTo("1.4.0");
  }
  @Test void rejectsTraversalInvalidTimeoutAndRawSigningSecrets(){
    for(String extra:new String[]{",\"aabPath\":\"../app.aab\"",",\"target\":\"C:/secret\"",",\"signingProfile\":\"../key\"",",\"versionCode\":0",",\"versionCode\":2100000001",",\"buildTimeoutSeconds\":30",",\"storePassword\":\"secret\""})
      assertThatThrownBy(()->FlutterBuildConfiguration.parse(json(extra))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void apkIsExplicitSingleFileAndOldConfigurationStillMeansAab(){
    assertThat(FlutterBuildConfiguration.parse(json("")).outputFormat()).isEqualTo("AAB");
    var apk=FlutterBuildConfiguration.parse(json(",\"outputFormat\":\"APK\",\"flavor\":\"production\""));
    assertThat(apk.outputPath()).isEqualTo("build/app/outputs/flutter-apk/app-production-release.apk");assertThat(apk.archiveName()).isEqualTo("app.apk");
    for(String extra:new String[]{",\"outputFormat\":\"BOTH\"",",\"apkPath\":\"../secret.apk\"",",\"apkPath\":\"build/*.apk\"",",\"apkPath\":\"build/test.aab\""})assertThatThrownBy(()->FlutterBuildConfiguration.parse(json(extra))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void disabledDraftCanBeIncompleteButCannotStoreUnsafePathsOrSecretFields(){
    assertThat(FlutterBuildConfiguration.parseDraft("{}").flutterVersion()).isNull();
    assertThat(FlutterBuildConfiguration.parseDraft("{\"mobileBuild\":{\"outputFormat\":\"APK\"}}").isApk()).isTrue();
    assertThatThrownBy(()->FlutterBuildConfiguration.parse("{\"mobileBuild\":{\"outputFormat\":\"APK\"}}")).hasMessageContaining("不能为空");
    for(String raw:new String[]{"{\"apkPath\":\"../bad.apk\"}","{\"storePassword\":\"secret\"}","{\"flutterVersion\":\"stable\"}"})assertThatThrownBy(()->FlutterBuildConfiguration.parseDraft("{\"mobileBuild\":"+raw+"}")).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void snapshotsLockFormatFlavorAndWorkingDirectoryWithoutIncludingOtherSecrets()throws Exception{
    var app=new com.local.release.domain.AppService();app.setConfigJson(json(",\"outputFormat\":\"APK\",\"flavor\":\"production\""));app.setWorkDirectory("mobile");app.setGitRepo("git@example:app.git");
    var task=new com.local.release.domain.ReleaseTask(1L,com.local.release.domain.ModelEnums.TaskKind.BUILD,null,"build","master",null,false);
    task.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.snapshot(app));app.setWorkDirectory("other");app.setConfigJson(json(""));
    assertThat(FlutterBuildConfiguration.fromTask(task).outputFormat()).isEqualTo("APK");assertThat(FlutterBuildConfiguration.taskWorkDirectory(task)).isEqualTo("mobile");
    assertThatThrownBy(()->task.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.snapshot(app))).hasMessageContaining("不可修改");
    assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(task)).doesNotContain("mobileBuildConfigSnapshot","git@example");
    app.setGitRepo("git@example:other.git");assertThatThrownBy(()->FlutterBuildConfiguration.verifyRepository(task,app)).hasMessageContaining("仓库地址已改变");
    var legacy=new com.local.release.domain.ReleaseTask(1L,com.local.release.domain.ModelEnums.TaskKind.BUILD,null,"build","master",null,false);
    assertThatThrownBy(()->FlutterBuildConfiguration.fromTask(legacy)).hasMessageContaining("新建打包任务");
  }
}
