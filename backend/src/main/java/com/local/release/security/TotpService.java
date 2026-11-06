package com.local.release.security;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.local.release.domain.TotpCredential;
import com.local.release.repo.TotpCredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TotpService {
  private static final String ISSUER = "发版平台";
  private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int MAX_FAILURES = 5;
  private final TotpCredentialRepository credentials;
  private final TotpSecretCrypto crypto;
  private final Clock clock;
  private final Map<String, PendingSetup> pending = new ConcurrentHashMap<>();

  private record PendingSetup(String secret, Instant expiresAt, int attempts) {}
  public record SetupResult(String qrCodeDataUrl, String manualKey, Instant expiresAt) {}
  public record Availability(boolean enabled, Instant serverTime, Instant nextCodeAvailableAt, long retryAfterSeconds) {}

  @Autowired
  public TotpService(TotpCredentialRepository credentials, TotpSecretCrypto crypto) {
    this(credentials, crypto, Clock.systemDefaultZone());
  }

  TotpService(TotpCredentialRepository credentials, TotpSecretCrypto crypto, Clock clock) {
    this.credentials = credentials;
    this.crypto = crypto;
    this.clock = clock;
  }

  public boolean isEnabled(String username) {
    return credentials.findByUsername(username).map(TotpCredential::isEnabled).orElse(false);
  }

  public Availability availability(String username) {
    Instant now = clock.instant();
    return credentials.findByUsername(username).filter(TotpCredential::isEnabled)
      .map(credential -> {
        Instant next = nextAvailable(credential, now);
        if (credential.getLockedUntil() != null) {
          Instant unlocked = credential.getLockedUntil().atZone(clock.getZone()).toInstant();
          if (unlocked.isAfter(next)) next = unlocked;
        }
        return new Availability(true, now, next, secondsUntil(now, next));
      }).orElse(new Availability(false, now, now, 0));
  }

  @Transactional(noRollbackFor = TotpChallengeException.class)
  public SetupResult beginSetup(String username, String currentCode) {
    // Acquire the row lock before loading the credential into this transaction's persistence context.
    if (credentials.findByUsernameForUpdate(username).filter(TotpCredential::isEnabled).isPresent()) {
      if (currentCode == null || currentCode.isBlank()) throw new IllegalArgumentException("更换绑定前请输入当前动态码");
      verifyAndConsume(username, currentCode);
    }
    byte[] secretBytes = new byte[20];
    RANDOM.nextBytes(secretBytes);
    String secret = base32Encode(secretBytes);
    Instant expiresAt = clock.instant().plusSeconds(10 * 60);
    pending.put(username, new PendingSetup(secret, expiresAt, 0));
    String label = ISSUER + ":" + username;
    String uri = "otpauth://totp/" + url(label) + "?secret=" + secret + "&issuer=" + url(ISSUER) + "&algorithm=SHA1&digits=6&period=30";
    return new SetupResult(qrDataUrl(uri), groupSecret(secret), expiresAt);
  }

  @Transactional
  public void confirmSetup(String username, String code) {
    PendingSetup setup = pending.get(username);
    if (setup == null || !setup.expiresAt().isAfter(clock.instant())) {
      pending.remove(username);
      throw new IllegalArgumentException("绑定二维码已过期，请重新生成");
    }
    Match match = matches(setup.secret(), code, currentStep());
    if (!match.matched()) {
      int attempts = setup.attempts() + 1;
      if (attempts >= MAX_FAILURES) pending.remove(username);
      else pending.put(username, new PendingSetup(setup.secret(), setup.expiresAt(), attempts));
      throw new IllegalArgumentException(attempts >= MAX_FAILURES ? "验证失败次数过多，请重新生成二维码" : "动态码不正确");
    }
    TotpCredential credential = credentials.findByUsernameForUpdate(username)
      .orElseGet(() -> new TotpCredential(username, crypto.encrypt(setup.secret())));
    credential.setEncryptedSecret(crypto.encrypt(setup.secret()));
    credential.setEnabled(true);
    credential.setFailedAttempts(0);
    credential.setLockedUntil(null);
    credential.setLastUsedTimeStep(match.step());
    credentials.save(credential);
    pending.remove(username);
  }

  @Transactional(noRollbackFor = TotpChallengeException.class)
  public void verifyAndConsume(String username, String code) {
    TotpCredential credential = credentials.findByUsernameForUpdate(username)
      .filter(TotpCredential::isEnabled)
      .orElseThrow(() -> new IllegalArgumentException("尚未绑定 Google Authenticator，请先完成二次认证设置"));
    LocalDateTime now = LocalDateTime.now(clock);
    if (credential.getLockedUntil() != null && credential.getLockedUntil().isAfter(now)) {
      throw challenge("TOTP_LOCKED", "动态码连续错误次数过多，请在倒计时结束后重试", credential.getLockedUntil().atZone(clock.getZone()).toInstant());
    }
    Match match = matches(crypto.decrypt(credential.getEncryptedSecret()), code, currentStep());
    if (!match.matched()) {
      int failures = credential.getFailedAttempts() + 1;
      credential.setFailedAttempts(failures);
      if (failures >= MAX_FAILURES) {
        credential.setLockedUntil(now.plusMinutes(5));
        credential.setFailedAttempts(0);
      }
      credentials.save(credential);
      if (failures >= MAX_FAILURES) throw challenge("TOTP_LOCKED", "动态码连续错误次数过多，已锁定 5 分钟", credential.getLockedUntil().atZone(clock.getZone()).toInstant());
      throw challenge("TOTP_INVALID", "动态码不正确，请输入手机上当前显示的 6 位数字", clock.instant());
    }
    if (credential.getLastUsedTimeStep() != null && match.step() <= credential.getLastUsedTimeStep()) {
      throw challenge("TOTP_REUSED", "该动态码已用于登录或其他确认，请等待手机显示新的动态码后重试", nextAvailable(credential, clock.instant()));
    }
    credential.setLastUsedTimeStep(match.step());
    credential.setFailedAttempts(0);
    credential.setLockedUntil(null);
    credentials.save(credential);
  }

  private record Match(boolean matched, long step) {}

  private Match matches(String secret, String rawCode, long currentStep) {
    String code = rawCode == null ? "" : rawCode.replaceAll("\\s", "");
    if (!code.matches("\\d{6}")) return new Match(false, -1);
    byte[] key = base32Decode(secret);
    for (long step = currentStep - 1; step <= currentStep + 1; step++) {
      if (constantTimeEquals(generateCode(key, step), code)) return new Match(true, step);
    }
    return new Match(false, -1);
  }

  private long currentStep() { return clock.instant().getEpochSecond() / 30; }

  private Instant nextAvailable(TotpCredential credential, Instant now) {
    Long lastUsed = credential.getLastUsedTimeStep();
    if (lastUsed == null) return now;
    Instant next = Instant.ofEpochSecond((lastUsed + 1) * 30);
    return next.isAfter(now) ? next : now;
  }

  private static long secondsUntil(Instant now, Instant next) {
    return Math.max(0, (next.toEpochMilli() - now.toEpochMilli() + 999) / 1000);
  }

  private TotpChallengeException challenge(String code, String message, Instant next) {
    return new TotpChallengeException(code, message, clock.instant(), next);
  }

  private static String generateCode(byte[] secret, long step) {
    try {
      byte[] counter = new byte[8];
      long value = step;
      for (int i = 7; i >= 0; i--) { counter[i] = (byte) value; value >>>= 8; }
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      byte[] hash = mac.doFinal(counter);
      int offset = hash[hash.length - 1] & 0x0f;
      int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
        | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
      return String.format("%06d", binary % 1_000_000);
    } catch (Exception e) {
      throw new IllegalStateException("无法生成动态码", e);
    }
  }

  private static boolean constantTimeEquals(String left, String right) {
    int diff = left.length() ^ right.length();
    for (int i = 0; i < Math.min(left.length(), right.length()); i++) diff |= left.charAt(i) ^ right.charAt(i);
    return diff == 0;
  }

  private static String base32Encode(byte[] input) {
    StringBuilder output = new StringBuilder();
    int buffer = 0, bits = 0;
    for (byte value : input) {
      buffer = (buffer << 8) | (value & 0xff);
      bits += 8;
      while (bits >= 5) { output.append(BASE32[(buffer >> (bits - 5)) & 31]); bits -= 5; }
    }
    if (bits > 0) output.append(BASE32[(buffer << (5 - bits)) & 31]);
    return output.toString();
  }

  private static byte[] base32Decode(String input) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int buffer = 0, bits = 0;
    for (char c : input.replace("=", "").toUpperCase().toCharArray()) {
      int value = c >= 'A' && c <= 'Z' ? c - 'A' : c >= '2' && c <= '7' ? c - '2' + 26 : -1;
      if (value < 0) continue;
      buffer = (buffer << 5) | value;
      bits += 5;
      if (bits >= 8) { output.write((buffer >> (bits - 8)) & 0xff); bits -= 8; }
    }
    return output.toByteArray();
  }

  private static String qrDataUrl(String content) {
    try {
      BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 300, 300);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      MatrixToImageWriter.writeToStream(matrix, "PNG", output);
      return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    } catch (Exception e) {
      throw new IllegalStateException("无法生成绑定二维码", e);
    }
  }

  private static String url(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
  private static String groupSecret(String secret) { return secret.replaceAll("(.{4})(?!$)", "$1 "); }
}
