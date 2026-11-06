package com.local.release.security;

import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.*;
import com.local.release.service.ReleaseExecutor;
import com.local.release.web.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import java.time.*;
import java.util.*;

/** Apply family filtering at the HTTP boundary, including aggregate views and the shared queue. */
@ControllerAdvice
public class AppResponseIsolation implements ResponseBodyAdvice<Object> {
  private final AppApiScope scope;private final AppServiceRepository services;private final ReleaseTaskRepository tasks;private final ArtifactRepository artifacts;
  public AppResponseIsolation(AppApiScope scope,AppServiceRepository services,ReleaseTaskRepository tasks,ArtifactRepository artifacts){this.scope=scope;this.services=services;this.tasks=tasks;this.artifacts=artifacts;}
  @Override public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter){return method.getContainingClass().getPackageName().equals("com.local.release.web");}
  @Override public Object beforeBodyWrite(Object body,MethodParameter method,MediaType type,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){
    boolean app=AppApiScope.appPath(request.getURI().getPath());
    if(body instanceof List<?> list)return list.stream().filter(item->visible(item,app)).toList();
    if(body instanceof ReleaseHistoryView history)return new ReleaseHistoryView(history.environment(),history.services().stream().filter(s->(s.serviceType()==ServiceType.FLUTTER_ANDROID||s.latestAttempt()!=null&&"MOBILE".equals(s.latestAttempt().task().deliveryType()))==app).toList(),history.records().stream().filter(r->visible(r.task(),app)).toList());
    if(body instanceof ReleaseExecutor.QueueSnapshot queue){
      boolean visible=scope.taskVisible(queue.runningTaskId(),app);
      String runtime=queue.runningRuntimeOperation();if(app&&runtime!=null)runtime="后台任务执行中";
      if(queue.runningTaskId()!=null&&!visible)runtime="其他任务执行中";
      var cloud=queue.cloudRunningTasks().stream().filter(t->scope.taskVisible(t.taskId(),app)).toList();
      if(cloud.size()<queue.cloudRunningTasks().size()&&runtime==null)runtime="其他任务执行中";
      return new ReleaseExecutor.QueueSnapshot(visible?queue.runningTaskId():null,visible?queue.runningTaskKind():null,visible?queue.runningServiceId():null,queue.pendingCount(),queue.pendingTasks().stream().filter(t->scope.taskVisible(t.taskId(),app)).toList(),queue.capacity(),runtime,queue.pendingRuntimeOperations(),cloud,queue.cloudConcurrency());
    }
    if(request.getURI().getPath().equals("/api/dashboard")&&body instanceof Map<?,?> map&&map.containsKey("serviceCount"))return dashboard();
    return body;
  }
  private boolean visible(Object item,boolean app){
    if(item instanceof AppService service)return AppApiScope.isApp(service)==app;
    if(item instanceof ReleaseTaskView task)return "MOBILE".equals(task.deliveryType())==app;
    if(item instanceof ReleaseTask task)return AppApiScope.isApp(task)==app;
    if(item instanceof Artifact artifact)return scope.isApp(artifact)==app;
    return true;
  }
  private Map<String,Object> dashboard(){
    List<ReleaseTask> all=tasks.findAllByOrderByCreatedAtDesc().stream().filter(t->!AppApiScope.isApp(t)).toList();
    List<ReleaseTask> deploys=all.stream().filter(t->t.getTaskKind()==TaskKind.DEPLOY).toList();
    LocalDateTime today=LocalDate.now().atStartOfDay(),tomorrow=today.plusDays(1);
    List<ReleaseTask> todays=all.stream().filter(t->t.getCreatedAt()!=null&&!t.getCreatedAt().isBefore(today)&&t.getCreatedAt().isBefore(tomorrow)).toList();
    Map<String,Object> result=new LinkedHashMap<>();result.put("serviceCount",services.findAll().stream().filter(s->!AppApiScope.isApp(s)).count());result.put("releaseCount",deploys.size());result.put("buildCount",all.stream().filter(t->t.getTaskKind()==TaskKind.BUILD).count());result.put("todayReleaseCount",todays.stream().filter(t->t.getTaskKind()==TaskKind.DEPLOY).count());result.put("todayBuildCount",todays.stream().filter(t->t.getTaskKind()==TaskKind.BUILD).count());result.put("runningCount",all.stream().filter(t->t.getStatus()==TaskStatus.RUNNING).count());result.put("queuedCount",all.stream().filter(t->t.getStatus()==TaskStatus.PENDING).count());result.put("failedCount",all.stream().filter(t->t.getStatus()==TaskStatus.FAILED).count());result.put("todayFailedCount",todays.stream().filter(t->t.getStatus()==TaskStatus.FAILED).count());result.put("artifactCount",artifacts.findAll().stream().filter(a->!scope.isApp(a)).count());result.put("recentReleases",ReleaseTaskView.list(deploys.stream().limit(8).toList(),services));return result;
  }
}
