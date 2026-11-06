package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.*;
import java.util.function.Consumer;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlutterBuildServiceTest {
  @TempDir Path directory;
  ArtifactRepository artifacts=mock(ArtifactRepository.class);
  MobileArtifactRepository mobile=mock(MobileArtifactRepository.class);
  ReleaseTaskRepository tasks=mock(ReleaseTaskRepository.class);
  BuildStorageService storage=mock(BuildStorageService.class);
  FlutterBuildService builder;
  AppService app;
  ReleaseTask task;
  Path source;
  static final String COMMIT="a".repeat(40),CERT="ab".repeat(32);

  @BeforeEach void setup()throws Exception{
    builder=spy(new FlutterBuildService(artifacts,mobile,tasks,storage));builder.artifactRoot=directory.resolve("persistent").toString();
    app=new AppService();ReflectionTestUtils.setField(app,"id",5L);app.setServiceType(ServiceType.FLUTTER_ANDROID);app.setConfigJson(FlutterBuildConfigurationTest.json(""));
    task=new ReleaseTask(5L,TaskKind.BUILD,null,"build","release/1.0",null,false);ReflectionTestUtils.setField(task,"id",8L);ReflectionTestUtils.setField(task,"createdAt",LocalDateTime.of(2026,10,16,8,0));task.setCommitId(COMMIT);task.setCreatedBy("builder");
    task.setDeliveryType("MOBILE");task.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.snapshot(app));
    source=Files.createDirectories(directory.resolve("workspaces/8/source"));
  }
  @Test void archiveSurvivesWorkspaceRemovalAndRejectsTampering()throws Exception{
    prepareArchive();Artifact artifact=builder.persistArtifact(app,task,"app-b8");
    assertThat(artifact.getImageUrl()).isNull();assertThat(builder.metadata(artifact.getId()).getVersionCode()).isEqualTo(9);
    Path file=builder.verifiedFile(artifact.getId());assertThat(file).startsWith(directory.resolve("persistent"));
    Files.delete(source.resolve("build/app/outputs/bundle/release/app-release.aab"));assertThat(Files.readString(builder.verifiedFile(artifact.getId()))).isEqualTo("aab fixture");
    Files.writeString(file,"tampered");assertThatThrownBy(()->builder.verifiedFile(artifact.getId())).hasMessageContaining("摘要或大小");
  }
  @Test void persistingSameArchiveIsIdempotentAndIgnoresLaterServiceConfiguration()throws Exception{
    prepareArchive();Artifact first=builder.persistArtifact(app,task,"app-b8");Artifact second=builder.persistArtifact(app,task,"app-b8");
    assertThat(second).isSameAs(first);verify(artifacts,times(1)).saveAndFlush(any());verify(mobile,times(1)).saveAndFlush(any());
    app.setConfigJson(FlutterBuildConfigurationTest.json(",\"versionCode\":10"));
    assertThat(builder.persistArtifact(app,task,"app-b8")).isSameAs(first);assertThat(builder.metadata(first.getId()).getVersionCode()).isEqualTo(9);
  }
  @Test void taskIdReuseCannotAdoptAnOldArchive()throws Exception{
    prepareArchive();ReflectionTestUtils.setField(task,"createdAt",task.getCreatedAt().plusDays(1));
    assertThatThrownBy(()->builder.persistArtifact(app,task,"app-b8")).hasMessageContaining("当前构建任务不一致");verify(artifacts,never()).saveAndFlush(any());
  }
  @Test void artifactKeepsActualBuildFlavorAndTargetAfterServiceConfigurationChanges()throws Exception{
    app.setConfigJson(FlutterBuildConfigurationTest.json(",\"flavor\":\"production\",\"target\":\"lib/main_prod.dart\",\"aabPath\":\"build/app/outputs/bundle/release/app-release.aab\""));
    prepareArchive();Artifact artifact=builder.persistArtifact(app,task,"app-b8");
    app.setConfigJson(FlutterBuildConfigurationTest.json(",\"flavor\":\"development\",\"target\":\"lib/main_dev.dart\""));
    MobileArtifact snapshot=builder.metadata(artifact.getId());
    assertThat(snapshot.getFlavor()).isEqualTo("production");assertThat(snapshot.getTarget()).isEqualTo("lib/main_prod.dart");
    assertThat(new ObjectMapper().writeValueAsString(snapshot)).contains("\"flavor\":\"production\"","\"target\":\"lib/main_prod.dart\"");
  }
  @Test void existingArchiveWithDifferentBytesCannotBeReused()throws Exception{
    prepareArchive();Path output=source.resolve("build/app/outputs/bundle/release/app-release.aab");Files.writeString(output,"new build");
    doReturn(new FlutterBuildService.Validated("com.example.app","1.0",9,FlutterBuildService.sha256(output),Files.size(output),CERT)).when(builder).validate(any(),any(),any());
    assertThatThrownBy(()->builder.execute(StepType.ARCHIVE_ARTIFACT,app,task,source,null)).hasMessageContaining("不同的持久化产物");
  }
  @Test void cleanupRequiresCommittedSuccessAndVerifiedExternalArchive()throws Exception{
    prepareArchive();Artifact artifact=builder.persistArtifact(app,task,"app-b8");when(tasks.findById(8L)).thenReturn(Optional.of(task));
    storage.enabled=true;when(storage.validatedRoot()).thenReturn(directory.resolve("workspaces"));
    builder.cleanupAfterSuccess(8L);verify(storage,never()).removeWorkspace(any(),anyLong());
    task.setStatus(TaskStatus.SUCCESS);task.setFinishedAt(LocalDateTime.now());builder.cleanupAfterSuccess(8L);verify(storage).removeWorkspace(directory.resolve("workspaces"),8L);
    Files.writeString(builder.verifiedFile(artifact.getId()),"bad");builder.cleanupAfterSuccess(8L);verify(storage,times(1)).removeWorkspace(any(),anyLong());
  }
  @Test void validatesManifestAndBlocksXmlExternalEntities()throws Exception{
    String xml="<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.example.app\" android:versionName=\"1.2.3\" android:versionCode=\"42\"/>";
    var parsed=FlutterBuildService.readManifest(xml);assertThat(parsed.applicationId()).isEqualTo("com.example.app");assertThat(parsed.versionCode()).isEqualTo(42);
    assertThatThrownBy(()->FlutterBuildService.readManifest(xml.replace("42","0"))).hasMessageContaining("versionCode");
    assertThatThrownBy(()->FlutterBuildService.readManifest("<!DOCTYPE manifest [<!ENTITY xxe SYSTEM 'file:///never-read'>]>"+xml)).isInstanceOf(Exception.class);
  }
  @Test void rejectsUnsignedOrIncorrectlySignedBundleAndAcceptsExpectedCertificate()throws Exception{
    Path bundle=directory.resolve("fixture.aab");writeBundle(bundle);
    assertThatThrownBy(()->FlutterBuildService.verifySignedBundle(bundle,CERT)).hasMessageContaining("未签名");
    String fingerprint=sign(bundle);
    FlutterBuildService.verifySignedBundle(bundle,fingerprint);
    assertThatThrownBy(()->FlutterBuildService.verifySignedBundle(bundle,CERT)).hasMessageContaining("指纹不匹配");
    Path tampered=directory.resolve("tampered.aab");
    try(JarFile original=new JarFile(bundle.toFile());JarOutputStream output=new JarOutputStream(Files.newOutputStream(tampered))){
      var entries=original.entries();while(entries.hasMoreElements()){JarEntry entry=entries.nextElement();output.putNextEntry(new JarEntry(entry.getName()));if(!entry.isDirectory())output.write(entry.getName().equals("BundleConfig.pb")?"bad".getBytes():original.getInputStream(entry).readAllBytes());output.closeEntry();}
    }
    assertThatThrownBy(()->FlutterBuildService.verifySignedBundle(tampered,fingerprint)).hasMessageContaining("签名验证失败");
  }
  @Test void rejectsTraversalAndDoesNotExposeStoredPaths()throws Exception{
    assertThatThrownBy(()->FlutterBuildService.outputFile(source,"../../secret.aab")).isInstanceOf(IllegalArgumentException.class);
    MobileArtifact file=new MobileArtifact(1L,8L,5L,"com.example.app","1.0",9,CERT,3,CERT,COMMIT,"3.35.4","/private/storage/bundle.aab");
    assertThat(new ObjectMapper().writeValueAsString(file)).doesNotContain("archivePath","private/storage");
  }
  @Test void androidEnvironmentDoesNotInheritPlatformJavaHeapOrPrintPasswords(){
    var env=FlutterBuildService.buildEnvironment(Path.of("jdk"),Path.of("sdk"),Path.of("flutter"));
    assertThat(env).containsEntry("JAVA_TOOL_OPTIONS",null).containsEntry("_JAVA_OPTIONS",null).containsEntry("ANDROID_HOME","sdk");
    assertThat(env.get("GRADLE_OPTS")).contains("workers.max=1");assertThat(FlutterBuildService.redact("secret-one and secret-two",List.of("secret-one","secret-two"))).isEqualTo("[REDACTED] and [REDACTED]");
  }
  @Test void buildUsesPinnedToolsAndArgumentsAndRemovesStaleOutput()throws Exception{
    prepareTools();app.setConfigJson(FlutterBuildConfigurationTest.json(",\"versionName\":\"2.0.0\",\"versionCode\":99,\"buildTimeoutSeconds\":700"));
    snapshot();
    Path output=source.resolve("build/app/outputs/bundle/release/app-release.aab");Files.createDirectories(output.getParent());Files.writeString(output,"old package");
    List<List<String>> commands=new ArrayList<>();
    doAnswer(invocation->{List<String> cmd=invocation.getArgument(0);commands.add(List.copyOf(cmd));
      if(cmd.getFirst().equals("git"))return COMMIT;
      if(cmd.contains("--machine"))return "{\"frameworkVersion\":\"3.35.4\"}";
      assertThat(output).doesNotExist();assertThat((long)invocation.getArgument(2)).isEqualTo(700);Files.writeString(output,"new package");return "done";
    }).when(builder).run(anyList(),any(),anyLong(),nullable(Consumer.class),anyMap(),anyList());
    builder.execute(StepType.BUILD_AAB,app,task,source,null);
    assertThat(commands.getLast()).contains("build","appbundle","--release","--build-name=2.0.0","--build-number=99").doesNotContain("docker");
    assertThat(Files.readString(output)).isEqualTo("new package");
  }
  @Test void buildRejectsWrongCheckoutOrToolVersionBeforeInvokingBuild()throws Exception{
    prepareTools();
    doReturn("b".repeat(40)).when(builder).run(anyList(),any(),anyLong(),nullable(Consumer.class),anyMap(),anyList());
    assertThatThrownBy(()->builder.execute(StepType.BUILD_AAB,app,task,source,null)).hasMessageContaining("Commit");
    doAnswer(invocation->{List<String> cmd=invocation.getArgument(0);return cmd.getFirst().equals("git")?COMMIT:"{\"frameworkVersion\":\"9.9.9\"}";}).when(builder).run(anyList(),any(),anyLong(),nullable(Consumer.class),anyMap(),anyList());
    assertThatThrownBy(()->builder.execute(StepType.BUILD_AAB,app,task,source,null)).hasMessageContaining("工具链版本");
  }
  @Test void interruptedSigningInjectionRecoversOnlyExactOwnedContent()throws Exception{
    Path destination=prepareSigningMarker("private signing test");
    doReturn("").when(builder).run(anyList(),any(),anyLong(),nullable(Consumer.class),anyMap(),anyList());
    builder.recoverSigningInjection(task,source);
    assertThat(destination).doesNotExist();assertThat(builder.signingMarkerPath(task)).doesNotExist();
  }
  @Test void signingRecoveryRefusesTrackedOrModifiedFiles()throws Exception{
    Path destination=prepareSigningMarker("private signing test");
    doReturn("android/key.properties").when(builder).run(anyList(),any(),anyLong(),nullable(Consumer.class),anyMap(),anyList());
    assertThatThrownBy(()->builder.recoverSigningInjection(task,source)).hasMessageContaining("仓库跟踪");assertThat(destination).exists();
    doReturn("").when(builder).run(anyList(),any(),anyLong(),nullable(Consumer.class),anyMap(),anyList());Files.writeString(destination,"source owner edit");
    assertThatThrownBy(()->builder.recoverSigningInjection(task,source)).hasMessageContaining("内容已改变");assertThat(Files.readString(destination)).isEqualTo("source owner edit");assertThat(builder.signingMarkerPath(task)).exists();
  }
  @Test void signingRecoveryRefusesReusedTaskIdentity()throws Exception{
    Path destination=prepareSigningMarker("private signing test");ReflectionTestUtils.setField(task,"createdAt",task.getCreatedAt().plusSeconds(1));
    assertThatThrownBy(()->builder.recoverSigningInjection(task,source)).hasMessageContaining("当前任务不一致");assertThat(destination).exists();
  }

  private Path prepareSigningMarker(String content)throws Exception{
    Path destination=source.resolve("android/key.properties");Files.createDirectories(destination.getParent());Files.writeString(destination,content);
    Path marker=builder.signingMarkerPath(task);Files.createDirectories(marker.getParent());
    var record=new FlutterBuildService.SigningMarker(task.getId(),task.getCreatedAt().toString(),task.getCommitId(),source.toAbsolutePath().normalize().toString(),".release-signing-"+UUID.randomUUID()+".tmp",FlutterBuildService.sha256(destination));
    Files.write(marker,new ObjectMapper().writeValueAsBytes(record));return destination;
  }

  private void prepareTools()throws Exception{
    builder.flutterRoot=directory.resolve("flutter").toString();builder.javaHome=directory.resolve("jdk").toString();builder.androidSdk=Files.createDirectories(directory.resolve("sdk")).toString();
    Path flutter=Path.of(builder.flutterRoot,"3.35.4/bin/flutter"),java=Path.of(builder.javaHome,"bin/java");
    Files.createDirectories(flutter.getParent());Files.createDirectories(java.getParent());Files.writeString(flutter,"");Files.writeString(java,"");Files.writeString(source.resolve("pubspec.yaml"),"name: example\n");
  }

  private void prepareArchive()throws Exception{
    snapshot();
    Path output=source.resolve("build/app/outputs/bundle/release/app-release.aab");Files.createDirectories(output.getParent());Files.writeString(output,"aab fixture");
    doReturn(new FlutterBuildService.Validated("com.example.app","1.0",9,FlutterBuildService.sha256(output),Files.size(output),CERT)).when(builder).validate(any(),any(),any());
    builder.execute(StepType.ARCHIVE_ARTIFACT,app,task,source,null);
    AtomicReference<Artifact> savedArtifact=new AtomicReference<>();AtomicReference<MobileArtifact> savedMobile=new AtomicReference<>();
    when(artifacts.findByBuildTaskId(8L)).thenAnswer(i->Optional.ofNullable(savedArtifact.get()));
    when(artifacts.saveAndFlush(any())).thenAnswer(i->{Artifact value=i.getArgument(0);ReflectionTestUtils.setField(value,"id",12L);savedArtifact.set(value);return value;});
    when(mobile.findByBuildTaskId(8L)).thenAnswer(i->Optional.ofNullable(savedMobile.get()));
    when(mobile.findByArtifactId(12L)).thenAnswer(i->Optional.ofNullable(savedMobile.get()));
    when(mobile.saveAndFlush(any())).thenAnswer(i->{MobileArtifact value=i.getArgument(0);savedMobile.set(value);return value;});
  }
  private void snapshot(){ReflectionTestUtils.setField(task,"mobileBuildConfigSnapshot",FlutterBuildConfiguration.snapshot(app));}
  private static void writeBundle(Path file)throws IOException{try(JarOutputStream zip=new JarOutputStream(Files.newOutputStream(file))){for(String name:List.of("BundleConfig.pb","base/manifest/AndroidManifest.xml","base/dex/classes.dex")){zip.putNextEntry(new JarEntry(name));zip.write("test payload".getBytes());zip.closeEntry();}}}
  private String sign(Path bundle)throws Exception{
    Path jdk=Path.of(System.getProperty("java.home"),"bin"),key=directory.resolve("fixture.p12"),cert=directory.resolve("cert.der");
    command(List.of(jdk.resolve("keytool").toString(),"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","1","-dname","CN=Local Test","-keystore",key.toString(),"-storepass","test-only-123","-keypass","test-only-123","-noprompt"));
    command(List.of(jdk.resolve("jarsigner").toString(),"-keystore",key.toString(),"-storepass","test-only-123",bundle.toString(),"test"));
    command(List.of(jdk.resolve("keytool").toString(),"-exportcert","-alias","test","-keystore",key.toString(),"-storepass","test-only-123","-file",cert.toString()));
    try(InputStream in=Files.newInputStream(cert)){return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CertificateFactory.getInstance("X.509").generateCertificate(in).getEncoded()));}
  }
  private void command(List<String> cmd)throws Exception{BoundedProcessRunner.run(cmd,directory,null,30,null);}
}
