package com.local.release.security;

import java.time.Instant;

/** An expected authentication rejection whose failure counters must be committed. */
public class TotpChallengeException extends IllegalArgumentException {
  private final String code;
  private final Instant serverTime;
  private final Instant nextCodeAvailableAt;

  public TotpChallengeException(String code, String message, Instant serverTime, Instant nextCodeAvailableAt) {
    super(message);
    this.code = code;
    this.serverTime = serverTime;
    this.nextCodeAvailableAt = nextCodeAvailableAt;
  }

  public String getCode() { return code; }
  public Instant getServerTime() { return serverTime; }
  public Instant getNextCodeAvailableAt() { return nextCodeAvailableAt; }
  public long getRetryAfterSeconds() {
    return Math.max(0, (nextCodeAvailableAt.toEpochMilli() - serverTime.toEpochMilli() + 999) / 1000);
  }
}
