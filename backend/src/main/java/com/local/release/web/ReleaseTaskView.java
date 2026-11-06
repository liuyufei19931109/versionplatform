package com.local.release.web;

import com.local.release.domain.AppService;
import com.local.release.domain.ReleaseTask;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.AppServiceRepository;
import java.time.LocalDateTime;
import java.util.*;

/** Explicit public task fields; service configuration and request keys never enter this DTO. */
public record ReleaseTaskView(
  Long id, Long serviceId, String serviceName, String serviceKey, ServiceType serviceType, TaskKind taskKind,
  Long artifactId, String environmentName, String branch, String commitId, TaskStatus status,
  StepType currentStep, String errorMessage, String releaseNote, String createdBy,
  String targetServerIds, boolean simulate, LocalDateTime startedAt, LocalDateTime finishedAt,
  LocalDateTime createdAt, LocalDateTime updatedAt, String deliveryType, String mobileOutputFormat, String mobileBuildMode,
  String versionName,Long versionCode,Long preBuildTaskId,String mobileBuildEngine,String displayIcon,String frontendBuildEngine,String backendBuildEngine
) {
  private record ServiceMetadata(String name, String key, ServiceType type, String displayIcon) {}

  public static List<ReleaseTaskView> list(List<ReleaseTask> tasks, AppServiceRepository services) {
    if (tasks.isEmpty()) return List.of();
    Set<Long> serviceIds = new LinkedHashSet<>();
    for (ReleaseTask task : tasks) if (task.getServiceId() != null) serviceIds.add(task.getServiceId());
    Map<Long, ServiceMetadata> metadata = new HashMap<>();
    if (!serviceIds.isEmpty()) {
      for (AppService service : services.findAllById(serviceIds))
        metadata.put(service.getId(), new ServiceMetadata(service.getName(), service.getServiceKey(), service.getServiceType(), service.getDisplayIcon()));
    }
    return tasks.stream().map(task -> from(task, metadata.get(task.getServiceId()))).toList();
  }

  public static ReleaseTaskView one(ReleaseTask task, AppServiceRepository services) {
    return list(List.of(task), services).getFirst();
  }

  private static ReleaseTaskView from(ReleaseTask task, ServiceMetadata service) {
    String name = service == null ? "已删除服务 #" + task.getServiceId() : service.name();
    if (name == null || name.isBlank()) name = "未命名服务 #" + task.getServiceId();
    return new ReleaseTaskView(task.getId(), task.getServiceId(), name, service == null ? null : service.key(), service == null ? null : service.type(),
      task.getTaskKind(), task.getArtifactId(), task.getEnvironmentName(), task.getBranch(), task.getCommitId(),
      task.getStatus(), task.getCurrentStep(), task.getErrorMessage(), task.getReleaseNote(), task.getCreatedBy(),
      task.getTargetServerIds(), task.isSimulate(), task.getStartedAt(), task.getFinishedAt(), task.getCreatedAt(), task.getUpdatedAt(), task.getDeliveryType(),mobileFormat(task),mobileMode(task),mobileVersionName(task),mobileVersionCode(task),mobilePreSource(task),"MOBILE".equals(task.getDeliveryType())&&task.getTaskKind()==TaskKind.BUILD?task.getMobileBuildEngine():null,service==null?null:service.displayIcon(),task.getTaskKind()==TaskKind.BUILD&&service!=null&&service.type()==ServiceType.FRONTEND?task.getFrontendBuildEngine():null,task.getTaskKind()==TaskKind.BUILD&&service!=null&&service.type()==ServiceType.BACKEND?task.getBackendBuildEngine():null);
  }
  private static String mobileFormat(ReleaseTask task){if(!"MOBILE".equals(task.getDeliveryType())||task.getTaskKind()!=TaskKind.BUILD)return null;try{return com.local.release.service.FlutterBuildConfiguration.fromTask(task).outputFormat();}catch(IllegalArgumentException ignored){return null;}}
  private static String mobileMode(ReleaseTask task){if(!"MOBILE".equals(task.getDeliveryType())||task.getTaskKind()!=TaskKind.BUILD)return null;try{return com.local.release.service.FlutterBuildConfiguration.fromTask(task).buildMode();}catch(IllegalArgumentException ignored){return null;}}
  private static String mobileVersionName(ReleaseTask task){if(!"MOBILE".equals(task.getDeliveryType())||task.getTaskKind()!=TaskKind.BUILD)return null;try{return com.local.release.service.FlutterBuildConfiguration.fromTask(task).versionName();}catch(IllegalArgumentException ignored){return null;}}
  private static Long mobileVersionCode(ReleaseTask task){if(!"MOBILE".equals(task.getDeliveryType())||task.getTaskKind()!=TaskKind.BUILD)return null;try{return com.local.release.service.FlutterBuildConfiguration.fromTask(task).versionCode();}catch(IllegalArgumentException ignored){return null;}}
  private static Long mobilePreSource(ReleaseTask task){if(!"MOBILE".equals(task.getDeliveryType())||task.getTaskKind()!=TaskKind.BUILD)return null;try{return com.local.release.service.FlutterBuildConfiguration.taskPreBuildTaskId(task);}catch(IllegalArgumentException ignored){return null;}}
}
