package com.local.release;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class ReleasePlatformApplication {
  public static void main(String[] args) { SpringApplication.run(ReleasePlatformApplication.class, args); }
}
