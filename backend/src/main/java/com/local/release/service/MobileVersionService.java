package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.*;
import static com.local.release.domain.ModelEnums.*;

/** Version reservation is part of the caller's task-creation transaction, never part of a build attempt. */
@Service
public class MobileVersionService {
  private static final long MAX_CODE=2100000000L,MAX_BASE=999999999999L;
  private final MobileVersionSequenceRepository sequences;
  private final MobileVersionAllocationRepository allocations;
  private final AppServiceRepository services;
  private final ReleaseTaskRepository tasks;
  private final ArtifactRepository artifacts;
  private final MobileArtifactRepository mobile;
  public MobileVersionService(MobileVersionSequenceRepository sequences,MobileVersionAllocationRepository allocations,AppServiceRepository services,ReleaseTaskRepository tasks,ArtifactRepository artifacts,MobileArtifactRepository mobile){this.sequences=sequences;this.allocations=allocations;this.services=services;this.tasks=tasks;this.artifacts=artifacts;this.mobile=mobile;}
  public record Preview(String versionPolicy,String environmentName,String versionName,Long versionCode,String baseVersion,Long preBuildTaskId,boolean allocationPending,String message){}
  public record Candidate(Long buildTaskId,Long artifactId,String applicationId,LocalDateTime createdAt,String versionName,long versionCode,String baseVersion,String branch,String commitId){}
  public record Reservation(MobileVersionAllocation allocation,FlutterBuildConfiguration config){}

