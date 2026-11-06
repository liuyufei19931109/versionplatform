package com.local.release.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;

@Component
public class WecomNotificationDispatcher {
  private final WecomNotificationService service;
  private final boolean enabled;
  private ScheduledExecutorService executor;
  public WecomNotificationDispatcher(WecomNotificationService service,@Value("${release.wecom-dispatch-enabled:${RELEASE_WECOM_DISPATCH_ENABLED:true}}") boolean enabled){this.service=service;this.enabled=enabled;}
  @Order(100) @EventListener(ApplicationReadyEvent.class) public synchronized void start(){
    if(!enabled||executor!=null)return;
    executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"wecom-notifications");t.setDaemon(true);return t;});
    executor.execute(()->safe(service::recoverPending));
    executor.scheduleWithFixedDelay(()->safe(service::dispatchOne),2,1,TimeUnit.SECONDS);
  }
  private void safe(Runnable task){try{task.run();}catch(Exception error){LoggerFactory.getLogger(getClass()).warn("Notification dispatcher deferred; exceptionType={}",error.getClass().getSimpleName());}}
  @PreDestroy public synchronized void stop(){if(executor!=null)executor.shutdownNow();}
}
