package com.local.release.miniprogram;

import com.fasterxml.jackson.databind.*;
import com.local.release.service.BoundedProcessRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Local prerequisites only: a green result does not claim WeChat account or IP authorization. */
@Component
public class MiniProgramReadiness {
  public record Check(String code,String label,String status,String message){}
  public record Report(boolean localReady,boolean previewReady,boolean uploadReady,LocalDateTime checkedAt,List<Check> checks){}
  private final MiniProgramSettings settings;
  private final ObjectMapper mapper;
  public MiniProgramReadiness(MiniProgramSettings settings,ObjectMapper mapper){this.settings=settings;this.mapper=mapper;}
  public Report inspect(MiniProgramConfiguration config)throws InterruptedException {
    List<Check> checks=new ArrayList<>();
    checks.add(new Check("project","项目状态",config.enabled()?"OK":"INFO",config.enabled()?"项目已启用":"项目仍为草稿，可检查准备情况，启用后才可构建"));
    Path runner=null,key=null;
    try{runner=settings.runner();}catch(Exception ignored){}
    try{key=settings.key(config.appId());}catch(Exception ignored){}
    checks.add(new Check("runner","执行工具",runner==null?"ERROR":"OK",runner==null?"请管理员安装并配置小程序 CI 执行工具":"已找到平台执行工具"));
    JsonNode result=mapper.createObjectNode();
    if(runner!=null){
      try{result=mapper.readTree(probe(runner,key));}
      catch(InterruptedException interrupted){throw interrupted;}
      catch(Exception ignored){/* Never echo process output or credential paths. */}
    }
    if(result==null)result=mapper.createObjectNode();
    String node=result.path("node").asText(),sdk=result.path("sdk").asText(),keyState=result.path("key").asText();
    checks.add(new Check("node","Node 运行环境",node.equals("OK")?"OK":"ERROR",node.equals("OK")?"Node "+version(result,"nodeVersion"):node.equals("OLD")?"需要 Node 20 或以上，请管理员升级":"未能完成 Node 检查，请核对安装路径与运行权限"));
    checks.add(new Check("sdk","微信 CI 依赖",sdk.equals("OK")?"OK":"ERROR",sdk.equals("OK")?"已安装锁定版本 "+version(result,"sdkVersion")+"；依赖审计需另外完成":sdk.equals("VERSION_MISMATCH")?"已安装版本与工具声明不一致，请按锁文件重新安装":"依赖未安装完整，请管理员在工具目录安装锁定依赖"));
    checks.add(new Check("key","代码上传密钥",key!=null&&keyState.equals("OK")?"OK":"ERROR",key==null?"未找到此 AppID 的可读密钥，请管理员配置":switch(keyState){
      case "OK"->"密钥格式有效；与 AppID 是否匹配需在微信联调时确认";
      case "IN_WORKSPACE"->"密钥不能放在项目工作目录，请移到独立密钥目录";
      case "PERMISSIONS"->"密钥应由平台运行账号持有，仅允许该账号读写（0600）";
      default->"密钥格式无效或检查未完成，请管理员核对代码上传密钥";
    }));
    checks.add(new Check("wechat","微信侧配置","MANUAL","尚未验证 IP 白名单、AppID 授权和合法域名；本检查不会上传代码或生成预览码"));
    checks.add(new Check("upload","上传开关",settings.uploadEnabled()?"OK":"INFO",settings.uploadEnabled()?"允许上传开发版本，仍需在微信后台提审和发布":"上传暂未开放，具备条件时可先生成预览码"));
    boolean local=checks.stream().noneMatch(c->c.status().equals("ERROR"));
    return new Report(local,local&&config.enabled()&&settings.enabled(),local&&config.enabled()&&settings.uploadEnabled(),LocalDateTime.now(),List.copyOf(checks));
  }
  public void requireLocal(MiniProgramConfiguration config)throws InterruptedException {
    Report report=inspect(config);
    if(!report.localReady())throw new IllegalArgumentException("构建准备未完成："+report.checks().stream().filter(c->c.status().equals("ERROR")).map(c->c.label()+"："+c.message()).findFirst().orElse("请检查配置"));
  }
  protected String probe(Path runner,Path key)throws Exception {
    String script=new ClassPathResource("runtime/miniprogram-readiness.cjs").getContentAsString(StandardCharsets.UTF_8);
    String input=mapper.writeValueAsString(Map.of("ciHome",runner.getParent().toString(),"keyPath",key==null?"":key.toString(),"workspace",settings.root().toString()));
    return BoundedProcessRunner.run(List.of(settings.node,"--max-old-space-size=96","--input-type=commonjs","-e",script+"\nprocess.stdout.write(JSON.stringify(inspect(JSON.parse(fs.readFileSync(0,'utf8')))));") ,runner.getParent(),input,10,null);
  }
  private String version(JsonNode node,String key){String value=node.path(key).asText();return value.matches("[0-9]+\\.[0-9]+\\.[0-9]+")?value:"（版本信息未返回）";}
}
