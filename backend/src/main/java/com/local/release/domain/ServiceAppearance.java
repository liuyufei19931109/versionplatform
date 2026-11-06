package com.local.release.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Presentation metadata only, stored in the existing service configuration. */
public final class ServiceAppearance {
  private static final ObjectMapper JSON = new ObjectMapper();
  public static final Set<String> ICONS = Set.of("server","users","document","compute","tasks","package","cluster","sync","storage","reports","approval","notifications","files","settings","database","api","gateway","website","operations","mobile");
  private ServiceAppearance() {}
  public static String icon(String config) {
    try { var value=JSON.readTree(config).path("displayIcon");return value.isTextual()&&ICONS.contains(value.asText())?value.asText():null; }
    catch(Exception ignored){return null;}
  }
  public static String configuration(String previous,String requested) {
    // An older client has no icon control: preserve the saved icon if its field is absent.
    ObjectNode root;
    try { var parsed=JSON.readTree(requested==null?"{}":requested);if(!(parsed instanceof ObjectNode object))return requested;root=object; }
    catch(Exception ignored){return requested;}
    if(root.has("displayIcon")) {
      var value=root.get("displayIcon");
      if(!value.isTextual()||!value.asText().isEmpty()&&!ICONS.contains(value.asText()))throw new IllegalArgumentException("请选择内置服务图标，或恢复自动匹配");
    } else {String icon=icon(previous);if(icon!=null)root.put("displayIcon",icon);}
    return root.toString();
  }
}
