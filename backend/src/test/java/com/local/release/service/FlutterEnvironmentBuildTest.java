package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.repo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlutterEnvironmentBuildTest {
  @TempDir Path dir;
  static final String CERT="ab".repeat(32),COMMIT="a".repeat(40);
  static final ObjectMapper JSON=new ObjectMapper();
  FlutterBuildService builder;AppService app;ReleaseTask task;Path source;
  @BeforeEach void setup()throws Exception{
    builder=spy(new FlutterBuildService(mock(ArtifactRepository.class),mock(MobileArtifactRepository.class),mock(ReleaseTaskRepository.class),mock(BuildStorageService.class)));
    builder.artifactRoot=dir.resolve("archive").toString();source=Files.createDirectories(dir.resolve("source"));
    app=new AppService();ReflectionTestUtils.setField(app,"id",5L);app.setServiceType(ServiceType.FLUTTER_ANDROID);
    app.setConfigJson("{\"mobileBuild\":{\"flutterVersion\":\"3.47.3\",\"outputFormat\":\"APK\",\"environments\":{\"test\":{\"enabled\":true,\"applicationId\":\"com.example.app.pre\",\"expectedCertificateSha256\":\""+CERT+"\",\"signingProfile\":\"pre-signing\",\"buildMode\":\"DEBUG\",\"allowDebugSigning\":true,\"dartDefines\":{\"LABEL\":\"two words & $(no-shell)\"},\"environmentVariables\":{\"APP_BUILD_FEATURE\":\"false\"}}}}}");
    task=new ReleaseTask(5L,TaskKind.BUILD,null,"test","release/1",null,false);ReflectionTestUtils.setField(task,"id",8L);ReflectionTestUtils.setField(task,"createdAt",LocalDateTime.of(2026,10,17,8,0));task.setCommitId(COMMIT);task.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.snapshot(app,"test"));
  }
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void buildUsesLockedEnvironmentModeAndLiteralVersionArguments(boolean production)throws Exception{
    builder.flutterRoot=dir.resolve("flutter").toString();builder.androidSdk=Files.createDirectory(dir.resolve("sdk")).toString();builder.javaHome=dir.resolve("jdk").toString();
    for(Path p:List.of(Path.of(builder.flutterRoot,"3.47.3/bin/flutter"),Path.of(builder.javaHome,"bin/java"))){Files.createDirectories(p.getParent());Files.writeString(p,"");}
    Files.writeString(source.resolve("pubspec.yaml"),"name: fixture");
    var assigned=FlutterBuildConfiguration.fromTask(task).withVersion("0.1.1-pre",22);
    if(production)assigned=new FlutterBuildConfiguration(assigned.flutterVersion(),assigned.flavor(),assigned.target(),"com.example.app","0.0.1",23L,CERT,"prod-signing",assigned.buildTimeoutSeconds(),assigned.aabPath(),"APK","build/app/outputs/flutter-apk/app-release.apk","prod","RELEASE",false,assigned.dartDefines(),assigned.environmentVariables());
    String envName=production?"prod":"test",name=assigned.versionName();long code=assigned.versionCode();
    ReleaseTask versioned=new ReleaseTask(5L,TaskKind.BUILD,null,envName,"release/1",null,false);ReflectionTestUtils.setField(versioned,"id",8L);ReflectionTestUtils.setField(versioned,"createdAt",task.getCreatedAt());versioned.setCommitId(COMMIT);versioned.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.assignedSnapshot(app,assigned,production?"0.0.1":"0.1.1",production?91L:null));task=versioned;
    var config=FlutterBuildConfiguration.fromTask(task);Path output=source.resolve(config.outputPath());Files.createDirectories(output.getParent());Files.writeString(output,"stale");
    doReturn(builder.new SigningInjection(null,task,source,List.of())).when(builder).injectSigning(any(),any(),any());
    List<String> logs=new ArrayList<>();List<List<String>> commands=new ArrayList<>();
    doAnswer(call->{List<String> command=call.getArgument(0);commands.add(command);if(command.getFirst().equals("git"))return COMMIT;if(command.contains("--machine"))return "{\"frameworkVersion\":\"3.47.3\"}";
      assertThat(output).doesNotExist();Map<String,String> env=call.getArgument(4);assertThat(env).containsEntry("APP_BUILD_FEATURE","false").containsEntry("RELEASE_SIGNING_PROPERTIES",source.resolve("android/key.properties").toString());
      assertThat(env).containsEntry("ORG_GRADLE_PROJECT_android.injected.signing.store.password",null);Files.writeString(output,"fixture apk");return "built";
    }).when(builder).run(anyList(),any(),anyLong(),nullable(Consumer.class),anyMap(),anyList());
    app.setConfigJson("{}");builder.execute(StepType.BUILD_APK,app,task,source,logs::add);
    assertThat(commands.getLast()).contains("apk",production?"--release":"--debug","--build-name="+name,"--build-number="+code,"--dart-define=LABEL=two words & $(no-shell)").doesNotContain(production?"--debug":"--release","sh","bash");
    assertThat(logs).anyMatch(line->line.contains(production?"RELEASE":"DEBUG")&&line.contains(envName));
  }
  @Test void rejectsProductionDebugEvenWhenAnInternalCallerBypassesConfigurationParser(){
    FlutterBuildService.validateBuildPolicy(config("test","DEBUG",true,"APK"));
    for(var config:List.of(config("prod","DEBUG",true,"APK"),config("prod","RELEASE",true,"APK"),config("test","DEBUG",false,"APK"),config("test","DEBUG",true,"AAB"),config(null,"DEBUG",true,"APK")))
      assertThatThrownBy(()->FlutterBuildService.validateBuildPolicy(config)).isInstanceOf(IllegalStateException.class);
    assertThat(FlutterBuildService.allowsDebugCertificate(config("test","DEBUG",true,"APK"))).isTrue();
    assertThat(FlutterBuildService.allowsDebugCertificate(config("prod","RELEASE",false,"APK"))).isFalse();
  }
  @Test void debugCertificateRequiresExactFingerprintAndDebugManifestMode()throws Exception{
    String output="Signer #1 certificate DN: CN=Android Debug,O=Fixture\nSigner #1 certificate SHA-256 digest: "+CERT+"\n";
    FlutterBuildService.verifyApkCertificateOutput(output,CERT,true);
    assertThatThrownBy(()->FlutterBuildService.verifyApkCertificateOutput(output,"cd".repeat(32),true)).hasMessageContaining("指纹不匹配");
    assertThatThrownBy(()->FlutterBuildService.verifyApkCertificateOutput(output,CERT,false)).hasMessageContaining("Debug");
    String xml="<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.example.app.pre\" android:versionName=\"1.0\" android:versionCode=\"1\"><application android:debuggable=\"true\"/></manifest>";
    FlutterBuildService.readManifest(xml,true,true);
    assertThatThrownBy(()->FlutterBuildService.readManifest(xml,true,false)).hasMessageContaining("可调试");
    assertThatThrownBy(()->FlutterBuildService.readManifest(xml.replace("debuggable=\"true\"","debuggable=\"false\""),true,true)).hasMessageContaining("Debug 构建模式");
  }
  @Test void signingPropertiesPreserveOriginalBytesAndEscapePasswordAndUnicode()throws Exception{
    byte[] original="# preserved\r\norg.gradle.workers.max=1\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Properties signing=new Properties();signing.setProperty("storeFile","/private/测试/pre key.p12");signing.setProperty("storePassword","colon: equals= slash\\ 中文\nline");signing.setProperty("keyPassword","leading & $(no-shell)");signing.setProperty("keyAlias","pre");
    byte[] injected=FlutterBuildService.gradleSigningBytes(original,signing);
    assertThat(Arrays.copyOf(injected,original.length)).isEqualTo(original);assertThat(FlutterBuildService.gradleSigningBytes(original,signing)).isEqualTo(injected);
    Properties parsed=new Properties();parsed.load(new ByteArrayInputStream(injected));
    assertThat(parsed.getProperty("android.injected.signing.store.file")).isEqualTo(signing.getProperty("storeFile"));
    assertThat(parsed.getProperty("android.injected.signing.store.password")).isEqualTo(signing.getProperty("storePassword"));
    assertThat(parsed.getProperty("android.injected.signing.key.password")).isEqualTo(signing.getProperty("keyPassword"));
    assertThat(parsed.getProperty("org.gradle.workers.max")).isEqualTo("1");
  }
  @Test void recoveryRestoresOriginalGradleBytesAndModeThenRemovesOnlyOwnedSecrets()throws Exception{
    byte[] original="# user settings\r\norg.gradle.jvmargs=-Xmx768m\r\n".getBytes();Path gradle=prepareRecovery(original,true);
    builder.recoverSigningInjection(task,source);
    assertThat(Files.readAllBytes(gradle)).isEqualTo(original);assertThat(source.resolve("android/key.properties")).doesNotExist();assertThat(builder.signingMarkerPath(task)).doesNotExist();
    if(Files.getFileStore(gradle).supportsFileAttributeView("posix"))assertThat(Files.getPosixFilePermissions(gradle)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.GROUP_READ);
  }
  @Test void recoveryDeletesNewOwnedGradleFileButPreservesUnexpectedUserEdits()throws Exception{
    Path gradle=prepareRecovery(new byte[0],false);Files.writeString(gradle,"someone edited");
    assertThatThrownBy(()->builder.recoverSigningInjection(task,source)).hasMessageContaining("内容已改变");
    assertThat(source.resolve("android/key.properties")).exists();assertThat(Files.readString(gradle)).isEqualTo("someone edited");
    gradle=prepareRecovery(new byte[0],false);builder.recoverSigningInjection(task,source);assertThat(gradle).doesNotExist();
  }
  @Test void interruptedBuildStillRestoresSigningFilesAndPreservesInterruptFlag()throws Exception{
    byte[] original="org.gradle.workers.max=1\n".getBytes();Path gradle=prepareRecovery(original,true);
    doAnswer(call->{assertThat(Thread.currentThread().isInterrupted()).isFalse();return "";}).when(builder).run(anyList(),any(),anyLong(),nullable(Consumer.class),anyMap(),anyList());
    Thread.currentThread().interrupt();
    try{
      builder.new SigningInjection(builder.signingMarkerPath(task),task,source,List.of()).close();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(Files.readAllBytes(gradle)).isEqualTo(original);assertThat(source.resolve("android/key.properties")).doesNotExist();
    }finally{Thread.interrupted();}
  }
  @Test void configDigestKeepsLegacyArchivesCompatibleAndLocksNewEnvironment()throws Exception{
    var old=FlutterBuildConfiguration.parse(FlutterBuildConfigurationTest.json(""));
    String legacy="{\"flutterVersion\":\"3.35.4\",\"flavor\":null,\"target\":null,\"applicationId\":\"com.example.app\",\"versionName\":null,\"versionCode\":null,\"expectedCertificateSha256\":\""+CERT+"\",\"signingProfile\":null,\"buildTimeoutSeconds\":600,\"aabPath\":\"build/app/outputs/bundle/release/app-release.aab\",\"outputFormat\":\"AAB\",\"apkPath\":\"build/app/outputs/flutter-apk/app-release.apk\"}";
    assertThat(FlutterBuildService.configHash(old)).isEqualTo(hash(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(FlutterBuildService.configHash(config("test","RELEASE",false,"APK"))).isNotEqualTo(FlutterBuildService.configHash(config("prod","RELEASE",false,"APK")));
  }
  private Path prepareRecovery(byte[] original,boolean existed)throws Exception{
    Path android=Files.createDirectories(source.resolve("android")),key=android.resolve("key.properties"),gradle=android.resolve("gradle.properties");Files.writeString(key,"owned fixture secrets");Files.writeString(gradle,"owned injected properties");
    Set<PosixFilePermission> mode=Files.getFileStore(android).supportsFileAttributeView("posix")?Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.GROUP_READ):null;
    String temporary=".release-signing-"+UUID.randomUUID()+".tmp";
    var g=new FlutterBuildService.GradleSigningMarker(existed,Base64.getEncoder().encodeToString(original),hash(original),FlutterBuildService.sha256(gradle),temporary+".gradle",mode);
    var marker=new FlutterBuildService.SigningMarker(task.getId(),task.getCreatedAt().toString(),COMMIT,source.toAbsolutePath().normalize().toString(),temporary,FlutterBuildService.sha256(key),g);
    Path path=builder.signingMarkerPath(task);Files.createDirectories(path.getParent());Files.write(path,JSON.writeValueAsBytes(marker));
    doReturn("").when(builder).run(anyList(),any(),anyLong(),nullable(Consumer.class),anyMap(),anyList());return gradle;
  }
  private FlutterBuildConfiguration config(String env,String mode,boolean debug,String format){return new FlutterBuildConfiguration("3.47.3",null,null,"com.example.app",null,null,CERT,"selected",600,"build/app.aab",format,"build/app.apk",env,mode,debug,Map.of(),Map.of());}
  private String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
