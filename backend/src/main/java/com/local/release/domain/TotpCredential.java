package com.local.release.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "totp_credential")
public class TotpCredential extends BaseEntity {
  @Column(nullable = false, unique = true, length = 64)
  private String username;

  @Column(nullable = false, length = 1000)
  private String encryptedSecret;

  @Column(nullable = false)
  private boolean enabled = true;

  @Column(nullable = false)
  private int failedAttempts;

  private LocalDateTime lockedUntil;
  private Long lastUsedTimeStep;

  protected TotpCredential() {}

  public TotpCredential(String username, String encryptedSecret) {
    this.username = username;
    this.encryptedSecret = encryptedSecret;
  }

  public String getUsername() { return username; }
  public String getEncryptedSecret() { return encryptedSecret; }
  public void setEncryptedSecret(String value) { encryptedSecret = value; }
  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean value) { enabled = value; }
  public int getFailedAttempts() { return failedAttempts; }
  public void setFailedAttempts(int value) { failedAttempts = value; }
  public LocalDateTime getLockedUntil() { return lockedUntil; }
  public void setLockedUntil(LocalDateTime value) { lockedUntil = value; }
  public Long getLastUsedTimeStep() { return lastUsedTimeStep; }
  public void setLastUsedTimeStep(Long value) { lastUsedTimeStep = value; }
}
