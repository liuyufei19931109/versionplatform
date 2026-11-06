package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class MobileSigningCatalogTest {
  @TempDir Path directory;
  MobileSigningCatalog catalog(){var service=new MobileSigningCatalog();service.signingRoot=directory.toString();return service;}
  void privateFile(Path file)throws Exception{if(Files.getFileStore(file).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}
  @Test void missingConfigurationProducesEmptyCatalog(){var service=new MobileSigningCatalog();assertThat(service.list()).isEqualTo(new MobileSigningCatalog.Catalog(false,List.of()));service.signingRoot=directory.resolve("absent").toString();assertThat(service.list().configured()).isFalse();}
  @Test void invalidProfileNeverLeaksFilePathsPasswordsOrExceptions()throws Exception{
    Path file=directory.resolve("demo-prod.properties");Files.writeString(file,"storeFile=/private/secret-key.jks\nstorePassword=never-expose-password\nkeyPassword=never-expose-key\nkeyAlias=app\n");privateFile(file);
    var result=catalog().list();assertThat(result.configured()).isTrue();assertThat(result.profiles()).hasSize(1);assertThat(result.profiles().getFirst().ready()).isFalse();String json=new ObjectMapper().writeValueAsString(result);assertThat(json).doesNotContain("secret-key","never-expose","storeFile","keyPassword");
  }
  @Test void inventoryOnlyAcceptsProvisionedProfileNames()throws Exception{
    Files.writeString(directory.resolve("not-a-profile.txt"),"irrelevant");Files.writeString(directory.resolve("bad name.properties"),"irrelevant");assertThat(catalog().list().profiles()).isEmpty();
  }
  @Test void readsActualPrivateKeyCertificateAndDetectsRegisteredDebugCertificate()throws Exception{
    Path key=directory.resolve("test.p12"),log=directory.resolve("keytool.log");String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
    Process process=new ProcessBuilder(executable,"-genkeypair","-alias","app","-keystore",key.toString(),"-storetype","PKCS12","-storepass","fixture-password","-keypass","fixture-password","-keyalg","RSA","-keysize","2048","-dname","CN=Android Debug,O=Fixture,C=CN","-validity","30").redirectErrorStream(true).redirectOutput(log.toFile()).start();
    assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();privateFile(key);
    Properties properties=new Properties();properties.setProperty("storeFile",key.toString());properties.setProperty("storePassword","fixture-password");properties.setProperty("keyPassword","fixture-password");properties.setProperty("keyAlias","app");Path file=directory.resolve("pre.properties");try(var writer=Files.newBufferedWriter(file)){properties.store(writer,null);}privateFile(file);
    var profile=catalog().list().profiles().getFirst();assertThat(profile.ready()).isTrue();assertThat(profile.debugCertificate()).isTrue();assertThat(profile.certificateSha256()).matches("[a-f0-9]{64}");
    properties.setProperty("keyAlias","missing");try(var writer=Files.newBufferedWriter(file)){properties.store(writer,null);}assertThat(catalog().list().profiles().getFirst().ready()).isFalse();
  }
}
