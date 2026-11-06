package com.local.release.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;
import java.util.function.LongConsumer;

/** Small bounded REST client. Tokens are sent only to the API, never to artifact storage redirects. */
@Component
public class GitHubActionsClient {
  private static final ObjectMapper JSON=new ObjectMapper();
  private final java.util.function.Supplier<String> tokenFile;
  private final URI api;
  @org.springframework.beans.factory.annotation.Autowired
  public GitHubActionsClient(MobileCloudSettings settings){this(settings,URI.create("https://api.github.com"));}
  GitHubActionsClient(MobileCloudSettings settings,URI api){this(()->settings.tokenFile,api);}
  GitHubActionsClient(String tokenFile){this(()->tokenFile,URI.create("https://api.github.com"));}
  private GitHubActionsClient(java.util.function.Supplier<String> tokenFile,URI api){this.tokenFile=tokenFile;this.api=api;}

  void checkCredential(){token();}
  private String token(){
    try{
      String configured=tokenFile.get();Path file=Path.of(configured);
      if(configured.isBlank()||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>16384)throw new IOException();
      if(Files.getFileStore(file).supportsFileAttributeView("posix")){
        var permissions=Files.getPosixFilePermissions(file);
        if(permissions.stream().anyMatch(p->p!=PosixFilePermission.OWNER_READ&&p!=PosixFilePermission.OWNER_WRITE))throw new IOException();
      }
      String value=Files.readString(file).trim();if(value.isEmpty()||!value.matches("[A-Za-z0-9_]+"))throw new IOException();return value;
    }catch(Exception error){throw new IllegalStateException("GitHub 构建凭据不可用，请检查私有 Token 文件及权限（仅运行账号可读写）");}
  }
  record Response(int status,JsonNode body){}
  Response request(String method,String path,Object body)throws IOException,InterruptedException{
    if(!path.startsWith("/repos/")||path.contains("..")||path.contains("#"))throw new IllegalArgumentException("GitHub API 路径无效");
    HttpURLConnection connection=open(api.resolve(path),true);
    try{
      connection.setRequestMethod(method);
      if(body!=null){byte[] bytes=JSON.writeValueAsBytes(body);connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);connection.setRequestProperty("Content-Type","application/json");try(OutputStream out=connection.getOutputStream()){out.write(bytes);}}
      int status=connection.getResponseCode();
      InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();
      byte[] bytes=stream==null?new byte[0]:readLimited(stream,2*1024*1024);
      if(status<200||status>=300)throw new ApiException(status);
      try{return new Response(status,bytes.length==0?JSON.createObjectNode():JSON.readTree(bytes));}
      catch(Exception invalid){throw new IOException("GitHub 返回的构建状态格式无效");}
    }finally{connection.disconnect();}
  }
  static final class ApiException extends IOException {
    final int status;
    ApiException(int status){super("GitHub API HTTP "+status+switch(status){case 401->"：构建凭据失效";case 403->"：权限、用量或访问限制，请检查 GitHub Actions";case 404->"：仓库/工作流不存在或凭据没有访问权限";case 422->"：工作流参数或分支配置不匹配";case 429->"：请求过于频繁";default->"：请求暂未成功";});this.status=status;}
  }
  private HttpURLConnection open(URI uri,boolean authenticated)throws IOException,InterruptedException{
    interrupted();HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();
    connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(30000);
    connection.setRequestProperty("User-Agent","Release-Platform-Mobile/0.5.3");
    if(authenticated){connection.setRequestProperty("Authorization","Bearer "+token());connection.setRequestProperty("Accept","application/vnd.github+json");connection.setRequestProperty("X-GitHub-Api-Version","2026-03-10");}
    return connection;
  }
  void download(String path,Path destination,long maxBytes,Instant deadline,LongConsumer progress)throws Exception{
    if(!path.matches("/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/actions/artifacts/[0-9]+/zip"))throw new IllegalArgumentException("GitHub 安装包地址无效");
    URI uri=api.resolve(path);boolean authenticated=true;
    try{
      for(int redirect=0;redirect<5;redirect++){
        HttpURLConnection connection=open(uri,authenticated);
        try{
          int status=connection.getResponseCode();
          if(Set.of(301,302,303,307,308).contains(status)){
            URI next=uri.resolve(connection.getHeaderField("Location"));
            if(!allowedArtifactUrl(next))throw new IOException("GitHub 安装包重定向地址不受信任");
            uri=next;authenticated=false;continue;
          }
          if(status!=200)throw new ApiException(status);
          if(connection.getContentLengthLong()>maxBytes)throw new IOException("GitHub 安装包超过下载大小限制");
          try(InputStream in=connection.getInputStream();OutputStream out=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)){
            byte[] buffer=new byte[65536];long count=0;int size;
            while((size=in.read(buffer))!=-1){interrupted();if(Instant.now().isAfter(deadline))throw new IOException("GitHub 安装包下载超时，可重试拉取本次产物");count+=size;if(count>maxBytes)throw new IOException("GitHub 安装包超过下载大小限制");out.write(buffer,0,size);progress.accept(count);}
          }
          return;
        }finally{connection.disconnect();}
      }
      throw new IOException("GitHub 安装包重定向次数过多");
    }catch(Exception error){Files.deleteIfExists(destination);throw error;}
  }
  static boolean allowedArtifactUrl(URI uri){
    String host=uri.getHost();return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&host!=null&&(host.endsWith(".githubusercontent.com")||host.endsWith(".blob.core.windows.net"));
  }
  private static byte[] readLimited(InputStream in,int maximum)throws IOException,InterruptedException{try(in){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){interrupted();if(out.size()+n>maximum)throw new IOException("GitHub 状态响应过大");out.write(buffer,0,n);}return out.toByteArray();}}
  private static void interrupted()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("GitHub 构建查询已中断");}
}
