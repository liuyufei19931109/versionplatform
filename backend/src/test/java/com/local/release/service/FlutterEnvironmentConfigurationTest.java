package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.local.release.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FlutterEnvironmentConfigurationTest {
  private final ObjectMapper json=new ObjectMapper();
  ObjectNode config(){
    ObjectNode root=json.createObjectNode(),common=root.putObject("mobileBuild");common.put("flutterVersion","3.47.3");common.put("outputFormat","APK");common.put("target","lib/main.dart");
    ObjectNode environments=common.putObject("environments");
    for (String env : new String[]{"test", "prod"}) {
      boolean test = env.equals("test");
      ObjectNode profile = environments.putObject(env);
      profile.put("enabled", true).put("applicationId", test ? "com.example.demo.pre" : "com.example.demo");
      profile.put("expectedCertificateSha256", (test ? "ab" : "cd").repeat(32));
      profile.put("signingProfile", "demo-" + env).put("buildMode", test ? "DEBUG" : "RELEASE").put("allowDebugSigning", test);
      profile.putObject("dartDefines").put("CHANNEL", env);
      profile.putObject("environmentVariables").put("APP_BUILD_PROFILE", env);
    }
    return root;
  }
  ObjectNode profile(ObjectNode root,String env){return (ObjectNode)root.path("mobileBuild").path("environments").path(env);}
  AppService app(ObjectNode root){AppService service=new AppService();service.setGitRepo("git@example:demo.git");service.setConfigJson(root.toString());return service;}
  @Test void selectedEnvironmentResolvesIndependentIdentitiesAndCorrectDebugArtifact(){
    var service=app(config());var pre=FlutterBuildConfiguration.forEnvironment(service,"test");var prod=FlutterBuildConfiguration.forEnvironment(service,"prod");
    assertThat(pre.isDebug()).isTrue();assertThat(pre.allowDebugSigning()).isTrue();assertThat(pre.apkPath()).endsWith("app-debug.apk");assertThat(pre.applicationId()).endsWith(".pre");assertThat(pre.flutterVersion()).isEqualTo("3.47.3");assertThat(pre.environmentVariables()).containsEntry("APP_BUILD_PROFILE","test");
    assertThat(prod.isDebug()).isFalse();assertThat(prod.apkPath()).endsWith("app-release.apk");assertThat(prod.expectedCertificateSha256()).isNotEqualTo(pre.expectedCertificateSha256());
  }
  @Test void legacySigningCannotSilentlyBecomeAnEnvironmentIdentity(){
    var root=config();ObjectNode common=(ObjectNode)root.path("mobileBuild");common.put("signingProfile","legacy");common.put("applicationId","com.example.legacy");common.put("expectedCertificateSha256","ef".repeat(32));profile(root,"test").remove("signingProfile");
    assertThatThrownBy(()->FlutterBuildConfiguration.forEnvironment(app(root),"test")).hasMessageContaining("服务端签名");
  }
  @Test void emptyEnvironmentTargetInheritsConfiguredDefaultWhileExplicitTargetOverrides(){
    var root=config();((ObjectNode)root.path("mobileBuild")).put("target","lib/main_demo.dart");profile(root,"test").put("target","");
    assertThat(FlutterBuildConfiguration.forEnvironment(app(root),"test").target()).isEqualTo("lib/main_demo.dart");
    profile(root,"test").put("target","lib/main_pre.dart");assertThat(FlutterBuildConfiguration.forEnvironment(app(root),"test").target()).isEqualTo("lib/main_pre.dart");
  }
  @Test void productionMustUseDistinctIdentityAndRelease(){
    for(String field:new String[]{"signingProfile","expectedCertificateSha256","applicationId"}){var root=config();profile(root,"prod").set(field,profile(root,"test").get(field));assertThatThrownBy(()->FlutterBuildConfiguration.parse(root.toString())).hasMessageContaining("生产与非生产");}
    var root=config();profile(root,"prod").put("buildMode","DEBUG");assertThatThrownBy(()->FlutterBuildConfiguration.parse(root.toString())).hasMessageContaining("生产必须");
  }
  @Test void customDefinesAndEnvironmentVariablesSurviveTaskSnapshots(){
    for(String env:new String[]{"test","prod"})for(String key:new String[]{"FEATURE_MODE","ASSET_ROOT"})for(String value:new String[]{"preview layout","asset://demo/v3"}){
      var root=config();((ObjectNode)profile(root,env).path("dartDefines")).put(key,value);var service=app(root);
      assertThat(FlutterBuildConfiguration.forEnvironment(service,env).dartDefines()).containsEntry(key,value);
      ReleaseTask task=new ReleaseTask(1L,ModelEnums.TaskKind.BUILD,null,env,"master",null,false);task.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.snapshot(service,env));
      var snapshot=FlutterBuildConfiguration.fromTask(task);
      assertThat(snapshot.dartDefines()).containsEntry(key,value);
      assertThat(snapshot.environmentVariables()).containsEntry("APP_BUILD_PROFILE",env);
    }
  }
  @Test void testingRequiresExplicitDebugPermission(){
    var root=config();profile(root,"test").put("allowDebugSigning",false);assertThatThrownBy(()->FlutterBuildConfiguration.parse(root.toString())).hasMessageContaining("明确允许");
    var aab=config();profile(aab,"test").put("outputFormat","AAB");assertThatThrownBy(()->FlutterBuildConfiguration.parse(aab.toString())).hasMessageContaining("Debug 仅支持 APK");
    var release=config();profile(release,"test").put("buildMode","RELEASE");assertThatThrownBy(()->FlutterBuildConfiguration.parse(release.toString())).hasMessageContaining("Release 构建不能启用");
  }
  @Test void rejectsSystemOverridesCredentialsAndControlCharacters(){
    for (String key : new String[]{"PATH", "JAVA_TOOL_OPTIONS", "LD_PRELOAD", "RELEASE_SIGNING_PROPERTIES", "APP_BUILD_PRIVATE_KEY", "APP_BUILD_API_TOKEN"}) {
      var root = config();
      ((ObjectNode) profile(root, "test").path("environmentVariables")).put(key, "value");
      assertThatThrownBy(() -> FlutterBuildConfiguration.parse(root.toString())).isInstanceOf(IllegalArgumentException.class);
    }
    var root = config();
    ((ObjectNode) profile(root, "test").path("dartDefines")).put("LABEL", "line\nbreak");
    assertThatThrownBy(() -> FlutterBuildConfiguration.parse(root.toString())).hasMessageContaining("控制字符");
    var secret=config();profile(secret,"test").put("storePassword","never-save");assertThatThrownBy(()->FlutterBuildConfiguration.parse(secret.toString())).hasMessageContaining("私有配置");
  }
  @Test void snapshotLocksResolvedProfileAndRejectsEnvironmentTampering(){
    var root=config();var service=app(root);String snapshot=FlutterBuildConfiguration.snapshot(service,"test");
    ReleaseTask task=new ReleaseTask(1L,ModelEnums.TaskKind.BUILD,null,"test","master",null,false);task.setMobileBuildConfigSnapshot(snapshot);
    profile(root,"test").put("applicationId","com.example.changed");service.setConfigJson(root.toString());assertThat(FlutterBuildConfiguration.fromTask(task).applicationId()).isEqualTo("com.example.demo.pre");
    ReleaseTask tampered=new ReleaseTask(1L,ModelEnums.TaskKind.BUILD,null,"prod","master",null,false);tampered.setMobileBuildConfigSnapshot(snapshot);assertThatThrownBy(()->FlutterBuildConfiguration.fromTask(tampered)).hasMessageContaining("环境与锁定快照不一致");
  }
  @Test void disabledProfilesMayBeIncompleteWithoutImplicitEnabling(){
    var root=config();((ObjectNode)root.path("mobileBuild").path("environments")).putObject("dev").put("enabled",false).put("buildMode","DEBUG").put("allowDebugSigning",false);
    assertThat(FlutterBuildConfiguration.parse(root.toString())).isNotNull();assertThatThrownBy(()->FlutterBuildConfiguration.forEnvironment(app(root),"dev")).hasMessageContaining("尚未配置并启用");
    profile(root,"test").put("enabled",false);profile(root,"prod").put("enabled",false);assertThat(FlutterBuildConfiguration.parseDraft(root.toString())).isNotNull();assertThatThrownBy(()->FlutterBuildConfiguration.parse(root.toString())).hasMessageContaining("至少完成");
  }
  @Test void legacySerializationRetainsExactlyTheOriginalFieldSet(){
    var legacy=FlutterBuildConfiguration.parse(FlutterBuildConfigurationTest.json(""));var node=FlutterBuildConfiguration.legacyJson(legacy);assertThat(node.size()).isEqualTo(12);assertThat(node.has("environmentName")).isFalse();assertThat(node.has("buildMode")).isFalse();assertThat(node.has("dartDefines")).isFalse();
  }
  @Test void assignedSnapshotLocksVersionAndPreSourceWithoutChangingResolvedConfigurationDigest()throws Exception{
    var root=config();((ObjectNode)root.path("mobileBuild")).put("versionPolicy","AUTO");var service=app(root);
    var resolved=FlutterBuildConfiguration.forEnvironment(service,"prod").withVersion("0.0.1",22);
    ReleaseTask task=new ReleaseTask(1L,ModelEnums.TaskKind.BUILD,null,"prod","release20261020",null,false);
    task.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.assignedSnapshot(service,resolved,"0.0.1",91L));
    assertThat(FlutterBuildConfiguration.fromTask(task)).isEqualTo(resolved);assertThat(FlutterBuildConfiguration.taskPreBuildTaskId(task)).isEqualTo(91L);
    assertThat(json.readTree(task.getMobileBuildConfigSnapshot()).path("snapshotVersion").asInt()).isEqualTo(4);
    assertThat(FlutterBuildService.configHash(resolved)).isEqualTo(FlutterBuildService.configHash(FlutterBuildConfiguration.fromTask(task)));
    ObjectNode tampered=(ObjectNode)json.readTree(task.getMobileBuildConfigSnapshot());tampered.put("baseVersion","0.1.0");ReleaseTask wrong=new ReleaseTask(1L,ModelEnums.TaskKind.BUILD,null,"prod","release20261020",null,false);wrong.setMobileBuildConfigSnapshot(tampered.toString());
    assertThatThrownBy(()->FlutterBuildConfiguration.fromTask(wrong)).hasMessageContaining("自动 App 版本快照无效");
    ObjectNode noSource=(ObjectNode)json.readTree(task.getMobileBuildConfigSnapshot());noSource.remove("preBuildTaskId");ReleaseTask missing=new ReleaseTask(1L,ModelEnums.TaskKind.BUILD,null,"prod","release20261020",null,false);missing.setMobileBuildConfigSnapshot(noSource.toString());assertThatThrownBy(()->FlutterBuildConfiguration.fromTask(missing)).hasMessageContaining("PRE");
  }
  @Test void legacyProductionSnapshotKeepsItsNameSourceAndArchiveDigest()throws Exception{
    var service=app(config());var resolved=FlutterBuildConfiguration.forEnvironment(service,"prod").withVersion("0.2.0-prd",22);
    ObjectNode legacy=(ObjectNode)json.readTree(FlutterBuildConfiguration.snapshot(service,"prod"));legacy.put("snapshotVersion",3).put("versionPolicy","AUTO").put("baseVersion","0.2.0").put("preBuildTaskId",91L);legacy.set("mobileBuild",json.valueToTree(resolved));
    ReleaseTask task=new ReleaseTask(1L,ModelEnums.TaskKind.BUILD,null,"prod","release20261020",null,false);task.setMobileBuildConfigSnapshot(legacy.toString());
    assertThat(FlutterBuildConfiguration.fromTask(task)).isEqualTo(resolved);assertThat(FlutterBuildConfiguration.taskPreBuildTaskId(task)).isEqualTo(91L);
    assertThat(FlutterBuildService.configHash(FlutterBuildConfiguration.fromTask(task))).isEqualTo(FlutterBuildService.configHash(resolved));
    ((ObjectNode)legacy.path("mobileBuild")).put("versionName","0.2.0");ReleaseTask tampered=new ReleaseTask(1L,ModelEnums.TaskKind.BUILD,null,"prod","release20261020",null,false);tampered.setMobileBuildConfigSnapshot(legacy.toString());
    assertThatThrownBy(()->FlutterBuildConfiguration.fromTask(tampered)).hasMessageContaining("自动 App 版本快照无效");
  }
  @Test void automaticPolicyAndCodeFloorsRequireAdministratorWhileManualEditingRemainsCompatible(){
    var manual=config();var automatic=manual.deepCopy();((ObjectNode)automatic.path("mobileBuild")).put("versionPolicy","AUTO");
    assertThatThrownBy(()->FlutterBuildConfiguration.validateExternalFloors(manual.toString(),automatic.toString(),false)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    FlutterBuildConfiguration.validateExternalFloors(manual.toString(),automatic.toString(),true);
    var altered=automatic.deepCopy();profile(altered,"test").put("versionCode",21);assertThatThrownBy(()->FlutterBuildConfiguration.validateExternalFloors(automatic.toString(),altered.toString(),false)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    FlutterBuildConfiguration.validateExternalFloors(automatic.toString(),altered.toString(),true);
    var external=altered.deepCopy();profile(external,"test").put("externalVersionCodeFloor",40);assertThatThrownBy(()->FlutterBuildConfiguration.validateExternalFloors(altered.toString(),external.toString(),false)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);FlutterBuildConfiguration.validateExternalFloors(altered.toString(),external.toString(),true);
    assertThatThrownBy(()->FlutterBuildConfiguration.validateExternalFloors(external.toString(),altered.toString(),true)).hasMessageContaining("不能调低");
    var manualUpdate=manual.deepCopy();profile(manualUpdate,"test").put("versionCode",22);FlutterBuildConfiguration.validateExternalFloors(manual.toString(),manualUpdate.toString(),false);
    profile(external,"test").put("externalVersionCodeFloor",2100000001L);assertThatThrownBy(()->FlutterBuildConfiguration.parse(external.toString())).hasMessageContaining("2100000000");
  }
}
