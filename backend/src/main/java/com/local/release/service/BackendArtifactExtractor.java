package com.local.release.service;

import com.fasterxml.jackson.databind.*;
import com.local.release.domain.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class BackendArtifactExtractor {
  static final long MAX_BYTES=1024L*1024*1024;
  static void extract(Path zip,Path project,BackendCloudBuild entry,ReleaseTask task,BackendBuildConfiguration config)throws Exception{
    Path staging=Files.createTempDirectory(project,".backend-cloud-");
    try(ZipFile archive=new ZipFile(zip.toFile())){
      ZipEntry metadata=archive.getEntry("manifest.json");if(metadata==null)throw new IOException("后端产物缺少校验清单");
      JsonNode manifest;try(var in=archive.getInputStream(metadata)){byte[] bytes=in.readNBytes(65537);if(bytes.length>65536)throw new IOException("产物清单过大");manifest=new ObjectMapper().readTree(bytes);}
      if(manifest==null||manifest.path("schema").asInt()!=1||!manifest.path("request_id").asText().equals(entry.getRequestId())||manifest.path("task_id").asLong()!=task.getId()||manifest.path("service_id").asLong()!=task.getServiceId()||!manifest.path("commit_id").asText().equals(task.getCommitId())||!manifest.path("source_repository").asText().equals(entry.getSourceRepository())||!manifest.path("config_sha256").asText().equals(config.hash())||manifest.path("run_id").asLong()!=entry.getRunId()||manifest.path("run_attempt").asInt()!=1)throw new IOException("后端产物与本次任务身份不一致");
      JsonNode files=manifest.path("files");if(!files.isArray()||files.isEmpty()||files.size()>30)throw new IOException("后端产物文件数量无效");
      Map<String,JsonNode> expected=new LinkedHashMap<>();Set<String> folded=new HashSet<>();long total=0;
      for(JsonNode file:files){String name=file.path("path").asText();long size=file.path("size_bytes").asLong();
        if(!name.matches("(?:[A-Za-z0-9_.-]+/)*target/[A-Za-z0-9_.-]+\\.jar")||Arrays.stream(name.split("/")).anyMatch(p->Set.of(".","..",".git").contains(p))||!folded.add(name.toLowerCase(Locale.ROOT))||size<1||(total+=size)>MAX_BYTES||!file.path("sha256").asText().matches("[a-f0-9]{64}"))throw new IOException("后端产物路径、长度或摘要无效");expected.put(name,file);
      }
      Set<String> names=new HashSet<>();
      for(ZipEntry item:Collections.list(archive.entries())){
        if(!names.add(item.getName())||item.isDirectory()||!item.getName().equals("manifest.json")&&!expected.containsKey(item.getName()))throw new IOException("后端产物包含非预期文件");
      }
      if(names.size()!=expected.size()+1)throw new IOException("后端产物文件不完整");
      for(var item:expected.entrySet()){
        Path output=staging.resolve(item.getKey());Files.createDirectories(output.getParent());long size=0,limit=item.getValue().path("size_bytes").asLong();
        try(var in=archive.getInputStream(archive.getEntry(item.getKey()));var out=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)){
          byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if((size+=n)>limit)throw new IOException("后端产物解压大小超限");out.write(buffer,0,n);}
        }
        if(size!=limit||!FlutterBuildService.sha256(output).equals(item.getValue().path("sha256").asText()))throw new IOException("后端 JAR 长度或 SHA-256 不一致");
        try(var jar=new ZipFile(output.toFile())){if(jar.getEntry("META-INF/MANIFEST.MF")==null)throw new IOException("无效的 JAR 文件");}
        Path destination=project.resolve(item.getKey()).normalize();FlutterBuildService.ensureContained(project,destination);FlutterBuildService.rejectSymlinks(project,destination);
      }
      // All files verified before touching the task's existing output. Only target/*.jar is replaceable.
      Set<Path> parents=new HashSet<>();for(String name:expected.keySet())parents.add(project.resolve(name).getParent());
      for(Path parent:parents){FlutterBuildService.rejectSymlinks(project,parent);Files.createDirectories(parent);try(var old=Files.newDirectoryStream(parent,"*.jar")){for(Path file:old)Files.delete(file);}}
      for(String name:expected.keySet())Files.move(staging.resolve(name),project.resolve(name),StandardCopyOption.REPLACE_EXISTING);
    }finally{try(var files=Files.walk(staging)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
  }
}
