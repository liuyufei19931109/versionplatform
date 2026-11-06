package com.local.release.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.Artifact;
import com.local.release.domain.ReleaseStep;
import com.local.release.domain.ReleaseTask;
import com.local.release.repo.ArtifactRepository;
import com.local.release.repo.ReleaseStepRepository;
import com.local.release.repo.ReleaseTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Reclaims only the output of a committed successful build, never shared caches or history. */
@Service
public class BuildStorageService {
  private static final Logger log=LoggerFactory.getLogger(BuildStorageService.class);
  private static final String JOURNAL_DIRECTORY=".storage-maintenance";
  private final ObjectMapper mapper;
  private final ReleaseTaskRepository tasks;
  private final ArtifactRepository artifacts;
  private final ReleaseStepRepository steps;
  private final TransactionTemplate transactions;
  @Value("${release.workspace:../workspaces}") String workspaceRoot;
  @Value("${release.storage.cleanup-successful-builds:true}") boolean enabled=true;
  @Value("${release.storage.registry-timeout-seconds:30}") long registryTimeoutSeconds=30;
  @Value("${release.storage.pending-retries-per-build:2}") int retriesPerBuild=2;

  public BuildStorageService(ObjectMapper mapper,ReleaseTaskRepository tasks,ArtifactRepository artifacts,
      ReleaseStepRepository steps,PlatformTransactionManager tx) {
    this.mapper=mapper;this.tasks=tasks;this.artifacts=artifacts;this.steps=steps;
    this.transactions=new TransactionTemplate(tx);
  }

  record Pending(long taskId,long artifactId,String taskCreatedAt,String artifactCreatedAt,
                 String commitId,String image,String version) {}
  record Candidate(ReleaseTask task,Artifact artifact) {}
  record LocalImage(String id,Set<String> digests) {}
  record Verified(String digest,String localImageId) {}

  /** Called only after ReleaseExecutor's success transaction returns, while its execution lock is held. */
  public void afterSuccessfulBuild(Long taskId) {
    if(!enabled||taskId==null)return;
    try {
      Path root=validatedRoot();
      Path journals=journalDirectory(root);
      ReleaseTask task=tasks.findById(taskId).orElseThrow(()->new IOException("打包记录不存在"));
      Artifact artifact=artifacts.findByBuildTaskId(taskId).orElseThrow(()->new IOException("制品记录不存在"));
      Pending pending=pending(task,artifact);
      requireCandidate(pending);
      Path journal=journals.resolve(taskId+".json");
      if(!Files.exists(journal,NOFOLLOW_LINKS))writeJournal(journal,pending);
      else if(!pending.equals(readJournal(journal)))throw new IOException("存储维护记录与当前任务不一致");
      attempt(root,journal,pending);
      retryPending(root,journals,taskId);
    } catch(Exception error) { deferred(taskId,error); }
  }

  private Pending pending(ReleaseTask task,Artifact artifact)throws IOException {
    if(task.getId()==null||task.getId()<1||artifact.getId()==null||artifact.getId()<1
        ||task.getCreatedAt()==null||artifact.getCreatedAt()==null)throw new IOException("任务或制品标识不完整");
    return new Pending(task.getId(),artifact.getId(),task.getCreatedAt().toString(),artifact.getCreatedAt().toString(),
      task.getCommitId(),artifact.getImageUrl(),artifact.getVersion());
  }

  private Candidate requireCandidate(Pending pending)throws IOException {
    if(pending.taskId()<1||pending.artifactId()<1)throw new IOException("无效的维护任务编号");
    ReleaseTask task=tasks.findById(pending.taskId()).orElseThrow(()->new IOException("维护任务记录不存在"));
    Artifact artifact=artifacts.findById(pending.artifactId()).orElseThrow(()->new IOException("维护制品记录不存在"));
    if(task.getTaskKind()!=TaskKind.BUILD||task.getStatus()!=TaskStatus.SUCCESS||task.isSimulate()
        ||task.getFinishedAt()==null||!Objects.equals(task.getId(),artifact.getBuildTaskId())
        ||!Objects.equals(task.getServiceId(),artifact.getServiceId())||!pending.equals(pending(task,artifact))
        ||!Objects.equals(task.getCommitId(),artifact.getCommitId()))throw new IOException("仅允许维护匹配且已成功提交的真实打包任务");
    if(pending.image()==null||pending.version()==null||!pending.image().endsWith(":"+pending.version())
        ||!pending.version().endsWith("-b"+pending.taskId())
        ||!pending.image().matches("[A-Za-z0-9][A-Za-z0-9._:/-]*:[A-Za-z0-9_][A-Za-z0-9_.-]*"))
      throw new IOException("镜像不是该任务的唯一版本标签");
    List<ReleaseStep> saved=steps.findByReleaseTaskIdOrderById(pending.taskId());
    if(!saved.stream().anyMatch(step->step.getStepType()==StepType.PUSH_IMAGE&&step.getStatus()==StepStatus.SUCCESS))
      throw new IOException("推送步骤尚未成功");
    return new Candidate(task,artifact);
  }

