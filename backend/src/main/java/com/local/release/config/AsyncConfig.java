package com.local.release.config;
import org.springframework.context.annotation.*; import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
@Configuration
public class AsyncConfig {
  @Bean(name="cloudBuildExecutor") ThreadPoolTaskExecutor cloudBuildExecutor(){
    ThreadPoolTaskExecutor e=new ThreadPoolTaskExecutor();
    // Admission is capped by ReleaseExecutor; queue absorbs a replacement while its predecessor exits.
    e.setCorePoolSize(8);e.setMaxPoolSize(8);e.setQueueCapacity(8);
    e.setThreadNamePrefix("cloud-build-");e.setWaitForTasksToCompleteOnShutdown(false);
    e.setAwaitTerminationSeconds(30);e.initialize();return e;
  }
  @Bean(name="taskExecutor") ThreadPoolTaskExecutor taskExecutor(){
    // Hard limit: all builds and deployments share one dispatcher.
    ThreadPoolTaskExecutor e=new ThreadPoolTaskExecutor();
    e.setCorePoolSize(1);e.setMaxPoolSize(1);e.setQueueCapacity(1);
    e.setThreadNamePrefix("release-");e.setWaitForTasksToCompleteOnShutdown(false);
    e.setAwaitTerminationSeconds(30);e.initialize();return e;
  }
}
