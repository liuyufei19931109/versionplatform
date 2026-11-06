package com.local.release.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;

@Component
public class ProductionMergeDispatcher {
  private final ProductionMergeService service;private final ObjectProvider<ReleaseExecutor> executor;private final boolean enabled;
  private ScheduledExecutorService worker;
  public ProductionMergeDispatcher(ProductionMergeService service,ObjectProvider<ReleaseExecutor> executor,@Value("${release.production-merge-enabled:false}") boolean enabled){this.service=service;this.executor=executor;this.enabled=enabled;}
  @Order(110) @EventListener(ApplicationReadyEvent.class) public synchronized void start(){
    if(!enabled||worker!=null)return;
    worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"production-code-merge");t.setDaemon(true);return t;});
    worker.execute(()->safe(service::recoverInterrupted));worker.scheduleWithFixedDelay(()->safe(this::tick),2,3,TimeUnit.SECONDS);
  }
  void tick(){
    for(var task:service.pendingSuccesses())safe(()->service.ensureForSuccessfulDeployment(task));
    Long id=service.nextId();if(id!=null)executor.getObject().executeRuntimeOperation("生产代码合回 #"+id,()->service.execute(id));
  }
  private void safe(Runnable work){try{work.run();}catch(Exception error){LoggerFactory.getLogger(getClass()).warn("Production merge deferred; exceptionType={}",error.getClass().getSimpleName());}}
  @PreDestroy public synchronized void stop(){if(worker!=null)worker.shutdownNow();}
}