  private void attempt(Path root,Path journal,Pending pending) {
    try {
      Candidate candidate=requireCandidate(pending);
      Verified verified=verifyRegistry(root,pending.image(),candidate.artifact().getImageDigest());
      // An independent transaction commits the remote proof before touching local output.
      transactions.executeWithoutResult(status->{
        try {
          Artifact current=requireCandidate(pending).artifact();
          if(current.getImageDigest()!=null&&!current.getImageDigest().isBlank()
              &&!current.getImageDigest().equals(verified.digest()))throw new IOException("已保存镜像摘要与仓库不同");
          if(!verified.digest().equals(current.getImageDigest())){current.setImageDigest(verified.digest());artifacts.saveAndFlush(current);}
        }catch(IOException error){throw new IllegalStateException(error.getMessage(),error);}
      });
      Candidate committed=requireCandidate(pending);
      if(!verified.digest().equals(committed.artifact().getImageDigest()))throw new IOException("镜像摘要尚未保存");
      removeWorkspace(root,pending.taskId());
      removeImage(root,pending.image(),verified);
      Files.delete(journal);
      maintenanceLog(pending.taskId(),"本地构建文件已清理；镜像已在仓库确认（"+verified.digest()+"）。版本记录、日志和共享构建缓存已保留。");
    }catch(Exception error){touchJournal(journal);deferred(pending.taskId(),error);}
  }

  private void retryPending(Path root,Path journals,long currentTask)throws IOException {
    List<Path> pendingFiles=new ArrayList<>();
    try(DirectoryStream<Path> entries=Files.newDirectoryStream(journals,"*.json")) {
      for(Path file:entries)if(file.getFileName().toString().matches("[1-9][0-9]*\\.json")
          &&!file.getFileName().toString().equals(currentTask+".json"))pendingFiles.add(file);
    }
    // Rotate failed attempts to the back so an unavailable registry cannot starve other journals.
    pendingFiles.sort(Comparator.comparing(this::journalAge).thenComparing(path->path.getFileName().toString()));
    int count=0;
    for(Path file:pendingFiles) {
      if(count++>=Math.max(0,Math.min(retriesPerBuild,5))||Thread.currentThread().isInterrupted())break;
      try {
        Pending pending=readJournal(file);
        if(!file.getFileName().toString().equals(pending.taskId()+".json"))throw new IOException("维护文件编号不一致");
        attempt(root,file,pending);
      }catch(Exception error){touchJournal(file);log.warn("Skipped unsafe build storage journal {}: {}",file.getFileName(),safeError(error));}
    }
  }

  private FileTime journalAge(Path path) {
    try{return Files.getLastModifiedTime(path,NOFOLLOW_LINKS);}catch(IOException error){return FileTime.fromMillis(0);}
  }
  private void touchJournal(Path path) {
    try{Files.getFileAttributeView(path,BasicFileAttributeView.class,NOFOLLOW_LINKS).setTimes(FileTime.fromMillis(System.currentTimeMillis()),null,null);}
    catch(IOException error){log.warn("Could not update storage maintenance retry time for {}",path.getFileName());}
  }

