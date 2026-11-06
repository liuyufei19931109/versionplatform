package com.local.release.security;

import java.util.*;

/** Preserve the old dashboard-to-runtime grant until an administrator explicitly edits permissions. */
public final class MenuPermissions {
  public static final List<String> ALL = List.of("dashboard", "runtime", "services", "servers", "builds", "release", "releases", "apps", "versions", "miniprograms");
  private static final String RUNTIME_EXPLICIT = "_runtime_explicit";
  private MenuPermissions() {}

  public static Set<String> effective(Collection<String> stored) {
    Set<String> values = stored == null ? Set.of() : new LinkedHashSet<>(stored);
    Set<String> result = new LinkedHashSet<>();
    for (String menu : ALL) if (values.contains(menu)) result.add(menu);
    if (values.contains("dashboard") && !values.contains(RUNTIME_EXPLICIT)) result.add("runtime");
    return result;
  }

  public static List<String> forStorage(Collection<String> selected) {
    List<String> result = new ArrayList<>();
    for (String menu : ALL) if (selected != null && selected.contains(menu)) result.add(menu);
    result.add(RUNTIME_EXPLICIT);
    return result;
  }
}
