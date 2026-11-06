package com.local.release.miniprogram;

import com.fasterxml.jackson.databind.*;
import com.local.release.service.BoundedProcessRunner;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

@Component
public class MiniProgramRunner {
  public static class RemoteRejected extends IllegalArgumentException { public RemoteRejected(String message){super(message);} }
  private final MiniProgramSettings settings;
  private final MiniProgramGit git;
  private final ObjectMapper mapper;
  private final MiniProgramReadiness readiness;
  public MiniProgramRunner(MiniProgramSettings settings,MiniProgramGit git,ObjectMapper mapper,MiniProgramReadiness readiness){this.settings=settings;this.git=git;this.mapper=mapper;this.readiness=readiness;}
  public void preflight(MiniProgramConfiguration config,MiniProgramJob.Action action)throws Exception {
    settings.requireEnabled();
    if(action==MiniProgramJob.Action.UPLOAD&&!settings.uploadEnabled())throw new IllegalArgumentException("微信代码上传开关尚未开启，目前只允许生成预览码");
    settings.runner();settings.key(config.appId());readiness.requireLocal(config);
  }
  public void execute(MiniProgramJob job,MiniProgramConfiguration config,BiConsumer<String,String> progress)throws Exception {
    preflight(config,job.getAction());
    progress.accept("GIT_PULL","拉取已锁定的 Commit："+job.getCommitId());
    Path source=git.checkout(job,config),project=source.resolve(config.projectDirectory()).normalize();
    if(!Files.isDirectory(project)||!project.toRealPath().startsWith(source.toRealPath()))throw new IllegalArgumentException("小程序项目目录不存在或超出仓库范围");
    validateProject(project,config);
    if(config.runChecks()){
      progress.accept("CHECK","运行项目代码检查与测试");
      Path check=project.resolve("scripts/check.js"),tests=project.resolve("scripts/test");
      if(!Files.isRegularFile(check)||!Files.isDirectory(tests))throw new IllegalArgumentException("项目未提供 scripts/check.js 或 scripts/test；请确认项目目录和检查配置");
      run(List.of(settings.node,check.toString()),project,null,120);
      List<String> command=new ArrayList<>(List.of(settings.node,"--test"));
      try(var files=Files.list(tests)){files.filter(p->p.getFileName().toString().endsWith(".test.js")).sorted().forEach(p->command.add(p.toString()));}
      if(command.size()==2)throw new IllegalArgumentException("没有找到小程序测试文件");
      run(command,project,null,180);
      progress.accept("CHECK","代码检查与测试通过");
    }
    Path output=git.directory("jobs/"+job.getId()+"/result");
    Path runner=settings.runner();
    Map<String,Object> request=new LinkedHashMap<>();
    request.put("taskId",job.getId());request.put("appId",config.appId());request.put("commitId",job.getCommitId());
    request.put("action",job.getAction().name());request.put("version",job.getVersion());request.put("description",Objects.toString(job.getNote(),""));
    request.put("robot",config.robot());request.put("projectPath",project.toString());request.put("privateKeyPath",settings.key(config.appId()).toString());
    request.put("outputDirectory",output.toString());
    // This transition is durable before any WeChat call. A crash afterwards is an UNKNOWN outcome, never an automatic replay.
    progress.accept("WECHAT_TRANSFER",job.getAction()==MiniProgramJob.Action.PREVIEW?"正在编译并向微信申请预览码":"正在编译并上传微信开发版本");
    run(List.of(settings.node,"--max-old-space-size=512",runner.toString()),runner.getParent(),mapper.writeValueAsString(request),Math.max(30,Math.min(3600,settings.timeoutSeconds)));
    verifyReceipt(output,job);
    progress.accept("COMPLETE",job.getAction()==MiniProgramJob.Action.PREVIEW?"预览码已生成，请使用有权限的微信扫码":"已上传微信开发版本；尚未提审或正式上线");
  }
  void validateProject(Path project,MiniProgramConfiguration config)throws Exception {
    Path path=project.resolve("project.config.json");
    if(!Files.isRegularFile(path)||Files.size(path)>1_000_000)throw new IllegalArgumentException("project.config.json 不存在或过大");
    JsonNode json=mapper.readTree(path.toFile());
    if(!config.appId().equals(json.path("appid").asText()))throw new IllegalArgumentException("源码 AppID 与平台配置不一致，已阻止上传");
    if(!"miniprogram".equals(json.path("compileType").asText()))throw new IllegalArgumentException("当前流程仅支持微信小程序项目");
    if(json.has("miniprogramRoot")){
      String relative=json.path("miniprogramRoot").asText();
      Path root=project.resolve(relative).normalize();
      if(!root.startsWith(project)||!Files.isDirectory(root)||!root.toRealPath().startsWith(project.toRealPath()))throw new IllegalArgumentException("miniprogramRoot 超出项目范围或不存在");
    }
  }
  void verifyReceipt(Path output,MiniProgramJob job)throws Exception {
    Path file=output.resolve("receipt.json");
    if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>100_000)throw new IllegalArgumentException("微信执行结果未返回，请核对微信后台");
    JsonNode receipt=mapper.readTree(file.toFile());
    if(receipt.path("taskId").asLong()!=job.getId()||!job.getAppId().equals(receipt.path("appId").asText())
        ||!job.getCommitId().equals(receipt.path("commitId").asText())||!job.getAction().name().equals(receipt.path("action").asText())
        ||!job.getVersion().equals(receipt.path("version").asText())||!receipt.path("success").asBoolean())throw new IllegalArgumentException("微信执行回执与当前任务不一致，请核对微信后台");
    if(job.getAction()==MiniProgramJob.Action.PREVIEW)preview(job.getId());
  }
  public byte[] preview(Long id)throws Exception {
    Path root=settings.root().toRealPath(),path=root.resolve("jobs/"+id+"/result/preview.image");
    if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().startsWith(root)||Files.size(path)>2_000_000)throw new IllegalArgumentException("预览码不存在，请重新生成");
    byte[] bytes=Files.readAllBytes(path),signature={(byte)137,80,78,71,13,10,26,10};
    boolean png=bytes.length>=8&&Arrays.equals(Arrays.copyOf(bytes,8),signature),jpeg=bytes.length>=3&&bytes[0]==(byte)255&&bytes[1]==(byte)216&&bytes[2]==(byte)255;
    if(!png&&!jpeg)throw new IllegalArgumentException("预览码文件无效");
    return bytes;
  }
  protected String run(List<String> command,Path dir,String input,long timeout)throws Exception {
    try{return BoundedProcessRunner.run(command,dir,input,timeout,null);}
    catch(InterruptedException e){throw e;}
    catch(Exception e){
      // CI and project script output is untrusted and may include credentials. Only known diagnostic codes leave the worker.
      String message=Objects.toString(e.getMessage(),"");
      if(message.contains("WX_IP_DENIED"))throw new RemoteRejected("微信拒绝当前出口 IP，请检查上传白名单");
      if(message.contains("WX_KEY_INVALID"))throw new RemoteRejected("微信代码上传密钥无效，请检查密钥与 AppID");
      String detail=message.contains("超时")?"执行超时，子进程已停止":message.contains("WX_COMPILE_FAILED")?"微信编译或代码上传失败，请用开发者工具检查源码与项目设置"
        :message.contains("Cannot find module")?"小程序 CI 依赖未安装完整":"执行失败，请检查代码检查结果、Node 和小程序 CI 安装";
      throw new IllegalArgumentException(detail);
    }
  }
}
