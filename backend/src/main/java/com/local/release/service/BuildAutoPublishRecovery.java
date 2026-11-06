package com.local.release.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** Recover transient enqueue/link failures even when the existing serial queue has become idle. */
@Component
public class BuildAutoPublishRecovery {
  private final ReleaseExecutor executor;
  private final boolean enabled;
  private ScheduledExecutorService worker;
  private volatile boolean stopping;
  public BuildAutoPublishRecovery(ReleaseExecutor executor,@Value("${release.auto-publish-recovery-enabled:${RELEASE_AUTO_PUBLISH_RECOVERY_ENABLED:true}}") boolean enabled){this.executor=executor;this.enabled=enabled;}
  @EventListener(ApplicationReadyEvent.class)
  public synchronized void start(){
    if(!enabled||stopping||worker!=null)return;
    worker=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("build-auto-publish-recovery").factory());
    worker.scheduleWithFixedDelay(this::tick,15,15,TimeUnit.SECONDS);
  }
  void tick(){if(enabled&&!stopping)executor.recoverAutomaticPublishing();}
  @PreDestroy public synchronized void stop(){stopping=true;if(worker!=null)worker.shutdownNow();}
}
