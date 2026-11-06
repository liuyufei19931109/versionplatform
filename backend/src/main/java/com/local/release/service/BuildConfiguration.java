package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.AppService;
import com.local.release.domain.ModelEnums.ImageBuildMode;
import com.local.release.domain.ModelEnums.ServiceType;
import java.util.regex.Pattern;

/** Defaults and early validation for the Linux build host. */
public final class BuildConfiguration {
  private static final Pattern WINDOWS_PATH = Pattern.compile("(?<![A-Za-z0-9])[A-Za-z]:[\\\\/]|(?:^|[\\s=\"'])\\\\\\\\[^\\\\\\s]+\\\\");
  private static final Pattern CMD_WRAPPER = Pattern.compile("(?is)^cmd(?:\\.exe)?\\s+/d\\s+/s\\s+/c\\s+(.+)$");
  private static final ObjectMapper JSON = new ObjectMapper();

  private BuildConfiguration() {}

  public static ImageBuildMode imageBuildMode(AppService service) {
    if (service.getServiceType() != ServiceType.FRONTEND) return ImageBuildMode.REPOSITORY_DOCKERFILE;
    try {
      String value = JSON.readTree(service.getConfigJson() == null ? "{}" : service.getConfigJson()).path("imageBuildMode").asText();
      return ImageBuildMode.REPOSITORY_DOCKERFILE.name().equals(value) ? ImageBuildMode.REPOSITORY_DOCKERFILE : ImageBuildMode.STATIC_NGINX;
    } catch (Exception ignored) {
      return ImageBuildMode.STATIC_NGINX;
    }
  }

  public static String command(String configured, ServiceType type) {
    if(type==ServiceType.FLUTTER_ANDROID)return "flutter build appbundle --release";
    String value = configured == null || configured.isBlank()
      ? (type == ServiceType.FRONTEND ? "npm ci && npm run build" : "mvn -B clean package -DskipTests")
      : configured.trim();
    rejectWindowsPath(value, "构建命令");
    var wrapper = CMD_WRAPPER.matcher(value);
    if (wrapper.matches()) {
      value = wrapper.group(1).trim();
      if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
    }
    value = value.replaceAll("(?i)(?<![A-Za-z0-9_./-])mvn\\.cmd(?![A-Za-z0-9_.-])", "mvn")
      .replaceAll("(?i)(?<![A-Za-z0-9_./-])mvnw\\.cmd(?![A-Za-z0-9_.-])", "./mvnw")
      .replaceAll("(?i)(?<![A-Za-z0-9_./-])gradlew\\.bat(?![A-Za-z0-9_.-])", "./gradlew")
      .replaceAll("(?i)(?<![A-Za-z0-9_./-])(npm|pnpm|yarn)\\.cmd(?![A-Za-z0-9_.-])", "$1");
    if (Pattern.compile("(?i)(?:^|[\\s;&|])(?:cmd(?:\\.exe)?|powershell(?:\\.exe)?)(?:\\s|$)").matcher(value).find())
      throw new IllegalArgumentException("构建服务器运行 Linux，请使用 Linux 构建命令");
    return value;
  }

  public static void rejectWindowsPath(String value, String label) {
    if (value != null && WINDOWS_PATH.matcher(value).find())
      throw new IllegalArgumentException(label + "包含 Windows 路径；请改为 Linux 路径或仓库内相对路径");
  }

  public static void repositoryPath(String value, String label) {
    if (value == null || value.isBlank()) return;
    rejectWindowsPath(value, label);
    if (value.startsWith("/") || value.startsWith("\\") || value.startsWith("~")
        || Pattern.compile("(?:^|/)\\.\\.(?:/|$)").matcher(value).find())
      throw new IllegalArgumentException(label + "必须是仓库内相对路径");
    if (value.contains("\\")) throw new IllegalArgumentException(label + "请使用正斜杠 / 分隔目录");
  }
}
