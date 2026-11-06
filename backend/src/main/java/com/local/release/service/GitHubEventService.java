package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import com.local.release.repo.*;
import com.local.release.security.MenuPermissions;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Small, permission-filtered feed. Reading it never creates a build or changes a Git branch. */
@Service
public class GitHubEventService {
  public record EventService(Long id,String name,String serviceKey,ServiceType serviceType,boolean canBuild){}
  public record Item(Long id,List<EventService> services,String branch,String commitId,String summary,String actor,Instant receivedAt,String eventUrl,boolean unread){}
  public record Feed(boolean enabled,boolean unread,Long latestEventId,List<Item> items){}
  private final GitHubWebhookSettings settings;
  private final GitHubPushEventRepository events;
  private final GitHubEventReadStateRepository reads;
  private final AppServiceRepository services;
  private final AdminUserRepository users;
  private final ProductionMergeRepository merges;
  private final TransactionTemplate tx;
  private final Object readLock=new Object();
  Clock clock=Clock.systemUTC();
  public GitHubEventService(GitHubWebhookSettings settings,GitHubPushEventRepository events,GitHubEventReadStateRepository reads,AppServiceRepository services,AdminUserRepository users,ProductionMergeRepository merges,PlatformTransactionManager manager){
    this.settings=settings;this.events=events;this.reads=reads;this.services=services;this.users=users;this.merges=merges;this.tx=new TransactionTemplate(manager);
  }
  /** Called only from the verified webhook transaction, after delivery replay validation. */
  void record(String delivery,String fullName,String identity,String branch,String before,String sha,String summary,String actor,Instant receivedAt){
    events.saveAndFlush(new GitHubPushEvent(delivery,fullName,identity,branch,before,sha,summary,actor,receivedAt));
  }
  public Feed feed(String operator){return feed(user(operator));}
  private Feed feed(AdminUser user){
    if(!settings.enabled())return new Feed(false,false,null,List.of());
    Set<String> permissions=MenuPermissions.effective(user.menuPermissionSet());
    Map<String,List<EventService>> visible=new LinkedHashMap<>();
    for(AppService service:services.findAll()){
      boolean app=service.getServiceType()==ServiceType.FLUTTER_ANDROID;
      if(!user.isAdmin()&&(app?!permissions.contains("apps"):permissions.stream().noneMatch(Set.of("services","builds","release")::contains)))continue;
      String name=repositoryName(service.getGitRepo(),settings.owner());if(name==null)continue;
      boolean canBuild=service.isEnabled()&&(user.isAdmin()||permissions.contains(app?"apps":"builds"));
      visible.computeIfAbsent(name,key->new ArrayList<>()).add(new EventService(service.getId(),service.getName(),service.getServiceKey(),service.getServiceType(),canBuild));
    }
    if(visible.isEmpty())return new Feed(true,false,null,List.of());
    // Give master callbacks a brief grace period: GitHub may respond before the merge's exact SHA proof is persisted.
    Set<String> pending=new HashSet<>();pending.add("no-pending-repository");
    for(ProductionMerge merge:merges.findByStatus(ProductionMerge.Status.RUNNING))
      if(merge.getRepositoryIdentity()!=null)pending.add(merge.getRepositoryIdentity());
    // Restart recovery requeues an attempted merge; its push may already have reached GitHub.
    // A brand-new queued merge has never pushed and must not hide unrelated human master events.
    for(ProductionMerge merge:merges.findByStatus(ProductionMerge.Status.QUEUED))
      if(merge.getAttempts()>0&&merge.getRepositoryIdentity()!=null)pending.add(merge.getRepositoryIdentity());
    List<GitHubPushEvent> latest=events.latestVisible(visible.keySet(),clock.instant().minusSeconds(15),pending,
      List.of(ProductionMerge.Status.MERGED),TaskKind.DEPLOY,TaskStatus.SUCCESS,PageRequest.of(0,5));
    Set<Long> seen=new HashSet<>();
    if(!latest.isEmpty())reads.findByUserIdAndEventIdIn(user.getId(),latest.stream().map(GitHubPushEvent::getId).toList()).forEach(r->seen.add(r.getEventId()));
    List<Item> items=latest.stream().map(e->new Item(e.getId(),List.copyOf(visible.get(e.getRepositoryFullName())),e.getBranch(),e.getCommitId(),e.getSummary(),e.getActor(),e.getReceivedAt(),"https://github.com/"+e.getRepositoryFullName()+"/commit/"+e.getCommitId(),!seen.contains(e.getId()))).toList();
    return new Feed(true,items.stream().anyMatch(Item::unread),items.stream().map(Item::id).max(Long::compareTo).orElse(null),items);
  }
  public void markRead(String operator,Long throughEventId,List<Long> eventIds){
    AdminUser user=user(operator);
    if(throughEventId==null||throughEventId<=0)throw new IllegalArgumentException("请选择有效的 Git 动态阅读位置");
    if(eventIds!=null&&(eventIds.size()>5||eventIds.stream().anyMatch(id->id==null||id<=0||id>throughEventId)))throw new IllegalArgumentException("Git 动态阅读列表无效");
    synchronized(readLock){tx.executeWithoutResult(status->{
      Feed current=feed(user);if(current.latestEventId()==null)return;
      // Record only shown, visible IDs. A master event held for classification must remain unread later.
      for(Item item:current.items())if(item.unread()&&item.id()<=throughEventId&&(eventIds==null||eventIds.contains(item.id())))reads.save(new GitHubEventReadState(user.getId(),item.id()));
      reads.flush();
    });}
  }
  private AdminUser user(String operator){return users.findByUsername(operator).filter(AdminUser::isEnabled).orElseThrow(()->new AccessDeniedException("请使用有效的平台登录账号查看 Git 动态"));}
  static boolean validBranch(String branch){
    return branch!=null&&!branch.isBlank()&&branch.length()<=255&&!branch.startsWith("-")&&!branch.startsWith("/")&&!branch.endsWith("/")&&!branch.endsWith(".")&&!branch.contains("..")&&!branch.contains("@{")&&!branch.contains("//")
      &&!branch.matches(".*[\\p{Cntrl}\\s~^:?*\\[\\\\].*")&&Arrays.stream(branch.split("/")).noneMatch(part->part.startsWith(".")||part.endsWith(".lock"));
  }
  static String repositoryName(String repository,String owner){
    if(repository==null)return null;String value=repository.trim(),path;
    try{
      if(value.startsWith("git@github.com:"))path=value.substring("git@github.com:".length());
      else{URI uri=URI.create(value);if(!"github.com".equalsIgnoreCase(uri.getHost())||uri.getQuery()!=null||uri.getFragment()!=null)return null;
        if(!Set.of("https","ssh").contains(uri.getScheme())||uri.getPort()!=-1&&uri.getPort()!=("ssh".equals(uri.getScheme())?22:443))return null;path=uri.getPath();if(path==null)return null;path=path.replaceFirst("^/","");}
      path=path.replaceAll("/+$","").replaceAll("\\.git$","").toLowerCase(Locale.ROOT);
      return path.matches(java.util.regex.Pattern.quote(owner)+"/[a-z0-9_.-]{1,100}")?path:null;
    }catch(IllegalArgumentException invalid){return null;}
  }
}
