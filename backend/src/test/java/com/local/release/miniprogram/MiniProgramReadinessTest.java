package com.local.release.miniprogram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class MiniProgramReadinessTest {
  @TempDir Path root;
  final ObjectMapper mapper=new ObjectMapper();
  MiniProgramSettings settings;Probe readiness;
  static class Probe extends MiniProgramReadiness {
    String response="{\"node\":\"OK\",\"nodeVersion\":\"22.14.0\",\"sdk\":\"OK\",\"sdkVersion\":\"2.1.48\",\"key\":\"OK\"}";
    Exception error;int calls;
    Probe(MiniProgramSettings settings,ObjectMapper mapper){super(settings,mapper);}
    @Override protected String probe(Path runner,Path key)throws Exception {calls++;if(error!=null)throw error;return response;}
  }
  @BeforeEach void setup()throws Exception{
    settings=new MiniProgramSettings();settings.enabled=true;settings.workspace=root.resolve("workspaces").toString();
    Path tool=Files.createDirectory(root.resolve("tools")),keys=Files.createDirectory(root.resolve("keys"));
    Files.writeString(tool.resolve("runner.cjs"),"// fixture");Files.writeString(keys.resolve("private.wx0000000000000001.key"),"FIXTURE_ONLY");
    settings.ciHome=tool.toString();settings.keyRoot=keys.toString();readiness=new Probe(settings,mapper);
  }
  @Test void localReadinessDoesNotClaimWechatAuthorizationAndRespectsSeparateSwitches()throws Exception{
    var result=readiness.inspect(MiniProgramConfigurationTest.config(true));
    assertThat(result.localReady()).isTrue();assertThat(result.previewReady()).isTrue();assertThat(result.uploadReady()).isFalse();
    assertThat(result.checks()).anyMatch(c->c.code().equals("wechat")&&c.status().equals("MANUAL"));
    settings.uploadEnabled=true;assertThat(readiness.inspect(MiniProgramConfigurationTest.config(true)).uploadReady()).isTrue();
    assertThat(readiness.inspect(MiniProgramConfigurationTest.config(false)).previewReady()).isFalse();
  }
  @Test void missingToolAndKeyFailWithoutSpawningAnyProcess()throws Exception{
    settings.ciHome="";settings.keyRoot="";
    var result=readiness.inspect(MiniProgramConfigurationTest.config(true));
    assertThat(result.localReady()).isFalse();assertThat(readiness.calls).isZero();
    assertThatThrownBy(()->readiness.requireLocal(MiniProgramConfigurationTest.config(true))).hasMessageContaining("执行工具");
  }
  @Test void failuresCannotLeakPathsCredentialsOrArbitraryProbeOutput()throws Exception{
    readiness.error=new IllegalStateException("private.key SECRET_TOKEN "+root);
    var result=readiness.inspect(MiniProgramConfigurationTest.config(true));
    assertThat(result.localReady()).isFalse();
    assertThat(result.checks().toString()).doesNotContain("SECRET_TOKEN",root.toString(),"private.key");
    readiness.error=null;readiness.response="{\"node\":\"OK\",\"nodeVersion\":\"SECRET_TOKEN\",\"sdk\":\"VERSION_MISMATCH\",\"key\":\"INVALID\"}";
    assertThat(readiness.inspect(MiniProgramConfigurationTest.config(true)).checks().toString()).doesNotContain("SECRET_TOKEN").contains("版本与工具声明不一致");
  }
  @Test void interruptedReadinessPreservesCancellation(){
    readiness.error=new InterruptedException();assertThatThrownBy(()->readiness.inspect(MiniProgramConfigurationTest.config(true))).isInstanceOf(InterruptedException.class);
  }
}
