package com.local.release.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class GitHubActionsClientTest {
  @TempDir Path directory;
  HttpServer server;GitHubActionsClient client;
  @BeforeEach void setup()throws Exception{
    var settings=new MobileCloudSettings();Path token=directory.resolve("token");Files.writeString(token,"test_only_token");if(Files.getFileStore(token).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(token,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));settings.tokenFile=token.toString();
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();client=new GitHubActionsClient(settings,URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
  }
  @AfterEach void cleanup(){server.stop(0);}
  @Test void authenticatesApiAndDoesNotEchoErrorBody()throws Exception{
    var header=new AtomicReference<String>();server.createContext("/repos/owner/repo",e->{header.set(e.getRequestHeaders().getFirst("Authorization"));byte[] body="do not echo: confidential contents".getBytes();e.sendResponseHeaders(403,body.length);e.getResponseBody().write(body);e.close();});
    assertThatThrownBy(()->client.request("GET","/repos/owner/repo",null)).hasMessageContaining("403").hasMessageNotContaining("confidential");assertThat(header.get()).isEqualTo("Bearer test_only_token");
  }
  @Test void rejectsUntrustedDownloadRedirectBeforeContactingIt()throws Exception{
    server.createContext("/repos/owner/repo/actions/artifacts/1/zip",e->{e.getResponseHeaders().set("Location","https://attacker.example/leak");e.sendResponseHeaders(302,-1);e.close();});Path destination=directory.resolve("out.zip");
    assertThatThrownBy(()->client.download("/repos/owner/repo/actions/artifacts/1/zip",destination,1024,Instant.now().plusSeconds(30),n->{})).hasMessageContaining("不受信任");assertThat(destination).doesNotExist();
    assertThat(GitHubActionsClient.allowedArtifactUrl(URI.create("https://x.blob.core.windows.net/data"))).isTrue();
    assertThat(GitHubActionsClient.allowedArtifactUrl(URI.create("https://x.blob.core.windows.net.attacker.com/data"))).isFalse();
    assertThat(GitHubActionsClient.allowedArtifactUrl(URI.create("http://x.blob.core.windows.net/data"))).isFalse();
  }
  @Test void streamingDownloadRejectsOversizedFileAndRemovesPartial()throws Exception{
    server.createContext("/repos/owner/repo/actions/artifacts/1/zip",e->{e.sendResponseHeaders(200,0);e.getResponseBody().write(new byte[4096]);e.close();});Path destination=directory.resolve("out.zip");
    assertThatThrownBy(()->client.download("/repos/owner/repo/actions/artifacts/1/zip",destination,1024,Instant.now().plusSeconds(30),n->{})).hasMessageContaining("大小限制");assertThat(destination).doesNotExist();
  }
}
