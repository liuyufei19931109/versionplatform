package com.local.release.security;

import com.local.release.domain.TotpCredential;
import com.local.release.repo.TotpCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(TotpServicePersistenceTest.Configuration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TotpServicePersistenceTest {
  // Public RFC 6238 test material; no application database or credential files are used.
  private static final String TEST_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
  private static final Instant NOW = Instant.parse("2026-09-05T08:00:10Z");
  @Autowired TotpService service;
  @Autowired TotpCredentialRepository credentials;
  @Autowired TotpSecretCrypto crypto;

  @TestConfiguration
  static class Configuration {
    @Bean TotpSecretCrypto testCrypto() {
      TotpSecretCrypto crypto = mock(TotpSecretCrypto.class);
      when(crypto.decrypt(anyString())).thenReturn(TEST_SECRET);
      when(crypto.encrypt(anyString())).thenAnswer(call -> call.getArgument(0));
      return crypto;
    }
    @Bean TotpService service(TotpCredentialRepository repository, TotpSecretCrypto crypto) {
      return new TotpService(repository, crypto, Clock.fixed(NOW, ZoneOffset.UTC));
    }
  }

  @BeforeEach
  void initialize() {
    credentials.deleteAll();
    credentials.saveAndFlush(new TotpCredential("admin", "test-only-encrypted-value"));
  }

  @Test
  void anotherAccountsCodeIsRejectedAndConsumptionAndLockoutRemainIndependent() throws Exception {
    // Separate public test key for yuan; never use real user credentials in tests.
    byte[] otherKey = "abcdefghijklmnopqrst".getBytes(StandardCharsets.US_ASCII);
    when(crypto.decrypt("yuan-test-key")).thenReturn("MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U");
    credentials.saveAndFlush(new TotpCredential("yuan","yuan-test-key"));
    String ownCode = currentCode(otherKey);
    assertThat(ownCode).isNotEqualTo(currentCode());
    assertThatThrownBy(() -> service.verifyAndConsume("yuan",currentCode())).isInstanceOf(TotpChallengeException.class);
    assertThat(credentials.findByUsername("admin").orElseThrow().getFailedAttempts()).isZero();
    service.verifyAndConsume("yuan",ownCode);
    assertThat(service.availability("yuan").retryAfterSeconds()).isEqualTo(20);
    assertThat(service.availability("admin").retryAfterSeconds()).isZero();
    service.verifyAndConsume("admin",currentCode());
    for(int i=0;i<5;i++)assertThatThrownBy(() -> service.verifyAndConsume("yuan","invalid")).isInstanceOf(TotpChallengeException.class);
    assertThat(service.availability("yuan").retryAfterSeconds()).isEqualTo(300);
    assertThat(credentials.findByUsername("admin").orElseThrow().getLockedUntil()).isNull();
  }

  @Test
  void failedAttemptsCommitDespiteAuthenticationRejectionAndEventuallyLock() {
    for (int attempt = 1; attempt <= 4; attempt++) {
      assertThatThrownBy(() -> service.verifyAndConsume("admin", "invalid"))
        .isInstanceOf(TotpChallengeException.class).hasMessageContaining("动态码不正确");
      assertThat(credentials.findByUsername("admin").orElseThrow().getFailedAttempts()).isEqualTo(attempt);
    }
    assertThatThrownBy(() -> service.verifyAndConsume("admin", "invalid"))
      .isInstanceOf(TotpChallengeException.class).hasMessageContaining("锁定 5 分钟");
    TotpCredential saved = credentials.findByUsername("admin").orElseThrow();
    assertThat(saved.getLockedUntil()).isEqualTo(LocalDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC));
    assertThat(service.availability("admin").retryAfterSeconds()).isEqualTo(300);
    assertThatThrownBy(() -> service.verifyAndConsume("admin", currentCode())).hasMessageContaining("倒计时");
  }

  @Test
  void loginConsumptionRequiresANewProductionCodeAndReportsServerTime() throws Exception {
    service.verifyAndConsume("admin", currentCode());
    TotpService.Availability availability = service.availability("admin");
    assertThat(availability.enabled()).isTrue();
    assertThat(availability.serverTime()).isEqualTo(NOW);
    assertThat(availability.nextCodeAvailableAt()).isEqualTo(Instant.parse("2026-09-05T08:00:30Z"));
    assertThat(availability.retryAfterSeconds()).isEqualTo(20);
    assertThatThrownBy(() -> service.verifyAndConsume("admin", currentCode()))
      .isInstanceOfSatisfying(TotpChallengeException.class, error -> {
        assertThat(error.getCode()).isEqualTo("TOTP_REUSED");
        assertThat(error.getRetryAfterSeconds()).isEqualTo(20);
      });
  }

  @Test
  void changingBindingAlsoCommitsFailedVerificationFromItsOuterTransaction() {
    assertThatThrownBy(() -> service.beginSetup("admin", "invalid"))
      .isInstanceOf(TotpChallengeException.class);
    assertThat(credentials.findByUsername("admin").orElseThrow().getFailedAttempts()).isEqualTo(1);
  }

  @Test
  void concurrentRequestsCannotConsumeTheSameTimeStepTwice() throws Exception {
    String code = currentCode();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Callable<String> verify = () -> {
        start.await(5, TimeUnit.SECONDS);
        try { service.verifyAndConsume("admin", code); return "ACCEPTED"; }
        catch (TotpChallengeException error) { return error.getCode(); }
      };
      Future<String> first = workers.submit(verify), second = workers.submit(verify);
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
        .containsExactlyInAnyOrder("ACCEPTED", "TOTP_REUSED");
    }
    assertThat(credentials.findByUsername("admin").orElseThrow().getLastUsedTimeStep())
      .isEqualTo(NOW.getEpochSecond() / 30);
  }

  private static String currentCode() throws Exception {
    return currentCode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
  }
  private static String currentCode(byte[] key) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(key, "HmacSHA1"));
    byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(NOW.getEpochSecond() / 30).array());
    int offset = hash[hash.length - 1] & 15;
    int value = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16)
      | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
    return String.format("%06d", value % 1_000_000);
  }
}
