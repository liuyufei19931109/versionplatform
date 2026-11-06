package com.local.release.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/**
 * Takes a transactionally consistent H2 backup while the pool is still available.
 * Constructor injection registers this bean as a dependent of the DataSource, so Spring
 * destroys it before destroying the pool. This is a shutdown safety net, not a write gate:
 * abrupt process termination or writes committed after the snapshot are not covered.
 */
@Component
public final class H2ShutdownBackup {
  private static final Logger log = LoggerFactory.getLogger(H2ShutdownBackup.class);
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
  private final DataSource dataSource;
  private final Path backupRoot, credentialKey, totpKey;
  private final AtomicBoolean attempted = new AtomicBoolean();

  public H2ShutdownBackup(DataSource dataSource,
      @Value("${release.shutdown-backup-dir:${RELEASE_DATA_DIR:./data}/backups}") String backupDirectory,
      @Value("${release.credential-key-file:./data/credential-encryption.key}") String credentialKeyFile,
      @Value("${release.totp-key-file:./data/totp-encryption.key}") String totpKeyFile) {
    this.dataSource = dataSource;
    backupRoot = Path.of(backupDirectory).toAbsolutePath().normalize();
    credentialKey = Path.of(credentialKeyFile).toAbsolutePath().normalize();
    totpKey = Path.of(totpKeyFile).toAbsolutePath().normalize();
  }

  @PreDestroy
  public void backupBeforeShutdown() {
    if (!attempted.compareAndSet(false, true)) return;
    Path destination = backupRoot;
    try (Connection connection = dataSource.getConnection()) {
      String url = connection.getMetaData().getURL();
      if (!"H2".equals(connection.getMetaData().getDatabaseProductName()) || url == null || !url.startsWith("jdbc:h2:file:")) {
        log.info("H2_SHUTDOWN_BACKUP status=SKIPPED");
        return;
      }
      createPrivateDirectory(backupRoot);
      destination = backupRoot.resolve("shutdown-" + STAMP.format(Instant.now()) + "-" + UUID.randomUUID());
      createPrivateDirectory(destination);
      Path pendingZip = destination.resolve("database.zip.incomplete");
      createPrivateFile(pendingZip);
      // The JDBC command captures the open database; never copy its live .mv.db file.
      try (PreparedStatement backup = connection.prepareStatement("BACKUP TO ?")) {
        backup.setString(1, pendingZip.toString());
        backup.execute();
      }
      privateFile(pendingZip);
      validateZip(pendingZip);
      copyExistingKey(credentialKey, destination.resolve("credential-encryption.key"));
      copyExistingKey(totpKey, destination.resolve("totp-encryption.key"));
      Path completedZip = destination.resolve("database.zip");
      try { Files.move(pendingZip, completedZip, StandardCopyOption.ATOMIC_MOVE); }
      catch (AtomicMoveNotSupportedException ignored) { Files.move(pendingZip, completedZip); }
      privateFile(completedZip);
      // Only directories with COMPLETE contain a finished ZIP and all existing key copies.
      Path marker = destination.resolve("COMPLETE");
      createPrivateFile(marker);
      Files.writeString(marker, "H2_SHUTDOWN_BACKUP_COMPLETE\n", StandardCharsets.UTF_8, StandardOpenOption.WRITE);
      log.info("H2_SHUTDOWN_BACKUP status=COMPLETE path={}", safePath(destination));
    } catch (Exception error) {
      // JDBC exceptions can contain SQL, paths, or credentials; do not log the exception.
      // Retain an incomplete directory for inspection, without advertising it as restorable.
      log.error("H2_SHUTDOWN_BACKUP status=FAILED path={}", safePath(destination));
    }
  }

  private static void copyExistingKey(Path source, Path target) throws IOException {
    if (Files.notExists(source)) return;
    if (!Files.isRegularFile(source) || Files.size(source) < 1 || Files.size(source) > 16384) {
      throw new IOException("Cannot copy the configured key");
    }
    createPrivateFile(target);
    try (var input = Files.newInputStream(source); var output = Files.newOutputStream(target, StandardOpenOption.WRITE)) {
      byte[] content = input.readNBytes(16385);
      if (content.length == 0 || content.length > 16384) throw new IOException("Invalid key copy size");
      output.write(content);
    }
    privateFile(target);
  }

  private static void validateZip(Path file) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) == 0) throw new IOException("Empty backup");
    try (ZipFile archive = new ZipFile(file.toFile())) {
      if (archive.stream().noneMatch(entry -> !entry.isDirectory() && entry.getName().endsWith(".mv.db") && entry.getSize() > 0)) {
        throw new IOException("Incomplete backup archive");
      }
    }
  }

  private static void createPrivateDirectory(Path directory) throws IOException {
    if (Files.isSymbolicLink(directory)) throw new IOException("Backup directory must not be a symbolic link");
    if (posix(directory)) {
      Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    } else Files.createDirectories(directory);
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid backup directory");
  }

  private static void createPrivateFile(Path file) throws IOException {
    if (posix(file)) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    else Files.createFile(file);
  }

  private static void privateFile(Path file) throws IOException {
    if (posix(file)) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
  }

  private static boolean posix(Path path) { return path.getFileSystem().supportedFileAttributeViews().contains("posix"); }
  private static String safePath(Path path) { return path.toString().replaceAll("[\\p{Cntrl}]", "?"); }
}