  public Preview preview(long serviceId,String environment,String branch,String commitId,Long preBuildTaskId){
    AppService service=app(serviceId);var config=FlutterBuildConfiguration.forEnvironment(service,environment);String policy=FlutterBuildConfiguration.versionPolicy(service);
    if(!"AUTO".equals(policy)){if(preBuildTaskId!=null)throw invalid("手动版本不使用 PRE 构建来源");return new Preview(policy,environment,config.versionName(),config.versionCode(),null,null,false,"沿用手动版本配置；空值由项目决定");}
    if("prod".equals(environment)&&preBuildTaskId==null)return new Preview(policy,environment,null,null,null,null,true,"请先选择已成功打包的 PRE 版本，生产将锁定其分支和 Commit");
    String base=baseVersion(service,config,branch,commitId,preBuildTaskId,false);
    long code=nextCode(service,config,false);
    return new Preview(policy,environment,base+suffix(environment),code,base,preBuildTaskId,true,"预计版本，提交时原子分配；并发提交可能使版本号顺延");
  }
  public List<Candidate> candidates(long serviceId,String branch,String commitId){
    AppService service=app(serviceId);
    if(branch!=null&&!branch.isBlank())validateBranch(branch);
    if(commitId!=null&&!commitId.isBlank())validateCommit(commitId);
    long productionHigh=highestProductionSource(service,false);
    List<Candidate> result=new ArrayList<>();
    for(ReleaseTask task:tasks.findByServiceIdAndTaskKindOrderByCreatedAtDesc(serviceId,TaskKind.BUILD)){
      if(branch!=null&&!branch.isBlank()&&!branch.equals(task.getBranch())||commitId!=null&&!commitId.isBlank()&&!commitId.equalsIgnoreCase(task.getCommitId()))continue;
      try{Candidate candidate=candidate(service,task);if(baseNumber(candidate.baseVersion())>=productionHigh)result.add(candidate);}catch(IllegalArgumentException ignored){}
    }
    return List.copyOf(result);
  }
  /** Caller must retry the complete creation transaction on an initial unique-row race. */
  public Reservation reserve(AppService service,FlutterBuildConfiguration config,String branch,String commitId,Long preBuildTaskId,String operator){
    if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("App 版本分配必须与任务创建处于同一事务");
    if(!"AUTO".equals(FlutterBuildConfiguration.versionPolicy(service)))throw invalid("当前 App 未启用自动版本");
    validateBranch(branch);validateCommit(commitId);
    // Always lock the package first; shared package IDs therefore serialize both name and code reservations.
    long code=nextCode(service,config,true);
    String base=baseVersion(service,config,branch,commitId,preBuildTaskId,true),name=base+suffix(config.environmentName());
    MobileVersionAllocation allocation=allocations.saveAndFlush(new MobileVersionAllocation(service.getId(),config.applicationId(),config.environmentName(),name,base,code,preBuildTaskId,branch,commitId.toLowerCase(Locale.ROOT),operator));
    return new Reservation(allocation,config.withVersion(name,code));
  }
  public void bind(Reservation reservation,long buildTaskId){
    if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("App 版本绑定必须与任务创建处于同一事务");
    reservation.allocation().bind(buildTaskId);allocations.saveAndFlush(reservation.allocation());
  }
  private long nextCode(AppService service,FlutterBuildConfiguration config,boolean reserve){
    String key="CODE:"+config.applicationId();MobileVersionSequence sequence=sequence(key,reserve);
    long high=sequence==null?0:sequence.getLastValue();
    high=Math.max(high,config.versionCode()==null?0:config.versionCode());high=Math.max(high,FlutterBuildConfiguration.externalVersionCodeFloor(service,config.environmentName()));
    for(MobileArtifact artifact:mobile.findByApplicationId(config.applicationId()))high=Math.max(high,artifact.getVersionCode());
    for(MobileVersionAllocation allocation:allocations.findByApplicationId(config.applicationId()))high=Math.max(high,allocation.getVersionCode());
    // Includes queued, failed and cancelled pre-feature tasks, not only successful artifacts.
    for(ReleaseTask task:tasks.findByTaskKindOrderByCreatedAtDesc(TaskKind.BUILD))if("MOBILE".equals(task.getDeliveryType())&&!task.isSimulate())try{
      var prior=FlutterBuildConfiguration.fromTask(task);if(config.applicationId().equals(prior.applicationId())&&prior.versionCode()!=null)high=Math.max(high,prior.versionCode());
    }catch(IllegalArgumentException ignored){}
    // Another environment or service can share this package ID: its registered external floor also applies.
    for(AppService other:services.findAll())if(other.getServiceType()==ServiceType.FLUTTER_ANDROID)for(String env:List.of("dev","test","prod"))try{
      var prior=FlutterBuildConfiguration.forEnvironment(other,env);if(config.applicationId().equals(prior.applicationId())){high=Math.max(high,prior.versionCode()==null?0:prior.versionCode());high=Math.max(high,FlutterBuildConfiguration.externalVersionCodeFloor(other,env));}
    }catch(IllegalArgumentException ignored){}
    if(high>=MAX_CODE)throw invalid("Android versionCode 已达到 2100000000，无法继续分配");
    if(reserve){sequence.advance(high+1);sequences.saveAndFlush(sequence);}return high+1;
  }
  private String baseVersion(AppService service,FlutterBuildConfiguration config,String branch,String commitId,Long preBuildTaskId,boolean reserve){
    String env=config.environmentName();
    if("prod".equals(env)){
      if(preBuildTaskId==null||preBuildTaskId<=0)throw invalid("自动生产构建必须选择一个成功的 PRE 构建版本");
      Candidate source=candidate(service,tasks.findById(preBuildTaskId).orElseThrow(()->invalid("所选 PRE 构建不存在")));
      if(!Objects.equals(branch,source.branch())||commitId==null||!commitId.equalsIgnoreCase(source.commitId()))throw invalid("生产分支和 Commit 必须与所选 PRE 构建完全一致，请重新选择 PRE 版本");
      long value=baseNumber(source.baseVersion());long high=highestProductionSource(service,reserve);
      if(value<high)throw invalid("所选 PRE 主版本早于已分配或已发布的生产版本；需要回滚时请发布原生产制品");
      // Keep the legacy counter as the PRE source floor. It must not reset with the display name.
      if(reserve){var sequence=sequence(productionSourceKey(service),true);sequence.advance(Math.max(high,value));sequences.saveAndFlush(sequence);}
    }
    else if(preBuildTaskId!=null)throw invalid("仅生产构建可以选择 PRE 构建来源");
    long high=highestBase(service,env,reserve),next=high<0?("prod".equals(env)?1:10):high+1;
    if(next>MAX_BASE)throw invalid("App 主版本号已超出平台支持范围");
    if(reserve){var sequence=sequence(nameKey(service,env),true);sequence.advance(next);sequences.saveAndFlush(sequence);}
    return baseString(next);
  }
  private long highestBase(AppService service,String env,boolean lock){
    var sequence=sequence(nameKey(service,env),lock);long high=sequence==null?-1:sequence.getLastValue();
    for(var allocation:allocations.findByServiceIdAndEnvironmentName(service.getId(),env))high=Math.max(high,"prod".equals(env)?nameNumber(allocation.getVersionName(),env):baseNumber(allocation.getBaseVersion()));
    for(var artifact:mobile.findByServiceIdAndBuildEnvironmentName(service.getId(),env))high=Math.max(high,nameNumber(artifact.getVersionName(),env));
    for(var task:tasks.findByServiceIdAndTaskKindOrderByCreatedAtDesc(service.getId(),TaskKind.BUILD))if("MOBILE".equals(task.getDeliveryType())&&!task.isSimulate()&&env.equals(task.getEnvironmentName()))try{
      var config=FlutterBuildConfiguration.fromTask(task);if(config.versionName()!=null)high=Math.max(high,nameNumber(config.versionName(),env));
    }catch(IllegalArgumentException invalidSnapshot){
      // A valid snapshot with an incompatible historical name must not silently reset the name sequence.
      if(invalidSnapshot.getMessage()!=null&&invalidSnapshot.getMessage().startsWith("历史 App 版本名"))throw invalidSnapshot;
    }
    return high;
  }
  /** Production display versions and the minimum eligible PRE source are independent sequences. */
  private long highestProductionSource(AppService service,boolean lock){
    var sequence=sequence(productionSourceKey(service),lock);long high=sequence==null?-1:sequence.getLastValue();
    for(var allocation:allocations.findByServiceIdAndEnvironmentName(service.getId(),"prod"))high=Math.max(high,productionSourceNumber(service,allocation.getVersionName(),allocation.getPreBuildTaskId()));
    for(var artifact:mobile.findByServiceIdAndBuildEnvironmentName(service.getId(),"prod"))high=Math.max(high,legacyProductionNumber(artifact.getVersionName()));
    for(var task:tasks.findByServiceIdAndTaskKindOrderByCreatedAtDesc(service.getId(),TaskKind.BUILD))if("MOBILE".equals(task.getDeliveryType())&&!task.isSimulate()&&"prod".equals(task.getEnvironmentName()))try{
      var config=FlutterBuildConfiguration.fromTask(task);high=Math.max(high,productionSourceNumber(service,config.versionName(),FlutterBuildConfiguration.taskPreBuildTaskId(task)));
    }catch(IllegalArgumentException invalidSnapshot){if(invalidSnapshot.getMessage()!=null&&invalidSnapshot.getMessage().startsWith("历史 App 版本名"))throw invalidSnapshot;}
    return high;
  }
  private long productionSourceNumber(AppService service,String name,Long preBuildTaskId){
    long high=legacyProductionNumber(name);
    if(preBuildTaskId!=null){var source=tasks.findById(preBuildTaskId).orElse(null);
      if(source!=null&&Objects.equals(source.getServiceId(),service.getId())&&source.getTaskKind()==TaskKind.BUILD&&"MOBILE".equals(source.getDeliveryType())&&"test".equals(source.getEnvironmentName())&&!source.isSimulate()){
        var config=FlutterBuildConfiguration.fromTask(source);if(config.versionName()!=null)high=Math.max(high,nameNumber(config.versionName(),"test"));
      }
    }
    return high;
  }
  private static long legacyProductionNumber(String name){return name!=null&&name.endsWith("-prd")?baseNumber(name.substring(0,name.length()-4)):-1;}
  private MobileVersionSequence sequence(String key,boolean lock){
    if(!lock)return sequences.findBySequenceKey(key).orElse(null);
    var existing=sequences.lockByKey(key).orElse(null);
    return existing==null?sequences.saveAndFlush(new MobileVersionSequence(key,key.startsWith("NAME:")?-1:0)):existing;
  }
  private Candidate candidate(AppService service,ReleaseTask build){
    if(!Objects.equals(build.getServiceId(),service.getId())||build.getTaskKind()!=TaskKind.BUILD||build.getStatus()!=TaskStatus.SUCCESS||build.isSimulate()||!"MOBILE".equals(build.getDeliveryType())||!"test".equals(build.getEnvironmentName()))throw invalid("请选择当前 App 真实成功的 PRE 打包记录");
    Artifact artifact=artifacts.findByBuildTaskId(build.getId()).orElseThrow(()->invalid("PRE 构建没有成功制品"));
    MobileArtifact file=mobile.findByBuildTaskId(build.getId()).orElseThrow(()->invalid("PRE 构建没有安装包元数据"));
    var config=FlutterBuildConfiguration.fromTask(build);FlutterBuildConfiguration.verifyRepository(build,service);
    if(!Objects.equals(artifact.getServiceId(),service.getId())||!Objects.equals(file.getServiceId(),service.getId())||!Objects.equals(file.getArtifactId(),artifact.getId())||!Objects.equals(artifact.getBranch(),build.getBranch())||!Objects.equals(artifact.getCommitId(),build.getCommitId())||!Objects.equals(file.getCommitId(),build.getCommitId())||!"test".equals(file.getBuildEnvironmentName())||!"test".equals(config.environmentName())||!Objects.equals(config.applicationId(),file.getApplicationId())||!Objects.equals(config.expectedCertificateSha256(),file.getCertificateSha256())||!Objects.equals(config.buildMode(),file.getBuildMode())||!Objects.equals(config.outputFormat(),file.getFormat())||file.getSizeBytes()<=0||file.getSha256()==null||!file.getSha256().matches("[a-fA-F0-9]{64}")||(config.versionName()!=null&&!config.versionName().equals(file.getVersionName()))||(config.versionCode()!=null&&config.versionCode()!=file.getVersionCode()))throw invalid("PRE 制品与锁定构建记录不一致，不能作为生产来源");
    validateCommit(build.getCommitId());validateBranch(build.getBranch());String base=baseString(nameNumber(file.getVersionName(),"test"));
    return new Candidate(build.getId(),artifact.getId(),file.getApplicationId(),build.getCreatedAt(),file.getVersionName(),file.getVersionCode(),base,build.getBranch(),build.getCommitId());
  }
  private AppService app(long id){AppService service=services.findById(id).orElseThrow(()->invalid("App 不存在"));if(service.getServiceType()!=ServiceType.FLUTTER_ANDROID||!service.isEnabled())throw invalid("请选择已启用的 Flutter Android App");return service;}
  private static String nameKey(AppService service,String env){return "NAME:"+service.getId()+":"+env+("prod".equals(env)?":PLAIN_V1":"");}
  private static String productionSourceKey(AppService service){return "NAME:"+service.getId()+":prod";}
  private static String suffix(String env){return switch(env){case "dev"->"-dev";case "test"->"-pre";case "prod"->"";default->throw invalid("App 环境无效");};}
  static long baseNumber(String base){if(base==null||!base.matches("(?:0|[1-9][0-9]{0,9})\\.[0-9]\\.[0-9]"))throw invalid("历史 App 版本名不符合自动编号规则，请先核对版本基线");String[] parts=base.split("\\.");long value=Long.parseLong(parts[0])*100+Long.parseLong(parts[1])*10+Long.parseLong(parts[2]);if(value>MAX_BASE)throw invalid("历史 App 版本名超出自动编号范围");return value;}
  private static long nameNumber(String name,String env){if("prod".equals(env)&&legacyProductionNumber(name)>=0)return -1;String suffix=suffix(env);if(name==null||!name.endsWith(suffix))throw invalid("历史 App 版本名不符合 "+suffix+" 自动编号规则，请先核对版本基线");return baseNumber(name.substring(0,name.length()-suffix.length()));}
  private static String baseString(long value){return value/100+"."+(value/10)%10+"."+value%10;}
  private static void validateCommit(String value){if(value==null||!value.matches("[a-fA-F0-9]{40,64}"))throw invalid("请指定完整 Commit");}
  private static void validateBranch(String value){if(value==null||value.isBlank()||value.length()>120||value.chars().anyMatch(Character::isISOControl))throw invalid("请指定有效构建分支");}
  private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
