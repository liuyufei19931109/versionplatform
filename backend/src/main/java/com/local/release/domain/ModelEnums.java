package com.local.release.domain;

public final class ModelEnums {
  private ModelEnums() {}
  public enum TaskStatus { PENDING, RUNNING, SUCCESS, FAILED, CANCELLED, WAITING_CONFIRMATION }
  public enum TaskKind { BUILD, DEPLOY }
  public enum ServiceType { BACKEND, FRONTEND, FLUTTER_ANDROID }
  public enum ImageBuildMode { STATIC_NGINX, REPOSITORY_DOCKERFILE }
  public enum StepStatus { WAITING, RUNNING, SUCCESS, FAILED, SKIPPED }
  public enum StepType { GIT_PULL, BUILD_JAR, BUILD_IMAGE, PUSH_IMAGE, SSH_CONNECT, START_SERVICE, HEALTH_CHECK, BUILD_AAB, VALIDATE_AAB, ARCHIVE_ARTIFACT, BUILD_APK, VALIDATE_APK }
}
