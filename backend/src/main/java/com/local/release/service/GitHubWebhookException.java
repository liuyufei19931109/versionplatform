package com.local.release.service;
public class GitHubWebhookException extends IllegalArgumentException {
  private final int status;
  private final String code;
  public GitHubWebhookException(int status,String code,String message){super(message);this.status=status;this.code=code;}
  public int status(){return status;} public String code(){return code;}
}
