package com.local.release.web;

import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.service.ReleaseExecutor;
import com.local.release.service.BuildAutoPublishService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController @RequestMapping({"/api/builds","/api/apps/builds"})
public class BuildController {
  private final ReleaseTaskRepository tasks;private final ReleaseStepRepository steps;private final AppServiceRepository services;private final ReleaseExecutor executor;
  public BuildController(ReleaseTaskRepository tasks,ReleaseStepRepository steps,AppServiceRepository services,ReleaseExecutor executor){this.tasks=tasks;this.steps=steps;this.services=services;this.executor=executor;}
  public record CreateBuild(@NotNull Long serviceId,String branch,String buildNote,Boolean simulate,@NotBlank String commitId,@NotBlank @Size(max=80) String requestId,String environmentName,Long preBuildTaskId,BuildAutoPublishService.Target autoPublish){
    public CreateBuild(Long serviceId,String branch,String buildNote,Boolean simulate,String commitId,String requestId){this(serviceId,branch,buildNote,simulate,commitId,requestId,null,null,null);}
    public CreateBuild(Long serviceId,String branch,String buildNote,Boolean simulate,String commitId,String requestId,String environmentName){this(serviceId,branch,buildNote,simulate,commitId,requestId,environmentName,null,null);}
    public CreateBuild(Long serviceId,String branch,String buildNote,Boolean simulate,String commitId,String requestId,String environmentName,Long preBuildTaskId){this(serviceId,branch,buildNote,simulate,commitId,requestId,environmentName,preBuildTaskId,null);}
  }
  public record RetryBuild(@NotBlank @Size(max=80) String requestId){}
  public record BuildDetail(ReleaseTaskView task,List<ReleaseStep> steps,ModelEnums.ServiceType serviceType,BuildAutoPublishService.View autoPublish,String cloudRunUrl){}
  @GetMapping public List<ReleaseTaskView> list(@RequestParam(required=false) Long serviceId){List<ReleaseTask> result=serviceId==null?tasks.findByTaskKindOrderByCreatedAtDesc(ModelEnums.TaskKind.BUILD):tasks.findByServiceIdAndTaskKindOrderByCreatedAtDesc(serviceId,ModelEnums.TaskKind.BUILD);return ReleaseTaskView.list(result,services);}
  @PostMapping public ReleaseTask create(@Valid @RequestBody CreateBuild r,Principal principal){return r.autoPublish()!=null?executor.createBuild(r.serviceId(),r.branch(),r.buildNote(),r.simulate()!=null&&r.simulate(),principal.getName(),r.commitId(),r.requestId(),r.environmentName(),r.preBuildTaskId(),r.autoPublish()):r.preBuildTaskId()==null?executor.createBuild(r.serviceId(),r.branch(),r.buildNote(),r.simulate()!=null&&r.simulate(),principal.getName(),r.commitId(),r.requestId(),r.environmentName()):executor.createBuild(r.serviceId(),r.branch(),r.buildNote(),r.simulate()!=null&&r.simulate(),principal.getName(),r.commitId(),r.requestId(),r.environmentName(),r.preBuildTaskId());}
  @GetMapping("/queue") public ReleaseExecutor.QueueSnapshot queue(){return executor.queue();}
  @GetMapping("/{id}") public BuildDetail detail(@PathVariable Long id){ReleaseTask task=find(id);if(task.getTaskKind()!=ModelEnums.TaskKind.BUILD)throw new IllegalArgumentException("不是打包任务");ReleaseTaskView view=ReleaseTaskView.one(task,services);return new BuildDetail(view,steps.findByReleaseTaskIdOrderById(id),view.serviceType(),executor.buildAutoPublish(id),executor.cloudRunUrl(id));}
  @PostMapping("/{id}/retry") public Map<String,String> retry(@PathVariable Long id,@Valid @RequestBody RetryBuild request,Principal principal){executor.retryBuild(id,request.requestId(),principal.getName());return Map.of("message","打包任务已重新加入执行队列");}
  @PostMapping("/{id}/cancel") public Map<String,String> cancel(@PathVariable Long id){executor.cancelBuild(id);return Map.of("message","取消请求已提交，请等待任务状态更新");}
  private ReleaseTask find(Long id){return tasks.findById(id).orElseThrow(()->new IllegalArgumentException("打包任务不存在"));}
}
