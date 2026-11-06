package com.local.release.web;

import com.local.release.domain.AppService; import com.local.release.domain.ServiceAppearance; import com.local.release.repo.AppServiceRepository;
import com.local.release.domain.ModelEnums.ServiceType;
import com.local.release.service.GitRepositoryInspector;
import com.local.release.service.BuildConfiguration;
import com.local.release.service.FlutterBuildConfiguration;
import jakarta.validation.Valid; import jakarta.validation.constraints.NotBlank; import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;

@RestController @RequestMapping({"/api/services","/api/apps/services"})
public class ServiceController {
  private final AppServiceRepository repo;private final GitRepositoryInspector gitInspector;
  @Value("${release.registry-prefix:}") String registryPrefix;
  public ServiceController(AppServiceRepository repo,GitRepositoryInspector gitInspector){this.repo=repo;this.gitInspector=gitInspector;}
  public record ServiceRequest(@NotBlank String name,@NotBlank String serviceKey,ServiceType serviceType,String description,Boolean enabled,String gitRepo,String defaultBranch,String buildCommand,String workDirectory,String jarPath,String dockerfilePath,String imageRepository,String targetHost,String targetUser,String startCommand,String healthCheckUrl,String envJson,String configJson,String buildEngine){
    public ServiceRequest(String name,String serviceKey,ServiceType serviceType,String description,Boolean enabled,String gitRepo,String defaultBranch,String buildCommand,String workDirectory,String jarPath,String dockerfilePath,String imageRepository,String targetHost,String targetUser,String startCommand,String healthCheckUrl,String envJson,String configJson){this(name,serviceKey,serviceType,description,enabled,gitRepo,defaultBranch,buildCommand,workDirectory,jarPath,dockerfilePath,imageRepository,targetHost,targetUser,startCommand,healthCheckUrl,envJson,configJson,null);}
  }
  @GetMapping public List<AppService> list(){return repo.findAll();}
  @GetMapping("/{id}") public AppService one(@PathVariable Long id){return find(id);}
  @PostMapping public AppService create(@Valid @RequestBody ServiceRequest r){if(repo.existsByServiceKey(r.serviceKey()))throw new IllegalArgumentException("服务标识已存在");return repo.save(apply(new AppService(),r));}
  @PutMapping("/{id}") public AppService update(@PathVariable Long id,@Valid @RequestBody ServiceRequest r){AppService old=find(id);if(!old.getServiceKey().equals(r.serviceKey())&&repo.existsByServiceKey(r.serviceKey()))throw new IllegalArgumentException("服务标识已存在");return repo.save(apply(old,r));}
  @DeleteMapping("/{id}") public void delete(@PathVariable Long id){repo.delete(find(id));}
  @GetMapping("/{id}/branches") public List<String> branches(@PathVariable Long id)throws Exception{return gitInspector.branches(find(id));}
  @GetMapping("/{id}/branches/latest-commit") public GitRepositoryInspector.CommitPreview latestCommit(@PathVariable Long id,@RequestParam String branch)throws Exception{return gitInspector.latestCommit(find(id),branch);}
  private AppService find(Long id){return repo.findById(id).orElseThrow(()->new IllegalArgumentException("服务不存在"));}
  private AppService apply(AppService s,ServiceRequest r){
    ServiceType type=r.serviceType()==null?ServiceType.BACKEND:r.serviceType();
    if(type==ServiceType.FLUTTER_ANDROID){var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();boolean admin=auth!=null&&auth.getAuthorities().stream().anyMatch(a->"ROLE_ADMIN".equals(a.getAuthority()));FlutterBuildConfiguration.validateExternalFloors(s.getConfigJson(),r.configJson(),admin);}
    FlutterBuildConfiguration mobile=type==ServiceType.FLUTTER_ANDROID?(Boolean.FALSE.equals(r.enabled())?FlutterBuildConfiguration.parseDraft(r.configJson()):FlutterBuildConfiguration.parse(r.configJson())):null;
    String command=mobile==null?BuildConfiguration.command(r.buildCommand(),type):"flutter build "+(mobile.isApk()?"apk":"appbundle")+(mobile.isDebug()?" --debug":" --release");
    BuildConfiguration.repositoryPath(r.workDirectory(),"工作目录");
    BuildConfiguration.repositoryPath(r.jarPath(),"构建输出路径");
    BuildConfiguration.repositoryPath(r.dockerfilePath(),"Dockerfile 路径");
    if(r.buildEngine()!=null)s.setBuildEngine(r.buildEngine());
    s.setName(r.name());s.setServiceKey(r.serviceKey());s.setServiceType(type);s.setDescription(r.description());s.setEnabled(r.enabled()==null||r.enabled());s.setGitRepo(r.gitRepo());
    if(r.defaultBranch()!=null)s.setDefaultBranch(r.defaultBranch());
    s.setBuildCommand(command);
    s.setWorkDirectory(r.workDirectory());
    s.setJarPath(mobile!=null?mobile.outputPath():(r.jarPath()==null||r.jarPath().isBlank()?(type==ServiceType.FRONTEND?"dist":"target/*.jar"):r.jarPath()));
    s.setDockerfilePath(r.dockerfilePath()==null||r.dockerfilePath().isBlank()?"Dockerfile":r.dockerfilePath());
    String imageRepository=r.imageRepository();
    if(type!=ServiceType.FLUTTER_ANDROID&&(imageRepository==null||imageRepository.isBlank())&&registryPrefix!=null&&!registryPrefix.isBlank())imageRepository=registryPrefix.replaceAll("/+$","")+"/"+r.serviceKey();
    s.setImageRepository(imageRepository);s.setTargetHost(r.targetHost());if(r.targetUser()!=null)s.setTargetUser(r.targetUser());s.setStartCommand(r.startCommand());s.setHealthCheckUrl(r.healthCheckUrl());s.setEnvJson(r.envJson()==null?"{}":r.envJson());s.setConfigJson(ServiceAppearance.configuration(s.getConfigJson(),r.configJson()==null?"{}":r.configJson()));return s;
  }
}
