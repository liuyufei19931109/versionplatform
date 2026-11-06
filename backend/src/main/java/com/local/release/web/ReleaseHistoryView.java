package com.local.release.web;

import com.local.release.domain.ModelEnums.ServiceType;
import java.util.List;

/** Read-only, explicitly selected fields: never serialize a service, environment or artifact entity. */
public record ReleaseHistoryView(String environment, List<ServiceSummary> services, List<HistoryEntry> records) {
  public record HistoryEntry(ReleaseTaskView task, String artifactVersion, Long buildTaskId,
    String imageReference, int targetNodeCount, boolean targetScopeKnown) {}

  /** configured: enabled environment with bound nodes; enabled: the service itself is enabled. */
  public record ServiceSummary(Long serviceId, String serviceName, String serviceKey, ServiceType serviceType,
    boolean configured, boolean enabled, int nodeCount, HistoryEntry latestSuccessful, HistoryEntry latestAttempt,String displayIcon) {}
}
