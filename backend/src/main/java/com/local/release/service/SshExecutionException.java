package com.local.release.service;

/** Only safe, predefined diagnostics cross the SSH boundary; raw tool output is never an exception message. */
public class SshExecutionException extends IllegalStateException {
  public enum Code { CONFIGURATION, DEPENDENCY_MISSING, GOOGLE_AUTH, IAP_PERMISSION, TUNNEL_FAILED, HOST_KEY, SSH_AUTH, CONNECTION_FAILED, CONNECT_TIMEOUT, COMMAND_TIMEOUT, REMOTE_COMMAND, CONNECTION_LOST, OUTPUT_LIMIT, PROCESS_IO }
  private final Code code;
  private final Integer exitCode;
  private final boolean remoteStarted;

  public SshExecutionException(Code code,String message,Integer exitCode,boolean remoteStarted){
    super("["+code+"] "+message);this.code=code;this.exitCode=exitCode;this.remoteStarted=remoteStarted;
  }
  public Code getCode(){return code;}
  public Integer getExitCode(){return exitCode;}
  public boolean isRemoteStarted(){return remoteStarted;}

  static SshExecutionException failure(String stderr,int status,boolean started){
    String text=stderr==null?"":stderr.toLowerCase(java.util.Locale.ROOT);
    if(started){
      if(status==255)return new SshExecutionException(Code.CONNECTION_LOST,"SSH 连接在远程命令开始后中断（退出码 255），也可能是远程命令返回该代码；请核对目标状态后再重试。",status,true);
      return new SshExecutionException(Code.REMOTE_COMMAND,"远程命令退出码 "+status+"；"+remoteReason(text)+" 部分操作可能已执行，请核对目标状态。",status,true);
    }
    if(text.contains("gcloud")&&(text.contains("not found")||text.contains("no such file")||text.contains("not recognized")))
      return new SshExecutionException(Code.DEPENDENCY_MISSING,"平台运行账号找不到 gcloud，请检查安装位置及受控 SSH 配置中的绝对路径。",status,false);
    if(text.contains("host key verification failed")||text.contains("remote host identification has changed")||text.contains("no matching host key")||text.contains("strict checking"))
      return new SshExecutionException(Code.HOST_KEY,"SSH 主机指纹未受信任或已变化，请由运维核对并准备 known_hosts；平台不会自动接受新指纹。",status,false);
    if(text.contains("bad owner or permissions")||text.contains("bad configuration option")||text.contains("terminating, ")&&text.contains("bad configuration"))
      return new SshExecutionException(Code.CONFIGURATION,"SSH 配置权限或语法不正确，请由运维检查受控配置文件及 Include 文件。",status,false);
    if(text.contains("permission denied (publickey")||text.contains("authentication failed")||text.contains("auth fail")||text.contains("no supported authentication")||text.contains("load key")||text.contains("identity file")||text.contains("private key file"))
      return new SshExecutionException(Code.SSH_AUTH,"SSH 登录认证失败，请检查目标机账号、私钥、OS Login 配置及文件权限。",status,false);
    if(text.contains("invalid_grant")||text.contains("invalid credentials")||text.contains("reauthentication")||text.contains("please run")&&text.contains("gcloud auth")||text.contains("no active account")||text.contains("do not currently have an active account")||text.contains("could not refresh")||text.contains("refresh your current auth")||text.contains("unauthenticated"))
      return new SshExecutionException(Code.GOOGLE_AUTH,"Google 机器身份未配置或已失效，请检查平台运行账号的 gcloud 凭据；平台不会启动交互登录。",status,false);
    if(text.contains("403")||text.contains("not authorized")||text.contains("access via iap is denied")||text.contains("iap.tunnelinstances.accessviaiap")||text.contains("permission_denied")||text.contains("permission denied"))
      return new SshExecutionException(Code.IAP_PERMISSION,"Google/IAP 权限不足，请核对机器身份、项目、实例及 IAP 隧道授权。",status,false);
    if(text.contains("gcloud")||text.contains("iap")||text.contains("failed to connect to backend")||text.contains("4003"))
      return new SshExecutionException(Code.TUNNEL_FAILED,"IAP 隧道未建立，请核对项目、区域、实例、网络出口及 IAP 到目标 SSH 端口的防火墙规则。",status,false);
    if(text.contains("timed out")||text.contains("timeout"))
      return new SshExecutionException(Code.CONNECT_TIMEOUT,"SSH 连接超时，请检查节点网络、代理配置及 SSH 服务状态。",status,false);
    return new SshExecutionException(Code.CONNECTION_FAILED,"SSH 连接未完成，请检查节点地址、端口、代理配置及网络；尚未收到远程执行确认。",status,false);
  }

  /** Match known error categories without ever echoing arbitrary remote text, paths, environment values or credentials. */
  private static String remoteReason(String text){
    if(text.contains("sudo")&&(text.contains("password")||text.contains("not allowed")||text.contains("not in the sudoers")||text.contains("permission")))return "远程 sudo 权限不足，请检查平台账号的非交互 sudo 授权。";
    if(text.contains("manifest unknown")||text.contains("manifest for")&&text.contains("not found")||text.contains("no such image"))return "目标镜像或版本不存在，请检查镜像仓库与版本标签。";
    if(text.contains("pull access denied")||text.contains("unauthorized")||text.contains("denied: requested access")||text.contains("authentication required"))return "镜像仓库访问被拒绝，请检查目标机拉取权限及仓库认证。";
    if(text.contains("no space left on device")||text.contains("disk quota exceeded"))return "目标机磁盘空间或配额不足。";
    if(text.contains("健康检查失败")||text.contains("健康检查未通过")||text.contains("health check failed")||text.contains("unhealthy")||text.contains("curl: (7)")||text.contains("curl: (22)")||text.contains("curl: (28)"))return "远程健康检查未通过，请检查应用启动状态、监听端口和健康检查路径。";
    if(text.contains("cannot connect to the docker daemon")||text.contains("is the docker daemon running"))return "目标机 Docker 服务不可用，请检查 Docker 运行状态及访问权限。";
    if(text.contains("permission denied")||text.contains("access denied"))return "目标机文件或命令访问权限不足，请检查 Compose 目录、文件及 Docker 权限。";
    if(text.contains("no such file or directory")||text.contains("can't cd")||text.contains("cannot cd")||text.contains("no configuration file provided"))return "目标路径或配置文件不存在，请检查远程 Compose 项目目录与配置文件。";
    if(text.contains("no such service"))return "Compose 配置中不存在指定服务，请核对服务名称。";
    if(text.contains("command not found")||text.contains("docker: not found")||text.contains("python3: not found"))return "目标机缺少所需命令，请检查 Docker、Compose、Python 3 与 PATH。";
    return "远程命令未成功完成，请检查目标机应用、Docker、Compose 配置及日志。";
  }
}
