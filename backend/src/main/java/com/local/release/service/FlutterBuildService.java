package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.*;
import java.util.regex.*;
import java.util.zip.ZipFile;
import java.security.cert.X509Certificate;
import static com.local.release.domain.ModelEnums.StepType;

/** Android is a file-artifact pipeline; none of these steps build or push a Docker image. */
@Service
public class FlutterBuildService {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final Set<PosixFilePermission> PRIVATE_FILE=Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE);
  private static final Set<PosixFilePermission> PRIVATE_DIR=Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE);
  private final ArtifactRepository artifacts;
  private final MobileArtifactRepository mobileArtifacts;
  private final ReleaseTaskRepository tasks;
  private final BuildStorageService storage;
  private GitHubMobileBuildService cloudBuild;
  @org.springframework.beans.factory.annotation.Autowired void setCloudBuild(GitHubMobileBuildService value){cloudBuild=value;}
  @Value("${release.mobile.flutter-root:}") String flutterRoot;
  @Value("${release.mobile.android-sdk:}") String androidSdk;
  @Value("${release.mobile.java-home:}") String javaHome;
  @Value("${release.mobile.bundletool-jar:}") String bundletoolJar;
  @Value("${release.mobile.apksigner-path:}") String apksignerPath;
  @Value("${release.mobile.apkanalyzer-path:}") String apkanalyzerPath;
  @Value("${release.mobile.artifact-root:./data/mobile-artifacts}") String artifactRoot;
  @Value("${release.mobile.signing-root:}") String signingRoot;
  @Value("${release.workspace:../workspaces}") String workspaceRoot="../workspaces";
  public FlutterBuildService(ArtifactRepository artifacts,MobileArtifactRepository mobileArtifacts,ReleaseTaskRepository tasks,BuildStorageService storage){this.artifacts=artifacts;this.mobileArtifacts=mobileArtifacts;this.tasks=tasks;this.storage=storage;}

  public String execute(StepType step,AppService service,ReleaseTask task,Path source,Consumer<String> log)throws Exception{
    if(task.isSimulate())throw new IllegalArgumentException("App 构建不支持模拟产物");
    var config=FlutterBuildConfiguration.fromTask(task);
    FlutterBuildConfiguration.verifyRepository(task,service);
    Path project=projectDirectory(source,FlutterBuildConfiguration.taskWorkDirectory(task));
    if((step==StepType.BUILD_APK||step==StepType.VALIDATE_APK)&&!config.isApk()||(step==StepType.BUILD_AAB||step==StepType.VALIDATE_AAB)&&config.isApk())throw new IllegalStateException("App 构建步骤与锁定格式不一致");
    return switch(step.name()){
      case "BUILD_AAB", "BUILD_APK" -> {
        if(GitHubMobileBuildService.usesGitHub(task)){
          if(cloudBuild==null)throw new IllegalStateException("GitHub 构建服务不可用；不会自动切换本机构建");
          yield cloudBuild.build(task,config,project,log);
        }
        yield build(config,task,source,project,log);
      }
      case "VALIDATE_AAB", "VALIDATE_APK" -> {var m=validate(config,outputFile(project,config.outputPath()),project);yield config.outputFormat()+" 校验通过："+m.applicationId()+" · "+m.versionName()+" ("+m.versionCode()+") · SHA-256 "+m.sha256();}
      case "ARCHIVE_ARTIFACT" -> {archive(service,task,config,project);yield "已持久保存 "+config.outputFormat()+"，并验证文件摘要；临时工作目录清理不会删除安装包";}
      default -> throw new IllegalArgumentException("不支持的 App 构建步骤："+step);
    };
  }

  private String build(FlutterBuildConfiguration config,ReleaseTask task,Path source,Path project,Consumer<String> log)throws Exception{
    validateBuildPolicy(config);
    Path flutter=requiredPath(flutterRoot,"Flutter 安装根目录").resolve(config.flutterVersion()).resolve("bin/flutter");
    requireRegular(flutter,"固定版本 Flutter 程序");
    Path android=requiredPath(androidSdk,"Android SDK");if(!Files.isDirectory(android))throw new IllegalStateException("Android SDK 目录不存在");
    Path java=javaBinary();requireRegular(java,"Android 构建专用 JDK");
    Map<String,String> env=buildEnvironment(java.getParent().getParent(),android,flutter.getParent());
    env.putAll(config.environmentVariables());
    String actualCommit=run(List.of("git","rev-parse","HEAD"),source,30,null,env,List.of()).trim();
    if(task.getCommitId()==null||!actualCommit.equalsIgnoreCase(task.getCommitId()))throw new IllegalStateException("工作目录 Commit 与本次打包锁定版本不一致");
    String versionOutput=run(List.of(flutter.toString(),"--version","--machine"),project,60,null,env,List.of());
    String actualVersion;try{actualVersion=JSON.readTree(versionOutput.substring(versionOutput.indexOf('{'))).path("frameworkVersion").asText();}catch(Exception e){throw new IllegalStateException("无法读取 Flutter 工具链版本");}
    if(!config.flutterVersion().equals(actualVersion))throw new IllegalStateException("Flutter 工具链版本与项目固定版本不一致");
    requireRegular(project.resolve("pubspec.yaml"),"Flutter pubspec.yaml");
    Path output=project.resolve(config.outputPath()).normalize();ensureContained(project,output);rejectSymlinks(project,output);
    // An old successful output must not be mistaken for this attempt's build result.
    if(Files.exists(output,LinkOption.NOFOLLOW_LINKS)){requireRegular(output,"旧 App 输出");Files.delete(output);}
    List<String> command=new ArrayList<>(List.of(flutter.toString(),"--no-version-check","build",config.isApk()?"apk":"appbundle",config.isDebug()?"--debug":"--release"));
    if(config.flavor()!=null)command.addAll(List.of("--flavor",config.flavor()));
    if(config.target()!=null){requireRegular(outputFile(project,config.target()),"Flutter 入口文件");command.addAll(List.of("--target",config.target()));}
    if(config.versionName()!=null)command.add("--build-name="+config.versionName());
    if(config.versionCode()!=null)command.add("--build-number="+config.versionCode());
    if(log!=null&&config.versionName()!=null&&config.versionCode()!=null)log.accept("本次锁定版本："+config.versionName()+" · versionCode "+config.versionCode());
    config.dartDefines().forEach((key,value)->command.add("--dart-define="+key+"="+value));
    if(log!=null)log.accept("使用固定 Flutter "+config.flutterVersion()+" 构建 Android "+config.outputFormat()+" · "+config.buildMode()+(config.environmentName()==null?"":" · 环境 "+config.environmentName())+"；Commit "+task.getCommitId());
    try(SigningInjection signing=injectSigning(config,task,project)){
      if(config.signingProfile()!=null)env.put("RELEASE_SIGNING_PROPERTIES",project.resolve("android/key.properties").toString());
      run(command,project,config.buildTimeoutSeconds(),log,env,signing.secrets());
    }
    outputFile(project,config.outputPath());
    return "Flutter Android "+config.outputFormat()+" 构建完成";
  }

  static Map<String,String> buildEnvironment(Path javaHome,Path sdk,Path flutterBin){
    Map<String,String> env=new HashMap<>();
    for(String key:List.of("JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS","JAVA_OPTS","GRADLE_OPTS","RELEASE_SIGNING_PROPERTIES"))env.put(key,null);
    for(String key:System.getenv().keySet())if(key.startsWith("APP_BUILD_") || key.startsWith("ORG_GRADLE_PROJECT_android.injected.signing."))env.put(key,null);
    for(String key:List.of("store.file","store.password","key.alias","key.password","store.type","v1-enabled","v2-enabled"))env.put("ORG_GRADLE_PROJECT_android.injected.signing."+key,null);
    env.put("JAVA_HOME",javaHome.toString());env.put("ANDROID_HOME",sdk.toString());env.put("ANDROID_SDK_ROOT",sdk.toString());
    env.put("PATH",javaHome.resolve("bin")+File.pathSeparator+flutterBin+File.pathSeparator+Objects.toString(System.getenv("PATH"),""));
    env.put("JAVA_OPTS","-Xms128m -Xmx1536m");
    env.put("GRADLE_OPTS","-Dorg.gradle.workers.max=1 -Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx1536m");
    env.put("CI","true");env.put("FLUTTER_SUPPRESS_ANALYTICS","true");return env;
  }

  SigningInjection injectSigning(FlutterBuildConfiguration config,ReleaseTask task,Path project)throws Exception{
    Path marker=signingMarkerPath(task);
    if(config.signingProfile()==null&&!Files.exists(marker,LinkOption.NOFOLLOW_LINKS))return new SigningInjection(null,task,project,List.of());
    // Never remove a versioned source file, including when recovering an interrupted build.
    rejectTrackedSigningFile(project);
    recoverSigningInjection(task,project);
    if(config.signingProfile()==null)return new SigningInjection(null,task,project,List.of());
    Path root=requiredPath(signingRoot,"App 私有签名目录");
    Path profile=root.resolve(config.signingProfile()+".properties");rejectSymlinks(root,profile);requirePrivate(profile);
    if(Files.size(profile)>65536)throw new IllegalStateException("签名配置文件过大");
    Properties values=new Properties();try(Reader input=Files.newBufferedReader(profile)){values.load(input);}
    for(String key:List.of("storeFile","storePassword","keyPassword","keyAlias"))if(values.getProperty(key)==null||values.getProperty(key).isBlank())throw new IllegalStateException("签名配置缺少必要字段："+key);
    Path key=Path.of(values.getProperty("storeFile"));if(!key.isAbsolute())throw new IllegalStateException("签名 storeFile 必须是私有目录中的绝对路径");key=key.normalize();ensureContained(root,key);rejectSymlinks(root,key);requirePrivate(key);
    if(config.environmentName()!=null)verifySigningIdentity(config,key,values);
    Path destination=project.resolve("android/key.properties");ensureContained(project,destination);rejectSymlinks(project,destination);
    if(!Files.isDirectory(destination.getParent()))throw new IllegalStateException("项目缺少 android 目录");
    if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("仓库已有 android/key.properties，请先移除仓库中的签名信息再使用私有签名配置");
    values.setProperty("storeFile",key.toString());
    StringWriter content=new StringWriter();values.store(content,"Injected for this build only");byte[] bytes=content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String temporaryName=".release-signing-"+UUID.randomUUID()+".tmp";
    GradleSigningMarker gradle=config.environmentName()==null?null:prepareGradleSigning(project,values,temporaryName);
    SigningMarker owned=new SigningMarker(task.getId(),createdIdentity(task),task.getCommitId(),project.toAbsolutePath().normalize().toString(),temporaryName,hex(MessageDigest.getInstance("SHA-256").digest(bytes)),gradle);
    ensurePrivateDirectory(marker.getParent());atomicWrite(marker,new ByteArrayInputStream(JSON.writeValueAsBytes(owned)));
    Path temporary=destination.resolveSibling(temporaryName);
    try{
      Files.createFile(temporary,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PRIVATE_FILE));
      Files.write(temporary,bytes,StandardOpenOption.TRUNCATE_EXISTING);try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
      try{Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,destination);}
      if(gradle!=null)writeGradleSigning(project,gradle,values);
      return new SigningInjection(marker,task,project,List.of(values.getProperty("storePassword"),values.getProperty("keyPassword")));
    }catch(Exception e){try{recoverSigningInjection(task,project);}catch(Exception recovery){e.addSuppressed(recovery);}throw e;}
  }
  private void verifySigningIdentity(FlutterBuildConfiguration config,Path key,Properties values)throws Exception{
    if(Files.size(key)>10*1024*1024)throw new IllegalStateException("签名密钥文件过大");
    char[] storePassword=values.getProperty("storePassword").toCharArray(),keyPassword=values.getProperty("keyPassword").toCharArray();
    try{
      KeyStore store=KeyStore.getInstance(key.toFile(),storePassword);String alias=values.getProperty("keyAlias");
      if(!store.isKeyEntry(alias)||!(store.getKey(alias,keyPassword) instanceof PrivateKey)||!(store.getCertificate(alias) instanceof X509Certificate certificate))throw new IllegalStateException("签名配置没有对应私钥和证书");
      certificate.checkValidity();
      if(!digest(certificate.getEncoded()).equals(config.expectedCertificateSha256()))throw new IllegalStateException("签名配置证书与构建环境锁定指纹不一致");
      if(!allowsDebugCertificate(config))rejectDebugCertificate(certificate.getSubjectX500Principal().getName());
      values.setProperty("storeType",store.getType());
    }catch(IllegalStateException e){throw e;}catch(Exception e){throw new IllegalStateException("签名配置无法读取，请管理员检查密钥密码、别名和证书有效期");}
    finally{Arrays.fill(storePassword,'\0');Arrays.fill(keyPassword,'\0');}
  }
  final class SigningInjection implements AutoCloseable {
    private final Path marker;private final ReleaseTask task;private final Path project;private final List<String> secrets;
    SigningInjection(Path marker,ReleaseTask task,Path project,List<String> secrets){this.marker=marker;this.task=task;this.project=project;this.secrets=secrets;}
    List<String> secrets(){return secrets;}
    public void close()throws Exception{
      if(marker==null)return;
      // The process runner preserves interruption; cleanup's bounded git check must still be allowed to execute.
      boolean interrupted=Thread.interrupted();
      try{recoverSigningInjection(task,project);}finally{if(interrupted)Thread.currentThread().interrupt();}
    }
  }
  record SigningMarker(Long taskId,String taskCreatedAt,String commitId,String projectDirectory,String temporaryName,String sha256,GradleSigningMarker gradle){
    SigningMarker(Long taskId,String taskCreatedAt,String commitId,String projectDirectory,String temporaryName,String sha256){this(taskId,taskCreatedAt,commitId,projectDirectory,temporaryName,sha256,null);}
  }
  /** Original bytes live only in the private ownership marker; the working-copy file is restored byte-for-byte. */
  record GradleSigningMarker(boolean existed,String originalBase64,String originalSha256,String injectedSha256,String temporaryName,Set<PosixFilePermission> permissions){}

  private GradleSigningMarker prepareGradleSigning(Path project,Properties signing,String temporaryName)throws Exception{
    Path file=project.resolve("android/gradle.properties");rejectSymlinks(project,file);
    boolean existed=Files.exists(file,LinkOption.NOFOLLOW_LINKS);byte[] original=new byte[0];Set<PosixFilePermission> permissions=null;
    if(existed){requireRegular(file,"Gradle 属性文件");if(Files.size(file)>65536)throw new IllegalStateException("Gradle 属性文件超过签名注入大小限制");original=Files.readAllBytes(file);if(Files.getFileStore(file).supportsFileAttributeView("posix"))permissions=Files.getPosixFilePermissions(file);}
    byte[] injected=gradleSigningBytes(original,signing);
    return new GradleSigningMarker(existed,Base64.getEncoder().encodeToString(original),digest(original),digest(injected),temporaryName+".gradle",permissions);
  }
  static byte[] gradleSigningBytes(byte[] original,Properties signing)throws IOException{
    Properties injected=new Properties();
    for(var pair:Map.of("storeFile","store.file","storePassword","store.password","keyAlias","key.alias","keyPassword","key.password").entrySet())injected.setProperty("android.injected.signing."+pair.getValue(),signing.getProperty(pair.getKey()));
    if(signing.getProperty("storeType")!=null)injected.setProperty("android.injected.signing.store.type",signing.getProperty("storeType"));
    // Properties escaping preserves spaces, colons, Unicode and newlines without putting passwords in process arguments.
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(original);bytes.write('\n');bytes.write('\n');
    // A stable representation is required for ownership verification after a crash (Properties.store adds a timestamp).
    ByteArrayOutputStream properties=new ByteArrayOutputStream();injected.store(properties,null);
    String content=properties.toString(java.nio.charset.StandardCharsets.ISO_8859_1).replaceFirst("(?m)^#[^\\r\\n]*(?:\\r?\\n)","");bytes.write(content.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));return bytes.toByteArray();
  }
  private void writeGradleSigning(Path project,GradleSigningMarker marker,Properties signing)throws Exception{
    Path file=project.resolve("android/gradle.properties"),temp=file.resolveSibling(marker.temporaryName());
    byte[] bytes=gradleSigningBytes(Base64.getDecoder().decode(marker.originalBase64()),signing);
    if(!digest(bytes).equals(marker.injectedSha256()))throw new IllegalStateException("临时 Gradle 签名内容不一致");
    rejectSymlinks(project,file);
    if(marker.existed()? !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!sha256(file).equals(marker.originalSha256()):Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Gradle 属性文件在签名注入前发生变化，已拒绝覆盖");
    Files.createFile(temp,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PRIVATE_FILE));Files.write(temp,bytes,StandardOpenOption.TRUNCATE_EXISTING);
    try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
    replaceOwnedFile(temp,file);
  }
  private static void replaceOwnedFile(Path source,Path target)throws IOException{try{Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}}
  private static String digest(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  Path signingMarkerPath(ReleaseTask task){if(task.getId()==null||task.getId()<1)throw new IllegalArgumentException("App 任务编号无效");return rootDirectory().resolve(".signing-injections").resolve(task.getId()+".json");}
  private void rejectTrackedSigningFile(Path project)throws Exception{
    String tracked=run(List.of("git","ls-files","--","android/key.properties"),project,30,null,Map.of(),List.of()).trim();
    if(!tracked.isEmpty())throw new IllegalStateException("android/key.properties 是仓库跟踪文件，拒绝注入或自动清理，请移除仓库中的签名信息");
  }
  /** Recovery only unlinks the exact private contents recorded for this task, never arbitrary source. */
  void recoverSigningInjection(ReleaseTask task,Path project)throws Exception{
    Path marker=signingMarkerPath(task);rejectSymlinks(rootDirectory(),marker);
    if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS))return;
    requireRegular(marker,"签名注入记录");if(Files.size(marker)>131072)throw new IllegalStateException("签名注入记录格式错误");
    SigningMarker record=JSON.readValue(Files.readAllBytes(marker),SigningMarker.class);
    if(!Objects.equals(record.taskId(),task.getId())||!Objects.equals(record.taskCreatedAt(),createdIdentity(task))||!Objects.equals(record.commitId(),task.getCommitId())||!Objects.equals(record.projectDirectory(),project.toAbsolutePath().normalize().toString())||record.temporaryName()==null||!record.temporaryName().matches("\\.release-signing-[a-f0-9-]{36}\\.tmp")||record.sha256()==null||!record.sha256().matches("[a-f0-9]{64}"))throw new IllegalStateException("签名注入记录与当前任务不一致，拒绝自动清理");
    rejectTrackedSigningFile(project);
    Path destination=project.resolve("android/key.properties"),temporary=project.resolve("android").resolve(record.temporaryName());
    for(Path file:List.of(destination,temporary)){
      rejectSymlinks(project,file);
      if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){requireRegular(file,"临时签名文件");if(!sha256(file).equals(record.sha256()))throw new IllegalStateException("临时签名文件内容已改变，拒绝自动清理，请管理员核对");}
    }
    validateGradleRecovery(project,record.gradle());
    recoverGradleSigning(project,record.gradle());
    Files.deleteIfExists(destination);Files.deleteIfExists(temporary);Files.delete(marker);
  }
  private void validateGradleRecovery(Path project,GradleSigningMarker record)throws Exception{
    if(record==null)return;
    byte[] original;try{original=Base64.getDecoder().decode(record.originalBase64());}catch(Exception e){throw new IllegalStateException("Gradle 签名恢复记录格式错误");}
    if(original.length>65536||!digest(original).equals(record.originalSha256())||record.injectedSha256()==null||!record.injectedSha256().matches("[a-f0-9]{64}")||record.temporaryName()==null||!record.temporaryName().matches("\\.release-signing-[a-f0-9-]{36}\\.tmp\\.gradle"))throw new IllegalStateException("Gradle 签名恢复记录无效");
    Path file=project.resolve("android/gradle.properties"),temp=file.resolveSibling(record.temporaryName());
    for(Path path:List.of(file,temp)){
      rejectSymlinks(project,path);
      if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){requireRegular(path,"临时 Gradle 签名文件");String hash=sha256(path);if(!hash.equals(record.injectedSha256())&&!(record.existed()&&hash.equals(record.originalSha256())))throw new IllegalStateException("临时 Gradle 签名文件内容已改变，拒绝自动清理，请管理员核对");}
      else if(path.equals(file)&&record.existed())throw new IllegalStateException("原 Gradle 属性文件缺失，拒绝自动清理");
    }
  }
  private void recoverGradleSigning(Path project,GradleSigningMarker record)throws Exception{
    if(record==null)return;
    Path file=project.resolve("android/gradle.properties"),temp=file.resolveSibling(record.temporaryName());
    if(record.existed()){
      if(!sha256(file).equals(record.originalSha256())){
        Files.deleteIfExists(temp);atomicWrite(temp,new ByteArrayInputStream(Base64.getDecoder().decode(record.originalBase64())));
        if(record.permissions()!=null&&Files.getFileStore(temp).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(temp,record.permissions());
        replaceOwnedFile(temp,file);
      }
    }else Files.deleteIfExists(file);
    Files.deleteIfExists(temp);
  }

  String run(List<String> command,Path directory,long timeout,Consumer<String> log,Map<String,String> env,List<String> secrets)throws Exception{
    Consumer<String> safeLog=log==null?null:line->log.accept(redact(line,secrets));
    try{return redact(BoundedProcessRunner.run(command,directory,null,timeout,safeLog,env),secrets);}
    catch(InterruptedException e){throw e;}
    catch(Exception e){throw new IllegalStateException(redact(e.getMessage(),secrets));}
  }
  static String redact(String text,List<String> secrets){String clean=Objects.toString(text,"执行失败");for(String secret:secrets)if(secret!=null&&!secret.isEmpty())clean=clean.replace(secret,"[REDACTED]");return clean;}

  record Validated(String applicationId,String versionName,long versionCode,String sha256,long sizeBytes,String certificateSha256){}
  record ArchiveManifest(Long serviceId,Long buildTaskId,String taskCreatedAt,String commitId,String configSha256,String flutterVersion,Validated file,String format){}
  Validated validate(FlutterBuildConfiguration config,Path file,Path project)throws Exception{
    validateBuildPolicy(config);
    requireRegular(file,"App 安装包");String before=sha256(file);
    AndroidManifest actual=config.isApk()?validateApk(config,file,project):validateBundle(config,file,project);
    if(!config.applicationId().equals(actual.applicationId()))throw new IllegalStateException(config.outputFormat()+" 应用 ID 与服务配置不一致");
    if(config.versionName()!=null&&!config.versionName().equals(actual.versionName()))throw new IllegalStateException(config.outputFormat()+" versionName 与指定版本不一致");
    if(config.versionCode()!=null&&config.versionCode()!=actual.versionCode())throw new IllegalStateException(config.outputFormat()+" versionCode 与指定版本不一致");
    if(!before.equals(sha256(file)))throw new IllegalStateException("校验期间 App 文件发生变化");
    return new Validated(actual.applicationId(),actual.versionName(),actual.versionCode(),before,Files.size(file),config.expectedCertificateSha256());
  }
  private AndroidManifest validateBundle(FlutterBuildConfiguration config,Path file,Path project)throws Exception{
    verifySignedBundle(file,config.expectedCertificateSha256());
    Path bundletool=requiredPath(bundletoolJar,"bundletool JAR");requireRegular(bundletool,"bundletool JAR");
    Path java=javaBinary();requireRegular(java,"Android 构建专用 JDK");
    Map<String,String> env=new HashMap<>();for(String k:List.of("JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS"))env.put(k,null);
    List<String> prefix=List.of(java.toString(),"-Xmx512m","-jar",bundletool.toString());
    List<String> command=new ArrayList<>(prefix);command.addAll(List.of("validate","--bundle="+file));run(command,project,120,null,env,List.of());
    command=new ArrayList<>(prefix);command.addAll(List.of("dump","manifest","--bundle="+file,"--module=base"));
    String xml=run(command,project,120,null,env,List.of());
    return readManifest(xml);
  }
  private AndroidManifest validateApk(FlutterBuildConfiguration config,Path file,Path project)throws Exception{
    verifyApkStructure(file);
    Path signer=requiredPath(apksignerPath,"固定版本 apksigner 路径"),analyzer=requiredPath(apkanalyzerPath,"固定版本 apkanalyzer 路径");
    requireRegular(signer,"apksigner");requireRegular(analyzer,"apkanalyzer");
    Path java=javaBinary();requireRegular(java,"Android 构建专用 JDK");
    Map<String,String> env=buildEnvironment(java.getParent().getParent(),requiredPath(androidSdk,"Android SDK"),signer.getParent());
    env.put("JAVA_OPTS","-Xms64m -Xmx512m");env.put("GRADLE_OPTS",null);env.put("LC_ALL","C");env.put("LANG","C");
    String certs=run(List.of(signer.toString(),"verify","--verbose","--print-certs",file.toString()),project,120,null,env,List.of());
    verifyApkCertificateOutput(certs,config.expectedCertificateSha256(),allowsDebugCertificate(config));
    String debuggable=run(List.of(analyzer.toString(),"manifest","debuggable",file.toString()),project,120,null,env,List.of()).trim();
    if(!(config.isDebug()?"true":"false").equals(debuggable))throw new IllegalStateException(config.isDebug()?"APK 与锁定的 Debug 构建模式不一致":"APK 必须是不可调试的 release 安装包");
    return readManifest(run(List.of(analyzer.toString(),"manifest","print",file.toString()),project,120,null,env,List.of()),true,config.isDebug());
  }
  static void verifyApkCertificateOutput(String output,String expected)throws Exception{
    verifyApkCertificateOutput(output,expected,false);
  }
  static void verifyApkCertificateOutput(String output,String expected,boolean allowDebugCertificate)throws Exception{
    Matcher cert=Pattern.compile("(?m)^Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\\s*$").matcher(output);
    List<String> hashes=new ArrayList<>();while(cert.find())hashes.add(cert.group(1).toLowerCase(Locale.ROOT));
    if(hashes.size()!=1||!hashes.getFirst().equals(expected))throw new IllegalStateException("APK 签名证书指纹不匹配或签名者不唯一");
    Matcher names=Pattern.compile("(?m)^Signer #[0-9]+ certificate DN: (.+)$").matcher(output);boolean found=false;
    while(names.find()){found=true;if(!allowDebugCertificate)rejectDebugCertificate(names.group(1).trim());}
    if(!found)throw new IllegalStateException("无法识别 APK 签名证书主题");
  }
  static boolean allowsDebugCertificate(FlutterBuildConfiguration config){return config.isDebug()&&config.allowDebugSigning()&&Set.of("dev","test").contains(Objects.toString(config.environmentName(),""));}
  static void validateBuildPolicy(FlutterBuildConfiguration config){
    if(!Set.of("DEBUG","RELEASE").contains(config.buildMode()))throw new IllegalStateException("App 构建模式无效");
    if("prod".equals(config.environmentName())&&(config.isDebug()||config.allowDebugSigning()))throw new IllegalStateException("生产环境只允许 Release 构建与正式签名");
    if(config.isDebug()&&(!config.isApk()||!allowsDebugCertificate(config)||config.signingProfile()==null))throw new IllegalStateException("Debug 仅允许非生产 APK，并须指定已登记的测试签名");
  }
  private static void rejectDebugCertificate(String subject)throws Exception{
    for(var rdn:new javax.naming.ldap.LdapName(subject).getRdns())if("CN".equalsIgnoreCase(rdn.getType())&&"Android Debug".equalsIgnoreCase(Objects.toString(rdn.getValue()).trim()))throw new IllegalStateException("不允许使用 Android Debug 调试签名证书");
  }
  static void verifyApkStructure(Path file)throws Exception{
    requireRegular(file,"APK 文件");if(Files.size(file)==0)throw new IllegalStateException("APK 文件为空");
    try(ZipFile zip=new ZipFile(file.toFile())){
      if(zip.getEntry("AndroidManifest.xml")==null||zip.getEntry("classes.dex")==null)throw new IllegalStateException("文件不是包含应用代码的完整 APK");
      long total=0;int count=0;Set<String> names=new HashSet<>();var entries=zip.entries();byte[] buffer=new byte[65536];
      while(entries.hasMoreElements()){
        var entry=entries.nextElement();String name=entry.getName();
        if(++count>200000||!names.add(name)||name.startsWith("/")||name.contains("\\")||Arrays.asList(name.split("/")).contains(".."))throw new IllegalStateException("APK 包含非法或重复条目");
        if(entry.isDirectory())continue;
        try(InputStream input=zip.getInputStream(entry)){int length;while((length=input.read(buffer))!=-1){total+=length;if(total>4L*1024*1024*1024)throw new IllegalStateException("APK 解压大小超出限制");}}
      }
    }
  }
  record AndroidManifest(String applicationId,String versionName,long versionCode){}
  static AndroidManifest readManifest(String xml)throws Exception{return readManifest(xml,false);}
  static AndroidManifest readManifest(String xml,boolean apk)throws Exception{
    return readManifest(xml,apk,false);
  }
  static AndroidManifest readManifest(String xml,boolean apk,boolean debugMode)throws Exception{
    DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
    factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
    Element root=factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    String app=root.getAttribute("package"),name=root.getAttributeNS("http://schemas.android.com/apk/res/android","versionName"),code=root.getAttributeNS("http://schemas.android.com/apk/res/android","versionCode");
    if(!root.getTagName().equals("manifest")||app.isBlank()||name.isBlank()||name.length()>120||!code.matches("[1-9][0-9]{0,9}"))throw new IllegalStateException("Android manifest 缺少有效应用 ID、versionName 或 versionCode");
    long number=Long.parseLong(code);if(number>2100000000L)throw new IllegalStateException("Android versionCode 超出 Android 允许范围");
    String ns="http://schemas.android.com/apk/res/android";
    if(apk&&(!root.getAttribute("split").isBlank()||!root.getAttribute("configForSplit").isBlank()||"true".equals(root.getAttributeNS(ns,"isFeatureSplit"))||root.getElementsByTagName("uses-split").getLength()>0))throw new IllegalStateException("APK 依赖拆分安装包，必须使用单个完整 APK");
    var apps=root.getElementsByTagName("application");if(apk&&apps.getLength()!=1)throw new IllegalStateException("APK manifest 缺少唯一 application");
    for(int i=0;i<apps.getLength();i++){
      Element application=(Element)apps.item(i);String debug=application.getAttributeNS(ns,"debuggable");
      if(debugMode?!"true".equals(debug):!debug.isBlank()&&!"false".equals(debug))throw new IllegalStateException(debugMode?"APK manifest 与锁定的 Debug 构建模式不一致":"不允许分发可调试的 Android 安装包");
      if(apk&&"true".equals(application.getAttributeNS(ns,"isSplitRequired")))throw new IllegalStateException("APK 依赖拆分安装包");
    }
    var data=root.getElementsByTagName("meta-data");for(int i=0;apk&&i<data.getLength();i++){Element item=(Element)data.item(i);if("com.android.vending.splits.required".equals(item.getAttributeNS(ns,"name"))&&"true".equals(item.getAttributeNS(ns,"value")))throw new IllegalStateException("APK 依赖拆分安装包");}
    return new AndroidManifest(app,name,number);
  }
  static void verifySignedBundle(Path file,String certificate)throws Exception{
    requireRegular(file,"AAB 文件");if(Files.size(file)==0)throw new IllegalStateException("AAB 文件为空");
    long total=0;int entries=0;boolean payload=false;
    try(JarFile jar=new JarFile(file.toFile(),true)){
      if(jar.getEntry("BundleConfig.pb")==null||jar.getEntry("base/manifest/AndroidManifest.xml")==null)throw new IllegalStateException("文件不是有效 Android App Bundle");
      var enumeration=jar.entries();byte[] buffer=new byte[65536];
      while(enumeration.hasMoreElements()){
        JarEntry entry=enumeration.nextElement();String name=entry.getName();
        if(++entries>200000||name.startsWith("/")||name.contains("\\")||Arrays.asList(name.split("/")).contains(".."))throw new IllegalStateException("AAB 包含非法条目");
        if(entry.isDirectory())continue;
        try(InputStream in=jar.getInputStream(entry)){int read;while((read=in.read(buffer))!=-1){total+=read;if(total>4L*1024*1024*1024)throw new IllegalStateException("AAB 解压大小超出限制");}}
        if(name.toUpperCase(Locale.ROOT).startsWith("META-INF/"))continue;
        payload=true;CodeSigner[] signers=entry.getCodeSigners();
        if(signers==null||signers.length==0)throw new IllegalStateException("AAB 包含未签名的内容");
        for(CodeSigner signer:signers){var cert=signer.getSignerCertPath().getCertificates().getFirst();String fingerprint=hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));if(!fingerprint.equals(certificate))throw new IllegalStateException("AAB 上传签名证书指纹不匹配");if(cert instanceof X509Certificate x509)rejectDebugCertificate(x509.getSubjectX500Principal().getName());}
      }
    }catch(SecurityException e){throw new IllegalStateException("AAB 签名验证失败");}
    if(!payload)throw new IllegalStateException("AAB 缺少应用内容");
  }

  private void archive(AppService service,ReleaseTask task,FlutterBuildConfiguration config,Path project)throws Exception{
    if(rootDirectory().startsWith(Path.of(workspaceRoot).toAbsolutePath().normalize()))throw new IllegalStateException("App 持久化目录必须在构建工作目录之外");
    Path output=outputFile(project,config.outputPath());Validated checked=validate(config,output,project);
    ArchiveManifest expected=new ArchiveManifest(service.getId(),task.getId(),createdIdentity(task),task.getCommitId(),configHash(config),config.flutterVersion(),checked,config.outputFormat());
    Path dir=archiveDirectory(task);ensurePrivateDirectory(dir);
    Path bundle=dir.resolve(config.archiveName()),manifest=dir.resolve("manifest.json");rejectSymlinks(rootDirectory(),bundle);rejectSymlinks(rootDirectory(),manifest);
    if(Files.exists(manifest)){ArchiveManifest old=JSON.readValue(Files.readAllBytes(manifest),ArchiveManifest.class);if(!old.equals(expected))throw new IllegalStateException("该构建任务已有不同的持久化产物，请创建新的打包任务");}
    if(Files.exists(bundle)){if(Files.size(bundle)!=checked.sizeBytes()||!sha256(bundle).equals(checked.sha256()))throw new IllegalStateException("持久化目录已有不同的安装包，请创建新的打包任务");}
    else atomicWrite(bundle,Files.newInputStream(output));
    if(Files.size(bundle)!=checked.sizeBytes()||!sha256(bundle).equals(checked.sha256()))throw new IllegalStateException("安装包持久化摘要验证失败");
    if(!Files.exists(manifest))atomicWrite(manifest,new ByteArrayInputStream(JSON.writeValueAsBytes(expected)));
  }

  /** Both records commit together; an interrupted pre-DB archive can only be reused after identity/hash verification. */
  @Transactional
  public Artifact persistArtifact(AppService service,ReleaseTask task,String version){
    try{return persistVerifiedArtifact(service,task,version);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("保存 App 制品失败："+e.getMessage(),e);}
  }
  private Artifact persistVerifiedArtifact(AppService service,ReleaseTask task,String version)throws Exception{
    FlutterBuildConfiguration config=FlutterBuildConfiguration.fromTask(task);FlutterBuildConfiguration.verifyRepository(task,service);
    Path dir=archiveDirectory(task),manifest=dir.resolve("manifest.json"),bundle=dir.resolve(config.archiveName());
    rejectSymlinks(rootDirectory(),manifest);requireRegular(manifest,"安装包持久化清单");
    ArchiveManifest saved=JSON.readValue(Files.readAllBytes(manifest),ArchiveManifest.class);
    if(!Objects.equals(saved.serviceId(),service.getId())||!Objects.equals(saved.buildTaskId(),task.getId())||!Objects.equals(saved.taskCreatedAt(),createdIdentity(task))||!Objects.equals(saved.commitId(),task.getCommitId())||!saved.configSha256().equals(configHash(config))||!Objects.equals(saved.format(),config.outputFormat()))throw new IllegalStateException("App 清单与当前构建任务不一致");
    requireIntegrity(bundle,saved.file().sha256(),saved.file().sizeBytes());
    Artifact artifact=artifacts.findByBuildTaskId(task.getId()).orElse(null);
    if(artifact!=null&&(!Objects.equals(artifact.getCommitId(),task.getCommitId())||!Objects.equals(artifact.getServiceId(),service.getId())||!Objects.equals(artifact.getVersion(),version)))throw new IllegalStateException("构建制品关联不一致");
    if(artifact==null){artifact=new Artifact(service.getId(),task.getId(),version,task.getBranch(),task.getCommitId(),null);artifact.setCreatedBy(task.getCreatedBy());artifact=artifacts.saveAndFlush(artifact);}
    MobileArtifact existing=mobileArtifacts.findByBuildTaskId(task.getId()).orElse(null);
    if(existing!=null){if(!Objects.equals(existing.getArtifactId(),artifact.getId())||!existing.getSha256().equals(saved.file().sha256())||!existing.getFormat().equals(config.outputFormat())||(config.environmentName()!=null&&(!Objects.equals(existing.getBuildEnvironmentName(),config.environmentName())||!Objects.equals(existing.getBuildMode(),config.buildMode()))))throw new IllegalStateException("移动制品关联不一致");return artifact;}
    var f=saved.file();MobileArtifact metadata=new MobileArtifact(artifact.getId(),task.getId(),service.getId(),f.applicationId(),f.versionName(),f.versionCode(),f.sha256(),f.sizeBytes(),f.certificateSha256(),task.getCommitId(),saved.flutterVersion(),bundle.toString(),config.flavor(),config.target(),config.outputFormat());
    if(config.environmentName()!=null){metadata.setBuildEnvironmentName(config.environmentName());metadata.setBuildMode(config.buildMode());}
    mobileArtifacts.saveAndFlush(metadata);
    return artifact;
  }

  public MobileArtifact metadata(Long artifactId){return mobileArtifacts.findByArtifactId(artifactId).orElseThrow(()->new IllegalArgumentException("App 安装包不存在"));}
  public Path verifiedFile(Long artifactId)throws Exception{MobileArtifact file=metadata(artifactId);Path path=Path.of(file.getArchivePath());requireIntegrity(path,file.getSha256(),file.getSizeBytes());return path;}
  /** Invoked only after the task success transaction commits; reuse descriptor-safe cleanup. */
  public void cleanupAfterSuccess(Long taskId){
    if(!storage.enabled||taskId==null)return;
    try{
      ReleaseTask task=tasks.findById(taskId).orElseThrow();
      if(task.getTaskKind()!=com.local.release.domain.ModelEnums.TaskKind.BUILD||task.getStatus()!=com.local.release.domain.ModelEnums.TaskStatus.SUCCESS||task.isSimulate()||task.getFinishedAt()==null)throw new IllegalStateException("App 任务尚未成功提交");
      Artifact artifact=artifacts.findByBuildTaskId(taskId).orElseThrow();MobileArtifact mobile=metadata(artifact.getId());
      if(!Objects.equals(task.getServiceId(),artifact.getServiceId())||!Objects.equals(task.getCommitId(),artifact.getCommitId())||!Objects.equals(mobile.getBuildTaskId(),taskId)||!Objects.equals(mobile.getCommitId(),task.getCommitId()))throw new IllegalStateException("App 制品与成功任务不一致");
      Path archived=verifiedFile(artifact.getId()),workspace=storage.validatedRoot();
      if(archived.startsWith(workspace)||rootDirectory().startsWith(workspace))throw new IllegalStateException("App 持久化目录必须在构建工作目录之外");
      storage.removeWorkspace(workspace,taskId);
    }catch(Exception e){org.slf4j.LoggerFactory.getLogger(FlutterBuildService.class).warn("App build #{} temporary cleanup deferred: {}",taskId,e.getMessage());}
  }
  private void requireIntegrity(Path path,String sha,long size)throws Exception{ensureContained(rootDirectory(),path);rejectSymlinks(rootDirectory(),path);requireRegular(path,"持久化安装包");if(Files.size(path)!=size||!sha256(path).equals(sha))throw new IllegalStateException("持久化安装包 文件摘要或大小发生变化，已拒绝提供");}

  private Path javaBinary(){Path home=requiredPath(javaHome,"Android 构建专用 JAVA_HOME"),binary=home.resolve("bin/java");return Files.isRegularFile(binary)?binary:home.resolve("bin/java.exe");}
  private Path rootDirectory(){return requiredPath(artifactRoot,"App 持久化目录");}
  private Path archiveDirectory(ReleaseTask task){if(task.getId()==null||task.getCommitId()==null||!task.getCommitId().matches("[a-fA-F0-9]{40,64}"))throw new IllegalArgumentException("App 构建任务必须锁定完整 Commit");return rootDirectory().resolve("build-"+task.getId()).resolve(task.getCommitId().toLowerCase(Locale.ROOT));}
  private static Path requiredPath(String raw,String label){if(raw==null||raw.isBlank())throw new IllegalStateException(label+"尚未由管理员配置");return Path.of(raw).toAbsolutePath().normalize();}
  static Path projectDirectory(Path source,String relative)throws IOException{Path root=source.toAbsolutePath().normalize();BuildConfiguration.repositoryPath(relative,"工作目录");Path project=relative==null||relative.isBlank()?root:root.resolve(relative).normalize();ensureContained(root,project);rejectSymlinks(root,project);if(!Files.isDirectory(project))throw new IllegalStateException("Flutter 工作目录不存在");return project;}
  static Path outputFile(Path project,String relative)throws IOException{BuildConfiguration.repositoryPath(relative,"构建输出路径");Path file=project.resolve(relative).normalize();ensureContained(project,file);rejectSymlinks(project,file);requireRegular(file,"构建输出文件");return file;}
  static void ensureContained(Path root,Path path){if(!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))throw new IllegalArgumentException("文件路径超出允许目录");}
  static void rejectSymlinks(Path root,Path path)throws IOException{ensureContained(root,path);Path absolute=path.toAbsolutePath().normalize();Path cursor=absolute.getRoot();for(Path part:absolute){cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor))throw new IllegalStateException("App 文件路径不允许符号链接");}}
  static void requireRegular(Path path,String label){if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))throw new IllegalStateException(label+"不存在或不是普通文件");}
  private static void requirePrivate(Path path)throws IOException{requireRegular(path,"私有签名文件");Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);if(!PRIVATE_FILE.containsAll(permissions))throw new IllegalStateException("私有签名文件必须仅所有者可读写（chmod 600）");}
  private void ensurePrivateDirectory(Path path)throws IOException{rejectSymlinks(rootDirectory(),path);Files.createDirectories(path);if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(path,PRIVATE_DIR);}
  private static void atomicWrite(Path target,InputStream input)throws Exception{
    Path temp=target.resolveSibling(".writing-"+UUID.randomUUID());
    try(input){Files.copy(input,temp);if(Files.getFileStore(temp).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(temp,PRIVATE_FILE);try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target);}}
    finally{Files.deleteIfExists(temp);}
  }
  static String sha256(Path path)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] bytes=new byte[65536];int read;while((read=in.read(bytes))!=-1)digest.update(bytes,0,read);}return hex(digest.digest());}
  static String configHash(FlutterBuildConfiguration config)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(config.environmentName()==null?FlutterBuildConfiguration.legacyJson(config):config)));}
  private static String createdIdentity(ReleaseTask task){if(task.getCreatedAt()==null)throw new IllegalStateException("App 构建任务尚未持久化");return task.getCreatedAt().toString();}
  private static String hex(byte[] bytes){return HexFormat.of().formatHex(bytes);}
}
