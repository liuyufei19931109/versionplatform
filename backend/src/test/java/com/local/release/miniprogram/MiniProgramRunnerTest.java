package com.local.release.miniprogram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MiniProgramRunnerTest {
  @TempDir Path root;
  final ObjectMapper mapper=new ObjectMapper();
  MiniProgramSettings settings;MiniProgramRunner runner;
  @BeforeEach void setup(){settings=new MiniProgramSettings();settings.workspace=root.toString();runner=new MiniProgramRunner(settings,mock(MiniProgramGit.class),mapper,mock(MiniProgramReadiness.class));}
  @Test void sourceAppIdMismatchAndEscapingRootAreBlocked()throws Exception{
    Path config=root.resolve("project.config.json");Files.writeString(config,"{\"appid\":\"wx1111111111111111\",\"compileType\":\"miniprogram\"}");
    assertThatThrownBy(()->runner.validateProject(root,MiniProgramConfigurationTest.config(true))).hasMessageContaining("AppID");
    Files.writeString(config,"{\"appid\":\"wx0000000000000001\",\"compileType\":\"miniprogram\",\"miniprogramRoot\":\"../\"}");
    assertThatThrownBy(()->runner.validateProject(root,MiniProgramConfigurationTest.config(true))).hasMessageContaining("超出");
  }
  @Test void receiptMustMatchTaskCommitAppAndAction()throws Exception{
    MiniProgramProject project=new MiniProgramProject();project.apply(MiniProgramConfigurationTest.config(true));ReflectionTestUtils.setField(project,"id",1L);
    MiniProgramJob job=new MiniProgramJob(project,MiniProgramJob.Action.UPLOAD,"master","a".repeat(40),"1.0.0","","admin","{}","key","fingerprint");ReflectionTestUtils.setField(job,"id",5L);
    Map<String,Object> receipt=new HashMap<>(Map.of("taskId",5,"appId",project.getAppId(),"commitId",job.getCommitId(),"action","UPLOAD","version","1.0.0","success",true));
    Files.writeString(root.resolve("receipt.json"),mapper.writeValueAsString(receipt));runner.verifyReceipt(root,job);
    receipt.put("commitId","b".repeat(40));Files.writeString(root.resolve("receipt.json"),mapper.writeValueAsString(receipt));
    assertThatThrownBy(()->runner.verifyReceipt(root,job)).hasMessageContaining("不一致");
  }
  @Test void rejectsHtmlInsteadOfQrImage()throws Exception{
    Path dir=root.resolve("jobs/5/result");Files.createDirectories(dir);Files.writeString(dir.resolve("preview.image"),"<html>secret</html>");
    assertThatThrownBy(()->runner.preview(5L)).hasMessageContaining("预览码文件无效");
  }
  @Test void uploadHasItsOwnKillSwitch(){settings.enabled=true;assertThatThrownBy(()->runner.preflight(MiniProgramConfigurationTest.config(true),MiniProgramJob.Action.UPLOAD)).hasMessageContaining("上传开关");}
}
