package com.local.release.miniprogram;

import com.local.release.service.PolicyGitRepository;
import java.net.URI;
import java.util.*;

public record MiniProgramConfiguration(String name,String code,String appId,String gitRepo,String defaultBranch,
    String projectDirectory,int robot,boolean runChecks,boolean enabled) {
  public MiniProgramConfiguration {
    name=text(name,100,"小程序名称");code=text(code,80,"项目标识");
    if(!code.matches("[a-z][a-z0-9_-]{1,79}"))throw new IllegalArgumentException("项目标识请使用小写字母、数字、横线或下划线");
    if(appId==null||!appId.matches("wx[0-9a-fA-F]{16}"))throw new IllegalArgumentException("AppID 格式不正确");
    gitRepo=repository(gitRepo);defaultBranch=branch(defaultBranch);
    projectDirectory=Objects.toString(projectDirectory,"").trim().replace('\\','/');
    if(projectDirectory.equals("."))projectDirectory="";
    if(!projectDirectory.isEmpty()&&(!projectDirectory.matches("[A-Za-z0-9_./-]{1,200}")||projectDirectory.startsWith("/")
        ||Arrays.asList(projectDirectory.split("/",-1)).stream().anyMatch(p->p.isBlank()||p.equals("..")||p.equals("."))))
      throw new IllegalArgumentException("项目目录必须是仓库内的相对目录");
    if(robot<1||robot>30)throw new IllegalArgumentException("微信上传机器人编号需在 1 到 30 之间");
  }
  public static String branch(String branch){
    String value=text(branch,120,"代码分支");
    if(value.startsWith("-")||value.startsWith("/")||value.contains("..")||value.contains("@{")||value.endsWith(".")
        ||value.endsWith("/")||value.endsWith(".lock")||value.contains("//")||!value.matches("[A-Za-z0-9._/-]+"))
      throw new IllegalArgumentException("代码分支名称格式不正确");
    return value;
  }
  public static String repository(String repository){
    String value=text(repository,500,"Git 仓库");
    if(value.matches("git@[A-Za-z0-9.-]+:[A-Za-z0-9_./-]+")&&!value.contains(".."))return value;
    try{URI uri=URI.create(value);if("https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null
        &&uri.getQuery()==null&&uri.getFragment()==null&&uri.getPath()!=null&&uri.getPath().matches("/[A-Za-z0-9_./-]+"))return value;}
    catch(IllegalArgumentException ignored){}
    throw new IllegalArgumentException("请填写 HTTPS 或 git@host:path 仓库地址，凭据由服务器配置");
  }
  public static String version(String version){
    String value=text(version,64,"版本号");if(!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw new IllegalArgumentException("版本号请使用字母、数字、点或横线");return value;
  }
  public static String commit(String commit){PolicyGitRepository.validateSha(commit);return commit.toLowerCase(Locale.ROOT);}
  public static String text(String value,int max,String label){
    String result=Objects.toString(value,"").trim();
    if(result.isEmpty()||result.length()>max||result.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException(label+"未填写或格式不正确");
    return result;
  }
}
