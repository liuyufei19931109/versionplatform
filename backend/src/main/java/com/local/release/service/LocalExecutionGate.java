package com.local.release.service;

import org.springframework.stereotype.Component;
import java.util.concurrent.locks.ReentrantLock;

/** Cloud compilation can overlap; all resource-heavy local work and remote deployment stay serial. */
@Component
public class LocalExecutionGate {
  final ReentrantLock lock=new ReentrantLock(true);
  @FunctionalInterface public interface Action<T>{T run() throws Exception;}
  public <T> T call(Action<T> action)throws Exception {lock.lockInterruptibly();try{return action.run();}finally{lock.unlock();}}
}
