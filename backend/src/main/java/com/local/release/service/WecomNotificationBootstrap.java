package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import java.nio.file.*;

@Component
public class WecomNotificationBootstrap {
  private final WecomNotificationService service;private final ObjectMapper json;private final String filename;
  public WecomNotificationBootstrap(WecomNotificationService service,ObjectMapper json,@Value("${release.wecom-bootstrap-file:${RELEASE_WECOM_BOOTSTRAP_FILE:}}") String filename){this.service=service;this.json=json;this.filename=filename;}
  @Order(-90) @EventListener(ApplicationReadyEvent.class) public void initialize(){
    if(filename.isBlank())return;
    Path path=Path.of(filename).toAbsolutePath().normalize();if(!Files.exists(path))return;
    try{
      if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>16384)throw new IllegalArgumentException("Invalid bootstrap file");
      var root=json.readTree(Files.readString(path));
      var request=new WecomNotificationService.SettingsRequest(root.path("enabled").asBoolean(),root.path("webhook").asText(),root.path("groupName").asText(),root.path("publicBaseUrl").asText(),
        json.convertValue(root.path("environments"),new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>(){}),root.path("notifySuccess").asBoolean(),root.path("notifyFailure").asBoolean());
      boolean imported=service.initializeIfAbsent(request,root.path("testNotification").asBoolean(false));
      Files.delete(path);
      LoggerFactory.getLogger(getClass()).info("WeCom bootstrap consumed; imported={}",imported);
    }catch(Exception error){LoggerFactory.getLogger(getClass()).warn("WeCom bootstrap not consumed; exceptionType={}",error.getClass().getSimpleName());}
  }
}
