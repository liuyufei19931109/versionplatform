package com.local.release.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.local.release.domain.AppService;
import com.local.release.domain.ReleaseTask;
import java.util.*;

/** Resolved immutable build settings. Environment profiles never inherit a legacy signing identity. */
public record FlutterBuildConfiguration(String flutterVersion,String flavor,String target,String applicationId,
    String versionName,Long versionCode,String expectedCertificateSha256,String signingProfile,
    int buildTimeoutSeconds,String aabPath,String outputFormat,String apkPath,
    String environmentName,String buildMode,boolean allowDebugSigning,
    Map<String,String> dartDefines,Map<String,String> environmentVariables) {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final Set<String> ENVS=Set.of("dev","test","prod");
  private static final Set<String> FIELDS=Set.of("flutterVersion","flavor","target","applicationId","versionName","versionCode","expectedCertificateSha256","signingProfile","buildTimeoutSeconds","aabPath","outputFormat","apkPath","buildMode","allowDebugSigning","dartDefines","environmentVariables","externalVersionCodeFloor");
  private static final List<String> SHARED=List.of("flutterVersion","outputFormat","buildTimeoutSeconds","target");
  public FlutterBuildConfiguration {
    buildMode=buildMode==null?"RELEASE":buildMode;
    dartDefines=Collections.unmodifiableMap(new TreeMap<>(dartDefines==null?Map.of():dartDefines));
    environmentVariables=Collections.unmodifiableMap(new TreeMap<>(environmentVariables==null?Map.of():environmentVariables));
  }
  public FlutterBuildConfiguration(String flutterVersion,String flavor,String target,String applicationId,String versionName,Long versionCode,String expectedCertificateSha256,String signingProfile,int buildTimeoutSeconds,String aabPath,String outputFormat,String apkPath){
    this(flutterVersion,flavor,target,applicationId,versionName,versionCode,expectedCertificateSha256,signingProfile,buildTimeoutSeconds,aabPath,outputFormat,apkPath,null,"RELEASE",false,Map.of(),Map.of());
  }
  public static FlutterBuildConfiguration from(AppService service){return parse(service.getConfigJson());}
  public static FlutterBuildConfiguration parse(String json){return validateService(json,true);}
  public static FlutterBuildConfiguration parseDraft(String json){return validateService(json,false);}
  public String outputPath(){return isApk()?apkPath:aabPath;}
  @com.fasterxml.jackson.annotation.JsonIgnore public boolean isApk(){return "APK".equals(outputFormat);}
  @com.fasterxml.jackson.annotation.JsonIgnore public boolean isDebug(){return "DEBUG".equals(buildMode);}
  public String archiveName(){return isApk()?"app.apk":"bundle.aab";}

  public static FlutterBuildConfiguration validateService(String json,boolean enabled){
    ObjectNode common=mobileNode(json,!enabled);versionPolicy(common);common.remove("versionPolicy");JsonNode profiles=common.get("environments");
    if(profiles==null)return parseResolved(common,enabled,null,false);
    if(!profiles.isObject())throw invalid("App 环境配置必须按开发、预生产、生产分别填写");
    ObjectNode legacy=common.deepCopy();legacy.remove("environments");
    FlutterBuildConfiguration representative=parseResolved(legacy,false,null,false);
    Map<String,FlutterBuildConfiguration> active=new LinkedHashMap<>();
    profiles.fields().forEachRemaining(entry->{
      String env=environment(entry.getKey());JsonNode profile=entry.getValue();
      if(!profile.isObject())throw invalid("App 环境配置格式不正确");
      boolean on=bool(profile,"enabled",false);
      FlutterBuildConfiguration config=parseResolved(resolve(common,(ObjectNode)profile),on,env,false);
      if(on)active.put(env,config);
    });
    if(enabled&&active.isEmpty())throw invalid("请至少完成并启用一个 App 构建环境");
    FlutterBuildConfiguration production=active.get("prod");
    if(production!=null)for(var entry:active.entrySet())if(!entry.getKey().equals("prod")){
      var other=entry.getValue();
      if(Objects.equals(production.expectedCertificateSha256(),other.expectedCertificateSha256())||Objects.equals(production.signingProfile(),other.signingProfile()))throw invalid("生产与非生产必须使用不同的签名配置和证书");
      if(Objects.equals(production.applicationId(),other.applicationId()))throw invalid("生产与非生产须使用不同包名，避免覆盖已有应用");
    }
    return active.values().stream().findFirst().orElse(representative);
  }
  public static FlutterBuildConfiguration forEnvironment(AppService service,String requested){
    String env=environment(requested);validateService(service.getConfigJson(),true);
    ObjectNode common=mobileNode(service.getConfigJson(),false);JsonNode profile=common.path("environments").path(env);
    if(!profile.isObject()||!bool(profile,"enabled",false))throw invalid("该 App 尚未配置并启用所选构建环境，请先完成环境配置");
    return parseResolved(resolve(common,(ObjectNode)profile),true,env,false);
  }
  private static ObjectNode resolve(ObjectNode common,ObjectNode profile){
    ObjectNode result=JSON.createObjectNode();for(String field:SHARED)if(common.has(field))result.set(field,common.get(field));
    profile.fields().forEachRemaining(e->{if(!e.getKey().equals("enabled")&&!(SHARED.contains(e.getKey())&&(e.getValue().isNull()||(e.getValue().isTextual()&&e.getValue().asText().isBlank()))))result.set(e.getKey(),e.getValue());});return result;
  }
  public static String environment(String value){if(value==null||!ENVS.contains(value))throw invalid("请先选择 App 构建环境：开发、预生产或生产");return value;}
  public static String snapshot(AppService service){var config=from(service);if(config.environmentName()!=null)throw invalid("请先选择 App 构建环境");return snapshot(service,config,1);}
  public static String snapshot(AppService service,String env){return snapshot(service,forEnvironment(service,env),2);}
  /** Allocation metadata lives at the snapshot root so existing v1/v2 archive digests remain byte-compatible. */
  public static String assignedSnapshot(AppService service,FlutterBuildConfiguration config,String baseVersion,Long preBuildTaskId){
    if(config.environmentName()==null||config.versionName()==null||config.versionCode()==null)throw invalid("自动版本尚未分配");
    ObjectNode node;try{node=(ObjectNode)JSON.readTree(snapshot(service,config,"prod".equals(config.environmentName())?4:3));}catch(Exception e){throw new IllegalStateException(e);}
    node.put("versionPolicy","AUTO");node.put("baseVersion",baseVersion);if(preBuildTaskId!=null)node.put("preBuildTaskId",preBuildTaskId);return node.toString();
  }
  public FlutterBuildConfiguration withVersion(String name,long code){return new FlutterBuildConfiguration(flutterVersion,flavor,target,applicationId,name,code,expectedCertificateSha256,signingProfile,buildTimeoutSeconds,aabPath,outputFormat,apkPath,environmentName,buildMode,allowDebugSigning,dartDefines,environmentVariables);}
  public static String versionPolicy(AppService service){return versionPolicy(mobileNode(service.getConfigJson(),false));}
  private static String versionPolicy(JsonNode node){String policy=optional(node,"versionPolicy");if(policy==null)return "MANUAL";if(!Set.of("MANUAL","AUTO").contains(policy))throw invalid("App 版本策略只能选择 MANUAL 或 AUTO");return policy;}
  public static long externalVersionCodeFloor(AppService service,String env){return versionFloor(mobileNode(service.getConfigJson(),false).path("environments").path(environment(env)));}
  private static long versionFloor(JsonNode node){JsonNode raw=node.get("externalVersionCodeFloor");if(raw==null||raw.isNull()||raw.asText().isBlank())return 0;if(!raw.asText().matches("0|[1-9][0-9]{0,9}"))throw invalid("外部 versionCode 下限必须为 0 到 2100000000 的整数");long value=Long.parseLong(raw.asText());if(value>2100000000L)throw invalid("外部 versionCode 下限不能超过 2100000000");return value;}
  public static void validateExternalFloors(String oldJson,String newJson,boolean admin){
    JsonNode oldRoot=mobileNode(oldJson,true),newRoot=mobileNode(newJson,true);String oldPolicy=versionPolicy(oldRoot),newPolicy=versionPolicy(newRoot);
    if(!oldPolicy.equals(newPolicy)&&!admin)throw new org.springframework.security.access.AccessDeniedException("仅管理员可以切换 App 自动版本策略");
    JsonNode before=oldRoot.path("environments"),after=newRoot.path("environments");
    for(String env:ENVS){long old=versionFloor(before.path(env)),next=versionFloor(after.path(env));if(next<old)throw invalid("已登记的外部 versionCode 下限不能调低");if(next!=old&&!admin)throw new org.springframework.security.access.AccessDeniedException("仅管理员可以上调外部 versionCode 下限");
      if("AUTO".equals(oldPolicy)||"AUTO".equals(newPolicy)){String oldCode=optional(before.path(env),"versionCode"),newCode=optional(after.path(env),"versionCode");if(!Objects.equals(oldCode,newCode)&&!admin)throw new org.springframework.security.access.AccessDeniedException("仅管理员可以修改自动版本的 versionCode 历史基准");}
    }
  }
  public static Long taskPreBuildTaskId(ReleaseTask task){JsonNode node=snapshotNode(task);return node.path("snapshotVersion").asInt()>=3&&node.hasNonNull("preBuildTaskId")?node.get("preBuildTaskId").longValue():null;}
  private static String snapshot(AppService service,FlutterBuildConfiguration config,int version){
    BuildConfiguration.repositoryPath(service.getWorkDirectory(),"工作目录");ObjectNode node=JSON.createObjectNode();node.put("snapshotVersion",version);
    node.set("mobileBuild",version==1?legacyJson(config):JSON.valueToTree(config));if(version>=2)node.put("environmentName",config.environmentName());
    node.put("workDirectory",Objects.toString(service.getWorkDirectory(),""));node.put("gitRepo",Objects.toString(service.getGitRepo(),""));return node.toString();
  }
  /** Preserve the field set/order used by v1 archive config digests. */
  public static ObjectNode legacyJson(FlutterBuildConfiguration config){ObjectNode node=JSON.valueToTree(config);node.remove(List.of("environmentName","buildMode","allowDebugSigning","dartDefines","environmentVariables"));return node;}
  public static FlutterBuildConfiguration fromTask(ReleaseTask task){JsonNode n=snapshotNode(task);String env=n.path("snapshotVersion").asInt()>=2?environment(n.path("environmentName").asText()):null;return parseResolved((ObjectNode)n.path("mobileBuild"),true,env,true);}
  public static String taskWorkDirectory(ReleaseTask task){String path=snapshotNode(task).path("workDirectory").asText();BuildConfiguration.repositoryPath(path,"工作目录");return path;}
  public static void verifyRepository(ReleaseTask task,AppService service){if(!snapshotNode(task).path("gitRepo").asText().equals(Objects.toString(service.getGitRepo(),"")))throw invalid("App 仓库地址已改变，请新建打包任务");}
  private static JsonNode snapshotNode(ReleaseTask task){
    String raw=task.getMobileBuildConfigSnapshot();if(raw==null||raw.isBlank())throw invalid("旧 App 任务没有锁定构建配置，请重新确认代码并新建打包任务");
    try{JsonNode n=JSON.readTree(raw);int version=n.path("snapshotVersion").asInt();
      if(!n.isObject()||(version!=1&&version!=2&&version!=3&&version!=4)||!n.path("mobileBuild").isObject()||!n.path("workDirectory").isTextual()||!n.path("gitRepo").isTextual())throw invalid("App 构建配置快照无效，请新建打包任务");
      if(version>=2){String env=environment(n.path("environmentName").asText());if(!env.equals(n.path("mobileBuild").path("environmentName").asText())||!env.equals(task.getEnvironmentName()))throw invalid("App 构建环境与锁定快照不一致");
        if(version>=3){String base=n.path("baseVersion").asText();String suffix=version==4?"":switch(env){case "prod"->"-prd";case "test"->"-pre";default->"-dev";};
          if(version==4&&!"prod".equals(env)||!"AUTO".equals(n.path("versionPolicy").asText())||!base.matches("(?:0|[1-9][0-9]*)\\.[0-9]\\.[0-9]")||!n.path("mobileBuild").path("versionName").asText().equals(base+suffix)||!n.path("mobileBuild").path("versionCode").canConvertToLong())throw invalid("自动 App 版本快照无效");
          if("prod".equals(env)&&(!n.path("preBuildTaskId").isIntegralNumber()||n.path("preBuildTaskId").longValue()<=0))throw invalid("生产 App 缺少锁定的 PRE 构建来源");if(!"prod".equals(env)&&n.hasNonNull("preBuildTaskId"))throw invalid("非生产 App 不应指定 PRE 构建来源");}
      }
      else if(n.path("mobileBuild").hasNonNull("environmentName"))throw invalid("旧 App 快照不能被重新指定环境");return n;
    }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw invalid("App 构建配置快照无效，请新建打包任务");}
  }
  private static ObjectNode mobileNode(String raw,boolean draft){try{JsonNode root=JSON.readTree(raw==null?"{}":raw);JsonNode n=root.path("mobileBuild");if(n.isMissingNode()&&draft)return JSON.createObjectNode();if(!n.isObject())throw invalid("请配置 Flutter Android 构建信息");return (ObjectNode)n;}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw invalid("Flutter 构建配置格式错误");}}
  private static FlutterBuildConfiguration parseResolved(ObjectNode n,boolean complete,String env,boolean snapshot){
    n.fieldNames().forEachRemaining(field->{if(!FIELDS.contains(field)&&!(snapshot&&field.equals("environmentName")))throw invalid("不支持的 App 构建配置字段："+field+"；签名密码和私钥必须放在服务器私有配置中");});
    String version=complete?required(n,"flutterVersion","Flutter 固定版本"):optional(n,"flutterVersion");
    if(version!=null&&!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.]+)?"))throw invalid("Flutter 版本必须固定到具体版本，例如 3.47.3");
    String app=complete?required(n,"applicationId","Android 应用 ID"):optional(n,"applicationId");if(app!=null&&!app.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))throw invalid("Android 应用 ID 格式不正确");
    String cert=complete?required(n,"expectedCertificateSha256","签名证书 SHA-256"):optional(n,"expectedCertificateSha256");
    if(cert!=null){cert=cert.replaceAll("[:\\s]","").toLowerCase(Locale.ROOT);if(!cert.matches("[a-f0-9]{64}"))throw invalid("签名证书 SHA-256 必须为 64 位十六进制指纹");}
    String format=optional(n,"outputFormat");if(format==null)format="AAB";if(!Set.of("APK","AAB").contains(format))throw invalid("App 产物格式仅支持 APK 或 AAB");
    String mode=optional(n,"buildMode");if(mode==null)mode="RELEASE";if(!Set.of("RELEASE","DEBUG").contains(mode))throw invalid("App 构建模式只能选择 Release 或 Debug");
    boolean debug=bool(n,"allowDebugSigning",false);
    if(complete&&"prod".equals(env)&&(!"RELEASE".equals(mode)||debug))throw invalid("生产必须使用 Release 构建和正式签名，禁止 Debug");
    if(complete&&env==null&&(!"RELEASE".equals(mode)||debug))throw invalid("Debug 仅可在明确的非生产环境使用");
    if(complete&&"DEBUG".equals(mode)&&(!"APK".equals(format)||!debug))throw invalid("Debug 仅支持 APK，请明确允许登记的测试证书");
    if(complete&&"AAB".equals(format)&&debug)throw invalid("AAB 必须使用 Release 和正式上传签名");
    if(complete&&"RELEASE".equals(mode)&&debug)throw invalid("Release 构建不能启用 Debug 测试证书许可");
    String flavor=optional(n,"flavor"),target=optional(n,"target"),name=optional(n,"versionName"),profile=optional(n,"signingProfile");
    if(env!=null&&complete&&profile==null)throw invalid("请选择该环境的服务端签名配置");
    if(flavor!=null&&!flavor.matches("[A-Za-z][A-Za-z0-9_-]{0,79}"))throw invalid("构建 flavor 格式不正确");
    if(profile!=null&&!profile.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))throw invalid("签名配置名称格式不正确");
    if(name!=null&&(name.length()>120||name.chars().anyMatch(Character::isISOControl)))throw invalid("应用版本名称不正确");
    if(target!=null){if(target.length()>300)throw invalid("Flutter 入口文件路径不能超过 300 个字符");BuildConfiguration.repositoryPath(target,"Flutter 入口文件");}
    Long code=null;if(n.hasNonNull("versionCode")&&!n.get("versionCode").asText().isBlank()){String raw=n.get("versionCode").asText();if(!raw.matches("[1-9][0-9]{0,9}"))throw invalid("versionCode 必须是正整数");code=Long.parseLong(raw);if(code>2100000000L)throw invalid("versionCode 超出 Android 允许范围");}
    versionFloor(n);
    int timeout=n.hasNonNull("buildTimeoutSeconds")?n.get("buildTimeoutSeconds").asInt(-1):600;if(timeout<60||timeout>7200)throw invalid("App 构建超时必须在 60 到 7200 秒之间");
    String path=optional(n,"aabPath");if(path==null)path=flavor==null?"build/app/outputs/bundle/release/app-release.aab":"build/app/outputs/bundle/"+flavor+"Release/app-"+flavor+"-release.aab";validatePath(path,"AAB",".aab");
    String apk=optional(n,"apkPath");if(apk==null)apk="build/app/outputs/flutter-apk/app"+(flavor==null?"":"-"+flavor)+("DEBUG".equals(mode)?"-debug.apk":"-release.apk");validatePath(apk,"APK",".apk");
    Map<String,String> defines=stringMap(n,"dartDefines",false),variables=stringMap(n,"environmentVariables",true);
    if(env==null&&(!defines.isEmpty()||!variables.isEmpty()))throw invalid("构建参数必须配置在明确的 App 环境中");
    return new FlutterBuildConfiguration(version,flavor,target,app,name,code,cert,profile,timeout,path,format,apk,env,mode,debug,defines,variables);
  }
  private static Map<String,String> stringMap(JsonNode node, String field, boolean variables) {
    JsonNode raw = node.get(field);
    if (raw == null || raw.isNull()) return Map.of();
    if (!raw.isObject() || raw.size() > 40) throw invalid(field + " 必须为最多 40 项的文本配置");
    Map<String,String> result = new TreeMap<>();
    raw.fields().forEachRemaining(entry -> {
      String key = entry.getKey();
      if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,79}") || !entry.getValue().isTextual())
        throw invalid(field + " 的名称和值格式不正确");
      String value = entry.getValue().asText();
      if (value.length() > 2048 || value.chars().anyMatch(Character::isISOControl))
        throw invalid("App 构建参数过长或含控制字符");
      if (key.toUpperCase(Locale.ROOT).matches(".*(PASSWORD|PASSWD|SECRET|TOKEN|PRIVATE_KEY|API_KEY|SIGNING|KEYSTORE|CREDENTIAL).*"))
        throw invalid("构建参数不能保存密码、令牌、私钥或签名凭据");
      if (variables && !key.matches("APP_BUILD_[A-Z0-9_]+"))
        throw invalid("构建环境变量须使用 APP_BUILD_ 前缀和大写字母、数字或下划线");
      result.put(key, value);
    });
    return result;
  }
  private static void validatePath(String path,String type,String extension){BuildConfiguration.repositoryPath(path,type+" 输出路径");if(!path.endsWith(extension)||path.contains("*")||path.contains("?"))throw invalid(type+" 输出路径必须指定唯一 "+extension+" 文件");}
  private static boolean bool(JsonNode node,String field,boolean fallback){JsonNode v=node.get(field);if(v==null||v.isNull())return fallback;if(!v.isBoolean())throw invalid(field+" 必须为开关值");return v.asBoolean();}
  private static String required(JsonNode n,String key,String title){String v=optional(n,key);if(v==null)throw invalid(title+"不能为空");return v;}
  private static String optional(JsonNode n,String key){JsonNode v=n.get(key);if(v==null||v.isNull())return null;if(!v.isValueNode())throw invalid(key+"必须为文本");String s=v.asText().trim();return s.isEmpty()?null:s;}
  private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