  Verified verifyRegistry(Path root,String image,String savedDigest)throws Exception {
    LocalImage local=localImage(root,image);
    Set<String> expected=new HashSet<>(local==null?Set.of():local.digests());
    if(savedDigest!=null&&!savedDigest.isBlank()) {
      if(!isDigest(savedDigest))throw new IOException("保存的镜像摘要格式不正确");
      if(local!=null&&!expected.contains(savedDigest))throw new IOException("本地镜像与保存的摘要不同");
      expected=new HashSet<>(Set.of(savedDigest));
    }
    if(expected.isEmpty())throw new IOException("本地镜像缺少该仓库的摘要，暂缓清理");
    JsonNode remote=mapper.readTree(command(List.of("docker","manifest","inspect","--verbose",image),root,timeout()));
    Set<String> found=new HashSet<>();
    if(remote.isArray())for(JsonNode descriptor:remote)addRemoteDigest(found,descriptor);
    else addRemoteDigest(found,remote);
    expected.retainAll(found);
    if(expected.size()!=1)throw new IOException("仓库镜像摘要未能与本地制品唯一匹配，暂缓清理");
    return new Verified(expected.iterator().next(),local==null?null:local.id());
  }

  private void addRemoteDigest(Set<String> digests,JsonNode manifest) {
    String digest=manifest.path("Descriptor").path("digest").asText();
    if(isDigest(digest))digests.add(digest);
  }

  private LocalImage localImage(Path root,String image)throws Exception {
    String ids=command(List.of("docker","image","ls","--no-trunc","--quiet","--filter","reference="+image),root,timeout()).trim();
    if(ids.isBlank())return null;
    Set<String> selected=new HashSet<>(ids.lines().map(String::trim).toList());
    if(selected.size()!=1||!isDigest(selected.iterator().next()))throw new IOException("无法唯一确认本地镜像");
    String id=selected.iterator().next();
    JsonNode inspected=mapper.readTree(command(List.of("docker","image","inspect",image),root,timeout()));
    if(!inspected.isArray()||inspected.size()!=1||!id.equals(inspected.get(0).path("Id").asText()))throw new IOException("本地镜像标签已变化");
    JsonNode entry=inspected.get(0);boolean tagged=false;
    for(JsonNode tag:entry.path("RepoTags"))if(image.equals(tag.asText()))tagged=true;
    if(!tagged)throw new IOException("本地镜像标签不匹配");
    String repository=image.substring(0,image.lastIndexOf(':'));
    Set<String> digests=new HashSet<>();
    for(JsonNode value:entry.path("RepoDigests")) {
      String ref=value.asText(),prefix=repository+"@";
      if(ref.startsWith(prefix)&&isDigest(ref.substring(prefix.length())))digests.add(ref.substring(prefix.length()));
    }
    return new LocalImage(id,Set.copyOf(digests));
  }

  private void removeImage(Path root,String image,Verified verified)throws Exception {
    LocalImage current=localImage(root,image);
    if(current==null)return;
    if(verified.localImageId()==null||!verified.localImageId().equals(current.id())||!current.digests().contains(verified.digest()))
      throw new IOException("待删除的本地镜像已变化");
    String containers=command(List.of("docker","ps","--all","--quiet","--filter","ancestor="+current.id()),root,timeout()).trim();
    if(!containers.isEmpty())throw new IOException("该镜像被本地容器引用，保留镜像");
    // No force and no parent-layer pruning: only this task's unique tag is removed.
    command(List.of("docker","image","rm","--no-prune",image),root,timeout());
  }

  String command(List<String> args,Path directory,long timeoutSeconds)throws Exception {
    return BoundedProcessRunner.run(args,directory,null,timeoutSeconds,null);
  }
  private long timeout(){return Math.max(1,Math.min(registryTimeoutSeconds,60));}
  private static boolean isDigest(String value){return value!=null&&value.matches("sha256:[a-f0-9]{64}");}

  Path validatedRoot()throws IOException {
    Path root=Paths.get(workspaceRoot).toAbsolutePath().normalize();
    if(root.getNameCount()<2||!Files.isDirectory(root,NOFOLLOW_LINKS)||Files.isSymbolicLink(root)
        ||!root.equals(root.toRealPath()))throw new IOException("工作目录必须是存在且不经过符号链接的独立目录");
    return root;
  }

  private Path journalDirectory(Path root)throws IOException {
    Path directory=root.resolve(JOURNAL_DIRECTORY);
    if(!Files.exists(directory,NOFOLLOW_LINKS))Files.createDirectory(directory);
    if(!Files.isDirectory(directory,NOFOLLOW_LINKS)||Files.isSymbolicLink(directory)||!directory.equals(directory.toRealPath()))
      throw new IOException("维护记录目录不安全");
    return directory;
  }

