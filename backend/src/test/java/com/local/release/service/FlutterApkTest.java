package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No Flutter/application build: small ZIP fixtures plus installed Android verification tools. */
class FlutterApkTest {
  @TempDir Path dir;
  static final String CERT="ab".repeat(32),COMMIT="a".repeat(40);
  FlutterBuildService builder;AppService app;ReleaseTask task;Path source;
  ArtifactRepository artifacts;MobileArtifactRepository mobile;
  @BeforeEach void setup()throws Exception{
    artifacts=mock(ArtifactRepository.class);mobile=mock(MobileArtifactRepository.class);
    builder=spy(new FlutterBuildService(artifacts,mobile,mock(ReleaseTaskRepository.class),mock(BuildStorageService.class)));
    builder.artifactRoot=dir.resolve("archive").toString();builder.workspaceRoot=dir.resolve("workspaces").toString();
    source=Files.createDirectories(dir.resolve("workspaces/8/source"));
    app=new AppService();ReflectionTestUtils.setField(app,"id",5L);app.setServiceType(ServiceType.FLUTTER_ANDROID);
    app.setConfigJson(FlutterBuildConfigurationTest.json(",\"outputFormat\":\"APK\",\"flavor\":\"production\",\"target\":\"lib/main_prod.dart\",\"versionName\":\"1.0\",\"versionCode\":9"));
    task=new ReleaseTask(5L,TaskKind.BUILD,null,"build","release/1",null,false);task.setDeliveryType("MOBILE");task.setCommitId(COMMIT);
    ReflectionTestUtils.setField(task,"id",8L);ReflectionTestUtils.setField(task,"createdAt",LocalDateTime.of(2026,10,16,9,0));task.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.snapshot(app));
  }
  @Test void apkBuildUsesSingleReleaseOutputAndSnapshotDespiteChangedService()throws Exception{
    builder.flutterRoot=dir.resolve("flutter").toString();builder.javaHome=dir.resolve("jdk").toString();builder.androidSdk=Files.createDirectory(dir.resolve("sdk")).toString();
    for(Path executable:List.of(Path.of(builder.flutterRoot,"3.35.4/bin/flutter"),Path.of(builder.javaHome,"bin/java"))){Files.createDirectories(executable.getParent());Files.writeString(executable,"");}
    Files.writeString(source.resolve("pubspec.yaml"),"name: test\n");Files.createDirectories(source.resolve("lib"));Files.writeString(source.resolve("lib/main_prod.dart"),"void main() {}\n");
    Path output=source.resolve("build/app/outputs/flutter-apk/app-production-release.apk");Files.createDirectories(output.getParent());Files.writeString(output,"old");
    app.setConfigJson(FlutterBuildConfigurationTest.json(""));app.setWorkDirectory("changed-after-submission");
    List<List<String>> commands=new ArrayList<>();
    doAnswer(call->{List<String> cmd=call.getArgument(0);commands.add(List.copyOf(cmd));if(cmd.getFirst().equals("git"))return COMMIT;if(cmd.contains("--machine"))return "{\"frameworkVersion\":\"3.35.4\"}";
      assertThat(output).doesNotExist();assertThat((Path)call.getArgument(1)).isEqualTo(source);Files.writeString(output,"new apk");return "built";
    }).when(builder).run(anyList(),any(),anyLong(),nullable(Consumer.class),anyMap(),anyList());
    builder.execute(StepType.BUILD_APK,app,task,source,null);
    assertThat(commands.getLast()).contains("build","apk","--release","--flavor","production","--target","lib/main_prod.dart","--build-number=9").doesNotContain("--split-per-abi","appbundle","docker");
    assertThatThrownBy(()->builder.execute(StepType.BUILD_AAB,app,task,source,null)).hasMessageContaining("锁定格式不一致");
  }
  @Test void apkArchiveMetadataAndRetryUseLockedFormatAndFlavor()throws Exception{
    var config=FlutterBuildConfiguration.fromTask(task);Path output=source.resolve(config.outputPath());Files.createDirectories(output.getParent());Files.writeString(output,"apk fixture");
    doReturn(new FlutterBuildService.Validated("com.example.app","1.0",9,FlutterBuildService.sha256(output),Files.size(output),CERT)).when(builder).validate(any(),any(),any());
    builder.execute(StepType.ARCHIVE_ARTIFACT,app,task,source,null);
    app.setConfigJson(FlutterBuildConfigurationTest.json(",\"flavor\":\"development\""));
    when(artifacts.saveAndFlush(any())).thenAnswer(call->{Artifact value=call.getArgument(0);ReflectionTestUtils.setField(value,"id",12L);return value;});
    builder.persistArtifact(app,task,"release-a-b8");var capture=org.mockito.ArgumentCaptor.forClass(MobileArtifact.class);verify(mobile).saveAndFlush(capture.capture());
    var file=capture.getValue();assertThat(file.getFormat()).isEqualTo("APK");assertThat(file.getFlavor()).isEqualTo("production");assertThat(file.getTarget()).isEqualTo("lib/main_prod.dart");assertThat(file.getArchivePath()).endsWith("app.apk");
    when(mobile.findByArtifactId(12L)).thenReturn(Optional.of(file));Files.delete(output);assertThat(Files.readString(builder.verifiedFile(12L))).isEqualTo("apk fixture");
  }
  @Test void rejectsMissingWrongMultipleAndDebugSigners()throws Exception{
    String good="Verifies\nSigner #1 certificate DN: CN=Release Key, O=Example\nSigner #1 certificate SHA-256 digest: "+CERT+"\n";
    FlutterBuildService.verifyApkCertificateOutput(good,CERT);
    for(String output:List.of("",good.replace(CERT,"ff".repeat(32)),good+"Signer #2 certificate SHA-256 digest: "+CERT+"\n",good.replace("Release Key","Android Debug")))
      assertThatThrownBy(()->FlutterBuildService.verifyApkCertificateOutput(output,CERT)).isInstanceOf(Exception.class);
  }
  @Test void refusesSplitDebuggableAndInvalidApkContainers()throws Exception{
    String manifest=manifest("false");FlutterBuildService.readManifest(manifest,true);
    for(String xml:List.of(manifest.replace("debuggable=\"false\"","debuggable=\"true\""),manifest.replace("<manifest ","<manifest split=\"config.arm64\" "),manifest.replace("<application ","<application android:isSplitRequired=\"true\" ")))
      assertThatThrownBy(()->FlutterBuildService.readManifest(xml,true)).isInstanceOf(IllegalStateException.class);
    Path invalid=dir.resolve("invalid.apk");Files.writeString(invalid,"not apk");assertThatThrownBy(()->FlutterBuildService.verifyApkStructure(invalid)).isInstanceOf(Exception.class);
    try(JarOutputStream zip=new JarOutputStream(Files.newOutputStream(invalid))){for(String name:List.of("AndroidManifest.xml","classes.dex","../bad")){zip.putNextEntry(new JarEntry(name));zip.write(1);zip.closeEntry();}}
    assertThatThrownBy(()->FlutterBuildService.verifyApkStructure(invalid)).hasMessageContaining("非法");
  }
  @Test void realAndroidToolsVerifyV2SignatureAndMetadataAndRejectUnsignedWrongCertTamperingAndDebug()throws Exception{
    String configured=System.getProperty("android.test.sdk",System.getenv().getOrDefault("ANDROID_SDK_ROOT",""));
    Assumptions.assumeFalse(configured.isBlank());
    Path sdk=Path.of(configured);
    boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");Path tools=sdk.resolve("build-tools/35.0.0"),platform=sdk.resolve("platforms/android-35/android.jar");
    Path aapt=tools.resolve(windows?"aapt2.exe":"aapt2"),signer=tools.resolve(windows?"apksigner.bat":"apksigner"),analyzer=sdk.resolve("cmdline-tools/latest/bin/"+(windows?"apkanalyzer.bat":"apkanalyzer"));
    Assumptions.assumeTrue(Files.isRegularFile(aapt)&&Files.isRegularFile(signer)&&Files.isRegularFile(analyzer)&&Files.isRegularFile(platform),"Actual APK fixture test requires installed Android SDK 35 tools (android.test.sdk)");
    builder.javaHome=System.getProperty("java.home");builder.androidSdk=sdk.toString();builder.apksignerPath=signer.toString();builder.apkanalyzerPath=analyzer.toString();
    Path xml=dir.resolve("AndroidManifest.xml"),base=dir.resolve("base.apk"),unsigned=dir.resolve("unsigned.apk"),signed=dir.resolve("signed.apk"),key=dir.resolve("fixture.p12"),der=dir.resolve("cert.der");
    Files.writeString(xml,manifest("false"));command(List.of(aapt.toString(),"link","--manifest",xml.toString(),"-I",platform.toString(),"-o",base.toString()));copyWithDex(base,unsigned,false);
    Path jdk=Path.of(System.getProperty("java.home"),"bin");
    command(List.of(jdk.resolve("keytool").toString(),"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","1","-dname","CN=Local APK Test","-keystore",key.toString(),"-storepass","test-only-123","-keypass","test-only-123","-noprompt"));
    command(List.of(jdk.resolve("keytool").toString(),"-exportcert","-alias","test","-keystore",key.toString(),"-storepass","test-only-123","-file",der.toString()));
    String fingerprint;try(InputStream in=Files.newInputStream(der)){fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CertificateFactory.getInstance("X.509").generateCertificate(in).getEncoded()));}
    var config=FlutterBuildConfiguration.parse(FlutterBuildConfigurationTest.json(",\"outputFormat\":\"APK\",\"versionName\":\"1.0\",\"versionCode\":9").replace(CERT,fingerprint));
    assertThatThrownBy(()->builder.validate(config,unsigned,dir)).hasMessageContaining("命令退出码");
    signApk(tools,key,unsigned,signed);var validated=builder.validate(config,signed,dir);
    assertThat(validated.certificateSha256()).isEqualTo(fingerprint);assertThat(validated.applicationId()).isEqualTo("com.example.app");assertThat(validated.versionCode()).isEqualTo(9);assertThat(validated.versionName()).isEqualTo("1.0");
    assertThatThrownBy(()->builder.validate(FlutterBuildConfiguration.fromTask(task),signed,dir)).hasMessageContaining("指纹不匹配");
    Path tampered=dir.resolve("tampered.apk");copyWithDex(signed,tampered,true);assertThatThrownBy(()->builder.validate(config,tampered,dir)).hasMessageContaining("命令退出码");
    Files.writeString(xml,manifest("true"));Path debugBase=dir.resolve("debug-base.apk"),debugUnsigned=dir.resolve("debug-unsigned.apk"),debug=dir.resolve("debug.apk");
    command(List.of(aapt.toString(),"link","--manifest",xml.toString(),"-I",platform.toString(),"-o",debugBase.toString()));copyWithDex(debugBase,debugUnsigned,false);signApk(tools,key,debugUnsigned,debug);
    assertThatThrownBy(()->builder.validate(config,debug,dir)).hasMessageContaining("不可调试");
    // PRE accepts only its explicitly registered existing debug identity, never a different machine's debug key.
    Path debugKey=dir.resolve("debug-identity.p12"),debugCert=dir.resolve("debug-identity.der"),pre=dir.resolve("pre.apk");
    command(List.of(jdk.resolve("keytool").toString(),"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","1","-dname","CN=Android Debug,O=Fixture,C=US","-keystore",debugKey.toString(),"-storepass","test-only-123","-keypass","test-only-123","-noprompt"));
    command(List.of(jdk.resolve("keytool").toString(),"-exportcert","-alias","test","-keystore",debugKey.toString(),"-storepass","test-only-123","-file",debugCert.toString()));
    String preFingerprint;try(InputStream in=Files.newInputStream(debugCert)){preFingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CertificateFactory.getInstance("X.509").generateCertificate(in).getEncoded()));}
    var preConfig=new FlutterBuildConfiguration(config.flutterVersion(),null,null,config.applicationId(),config.versionName(),config.versionCode(),preFingerprint,"pre-existing",600,config.aabPath(),"APK",config.apkPath(),"test","DEBUG",true,Map.of(),Map.of());
    signApk(tools,debugKey,debugUnsigned,pre);assertThat(builder.validate(preConfig,pre,dir).certificateSha256()).isEqualTo(preFingerprint);
    assertThatThrownBy(()->builder.validate(preConfig,debug,dir)).hasMessageContaining("指纹不匹配");
    var production=new FlutterBuildConfiguration(config.flutterVersion(),null,null,config.applicationId(),config.versionName(),config.versionCode(),preFingerprint,"prod",600,config.aabPath(),"APK",config.apkPath(),"prod","RELEASE",false,Map.of(),Map.of());
    assertThatThrownBy(()->builder.validate(production,pre,dir)).hasMessageContaining("Debug");
  }
  private String manifest(String debug){return "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.example.app\" android:versionCode=\"9\" android:versionName=\"1.0\"><uses-sdk android:minSdkVersion=\"24\" android:targetSdkVersion=\"35\"/><application android:label=\"Fixture\" android:debuggable=\""+debug+"\"/></manifest>";}
  private void copyWithDex(Path input,Path output,boolean tamper)throws Exception{
    try(JarFile original=new JarFile(input.toFile());JarOutputStream zip=new JarOutputStream(Files.newOutputStream(output))){var entries=original.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();if(entry.getName().equals("classes.dex"))continue;zip.putNextEntry(new JarEntry(entry.getName()));if(!entry.isDirectory())try(var in=original.getInputStream(entry)){in.transferTo(zip);}zip.closeEntry();}zip.putNextEntry(new JarEntry("classes.dex"));zip.write((tamper?"changed fixture":"fixture dex contents").getBytes());zip.closeEntry();}
  }
  private void signApk(Path tools,Path key,Path input,Path output)throws Exception{
    command(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-jar",tools.resolve("lib/apksigner.jar").toString(),"sign","--ks",key.toString(),"--ks-key-alias","test","--ks-pass","pass:test-only-123","--v1-signing-enabled","false","--v2-signing-enabled","true","--v3-signing-enabled","true","--v4-signing-enabled","false","--out",output.toString(),input.toString()));
  }
  private String command(List<String> command)throws Exception{return BoundedProcessRunner.run(command,dir,null,60,null);}
}
