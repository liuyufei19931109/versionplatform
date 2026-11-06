package com.local.release.service;

import com.fasterxml.jackson.databind.*;
import com.local.release.domain.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Only verified static files can be installed into the isolated task output directory. */
final class FrontendArtifactExtractor {
  static final long MAX_BYTES=512L*1024*1024,MAX_FILE_BYTES=128L*1024*1024;
  static final int MAX_FILES=20000;
  private static final ObjectMapper JSON=new ObjectMapper();
  static void extract(Path zip,Path project,FrontendCloudBuild entry,ReleaseTask task,FrontendBuildConfiguration config)throws Exception{
    Path destination=project.resolve(config.outputDirectory()).normalize();
    FlutterBuildService.ensureContained(project,destination);FlutterBuildService.rejectSymlinks(project,destination);
    if(destination.equals(project)||config.outputDirectory().isBlank())throw new IOException("前端产物目录无效");
    Path staging=Files.createTempDirectory(project,".frontend-cloud-");
    try(ZipFile outer=new ZipFile(zip.toFile())){
      Set<String> names=new HashSet<>();
      for(ZipEntry item:Collections.list(outer.entries()))if(item.isDirectory()||!Set.of("site.zip","manifest.json").contains(item.getName())||!names.add(item.getName()))throw new IOException("前端产物包含非预期文件");
      if(names.size()!=2)throw new IOException("前端产物缺少校验清单");
      JsonNode manifest;
      try(InputStream input=outer.getInputStream(outer.getEntry("manifest.json"))){byte[] bytes=input.readNBytes(65537);if(bytes.length>65536)throw new IOException("前端产物清单过大");manifest=JSON.readTree(bytes);}
      if(manifest==null||manifest.path("schema").asInt()!=1||!manifest.path("request_id").asText().equals(entry.getRequestId())||manifest.path("task_id").asLong()!=task.getId()||manifest.path("service_id").asLong()!=task.getServiceId()||!manifest.path("commit_id").asText().equals(task.getCommitId())||!manifest.path("source_repository").asText().equals(entry.getSourceRepository())||!manifest.path("config_sha256").asText().equals(config.hash())||manifest.path("run_id").asLong()!=entry.getRunId()||manifest.path("run_attempt").asInt()!=1)throw new IOException("前端产物与本次任务身份不一致");
      long expectedSize=manifest.path("size_bytes").asLong();if(expectedSize<1||expectedSize>MAX_BYTES)throw new IOException("前端产物大小超限");
      Path site=staging.resolve("site.zip"),content=staging.resolve("content");Files.createDirectory(content);
      try(InputStream input=outer.getInputStream(outer.getEntry("site.zip"));OutputStream output=Files.newOutputStream(site,StandardOpenOption.CREATE_NEW)){if(copy(input,output,expectedSize)!=expectedSize)throw new IOException("前端产物长度不一致");}
      if(!FlutterBuildService.sha256(site).equals(manifest.path("sha256").asText()))throw new IOException("前端产物 SHA-256 不一致");
      unpack(site,content);
      // All paths and hashes are checked before replacing a previous failed-attempt output.
      FlutterBuildService.rejectSymlinks(project,destination);Files.createDirectories(destination.getParent());
      if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))removeTree(project,destination);
      Files.move(content,destination);
    }finally{removeTree(project,staging);}
  }
  static void unpack(Path site,Path content)throws Exception{
    Set<String> names=new HashSet<>();int count=0;long total=0;
    try(ZipFile archive=new ZipFile(site.toFile())){
      for(ZipEntry item:Collections.list(archive.entries())){
        if(++count>MAX_FILES)throw new IOException("前端产物文件数量超限");
        String name=item.getName();String clean=item.isDirectory()&&name.endsWith("/")?name.substring(0,name.length()-1):name;
        if(clean.isBlank()||clean.startsWith("/")||clean.contains("\\")||clean.contains(":")||clean.length()>500||clean.chars().anyMatch(c->c<32)||Arrays.stream(clean.split("/",-1)).anyMatch(p->p.isBlank()||Set.of(".","..",".git",".release-platform").contains(p))||!names.add(clean.toLowerCase(Locale.ROOT)))throw new IOException("前端产物包含非法或重复路径");
        Path file=content.resolve(clean).normalize();FlutterBuildService.ensureContained(content,file);
        if(item.isDirectory()){Files.createDirectories(file);continue;}
        if(item.getSize()>MAX_FILE_BYTES)throw new IOException("前端单个文件大小超限");
        Files.createDirectories(file.getParent());
        try(InputStream input=archive.getInputStream(item);OutputStream output=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){total+=copy(input,output,Math.min(MAX_FILE_BYTES,MAX_BYTES-total));}
      }
    }
    if(total==0||!Files.isRegularFile(content.resolve("index.html")))throw new IOException("前端产物为空或缺少 index.html");
  }
  private static long copy(InputStream input,OutputStream output,long limit)throws Exception{
    long total=0;byte[] buffer=new byte[65536];int size;
    while((size=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();total+=size;if(total>limit)throw new IOException("前端产物解压大小超限");output.write(buffer,0,size);}return total;
  }
  private static void removeTree(Path root,Path target)throws IOException{
    FlutterBuildService.ensureContained(root,target);if(target.equals(root)||!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return;
    // walk does not follow links; unlinking a link never traverses its destination.
    try(var walk=Files.walk(target)){for(Path path:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
  }
}