  private void writeJournal(Path path,Pending pending)throws IOException {
    byte[] content=mapper.writeValueAsBytes(pending);
    try(SeekableByteChannel channel=Files.newByteChannel(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,NOFOLLOW_LINKS))) {
      Channels.newOutputStream(channel).write(content);
    }
  }

  private Pending readJournal(Path path)throws IOException {
    if(!Files.isRegularFile(path,NOFOLLOW_LINKS)||Files.size(path)>16_384)throw new IOException("维护记录不是有效的小型普通文件");
    try(SeekableByteChannel channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,NOFOLLOW_LINKS))) {
      return mapper.readValue(Channels.newInputStream(channel),Pending.class);
    }
  }

  /** Descriptor-relative removal prevents symlink replacement from redirecting a recursive delete. */
  void removeWorkspace(Path root,long taskId)throws IOException {
    if(taskId<1||!root.equals(validatedRoot()))throw new IOException("非法任务工作目录");
    Path name=Path.of(Long.toString(taskId)),directory=root.resolve(name);
    if(!Files.exists(directory,NOFOLLOW_LINKS))return;
    if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,NOFOLLOW_LINKS))throw new IOException("任务工作目录不是普通目录");
    try(SecureDirectoryStream<Path> secure=openSecureDirectory(root)) {
      try(SecureDirectoryStream<Path> child=secure.newDirectoryStream(name,NOFOLLOW_LINKS)) {removeContents(child);}
      secure.deleteDirectory(name);
    }
  }

  private SecureDirectoryStream<Path> openSecureDirectory(Path absolute)throws IOException {
    DirectoryStream<Path> opened=Files.newDirectoryStream(absolute.getRoot());
    if(!(opened instanceof SecureDirectoryStream<Path> secure)) {
      opened.close();throw new IOException("文件系统不支持安全目录删除，已保留构建目录");
    }
    // Open every ancestor relative to an already-open descriptor, so replacing even the
    // workspace root with a symlink cannot redirect deletion between validation and use.
    try {
      for(Path component:absolute) {
        SecureDirectoryStream<Path> next=secure.newDirectoryStream(component,NOFOLLOW_LINKS);
        secure.close();secure=next;
      }
      return secure;
    }catch(IOException|RuntimeException error){secure.close();throw error;}
  }

  private void removeContents(SecureDirectoryStream<Path> directory)throws IOException {
    for(Path entry:directory) {
      Path name=entry.getFileName();
      BasicFileAttributes attributes=directory.getFileAttributeView(name,BasicFileAttributeView.class,NOFOLLOW_LINKS).readAttributes();
      if(attributes.isDirectory()) {
        try(SecureDirectoryStream<Path> child=directory.newDirectoryStream(name,NOFOLLOW_LINKS)){removeContents(child);}
        directory.deleteDirectory(name);
      }else directory.deleteFile(name); // A symlink is unlinked; its target is never traversed.
    }
  }

  private void deferred(long taskId,Exception error) {
    String detail=safeError(error);
    log.warn("Build #{} storage maintenance deferred: {}",taskId,detail);
    maintenanceLog(taskId,"本地清理暂缓："+detail+"。打包仍然成功；后续成功打包会重试已有维护记录。");
  }

  private String safeError(Exception error) {
    String text=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
    text=text.replaceAll("(?i)(https?://)[^/@\\s]+@","$1[hidden]@").replace('\r',' ').replace('\n',' ');
    return text.length()>500?text.substring(0,500):text;
  }

  private void maintenanceLog(long taskId,String message) {
    try {
      transactions.executeWithoutResult(status->{
        Optional<ReleaseStep> push=steps.findByReleaseTaskIdOrderById(taskId).stream().filter(step->step.getStepType()==StepType.PUSH_IMAGE).findFirst();
        if(push.isEmpty())return;
        ReleaseStep step=push.get();StringBuilder tail=new StringBuilder(step.getLogText()==null?"":step.getLogText());
        BoundedProcessRunner.appendTail(tail,"[存储维护] "+message+System.lineSeparator());step.setLogText(tail.toString());steps.save(step);
      });
    }catch(Exception error){log.warn("Could not append storage maintenance log for build #{}",taskId);}
  }
}
