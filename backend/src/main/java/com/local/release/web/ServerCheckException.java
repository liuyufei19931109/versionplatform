package com.local.release.web;

/** Preserve check stage without reflecting raw process output or credentials. */
public final class ServerCheckException extends RuntimeException {
  private final boolean sshConnected;
  public ServerCheckException(boolean sshConnected,Exception cause){
    super(sshConnected?"SSH 已连接；Compose 配置检查失败":"SSH 连接失败",cause);
    this.sshConnected=sshConnected;
  }
  public boolean isSshConnected(){return sshConnected;}
}
