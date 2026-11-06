package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import static com.local.release.domain.ModelEnums.*;

/** App-scoped integration access is independent of the existing 24-hour delivery tokens. */
@Service
public class AppIntegrationAccessService {
  private static final List<String> ENVIRONMENTS=List.of("dev","test","prod");
  private static final Pattern TOKEN=Pattern.compile("mapp_([1-9][0-9]{0,18})_[A-Za-z0-9_-]{43}");
  private static final Pattern RELEASE_PATH=Pattern.compile("/api/mobile/releases/([1-9][0-9]{0,18})(/download|/confirm)?");
  private final AppIntegrationAccessRepository access;
  private final AppServiceRepository services;
  private final ReleaseTaskRepository tasks;
  private final MobileDeliveryRepository deliveries;
  private final MobileReleaseService mobile;
  private final TransactionTemplate transactions;
  private final SecureRandom random=new SecureRandom();
  Clock clock=Clock.systemDefaultZone();

  public AppIntegrationAccessService(AppIntegrationAccessRepository access,AppServiceRepository services,
      ReleaseTaskRepository tasks,MobileDeliveryRepository deliveries,MobileReleaseService mobile,PlatformTransactionManager tx){
    this.access=access;this.services=services;this.tasks=tasks;this.deliveries=deliveries;this.mobile=mobile;
    this.transactions=new TransactionTemplate(tx);
  }
  public record Status(Long serviceId,String name,List<String> environments,boolean active,String tokenPrefix,
      LocalDateTime createdAt,LocalDateTime updatedAt,LocalDateTime revokedAt,String createdBy,String updatedBy){}
  public record Issued(Status access,String token){}
  public record TokenPrincipal(long serviceId,Set<String> environments,String name) implements Principal {
    public TokenPrincipal{environments=Set.copyOf(environments);}
    @Override public String getName(){return name;}
  }
  public record ReleasePage(List<MobileReleaseService.Detail> items,Long nextBeforeId,boolean hasMore){}

  public Status status(Long serviceId){AppService app=app(serviceId);return status(app,access.findByServiceId(serviceId).orElse(null));}

  public synchronized Issued issue(Long serviceId,String name,List<String> environments,String operator){
    String label=label(name),actor=actor(operator);List<String> scopes=validatedEnvironments(environments);
    byte[] bytes=new byte[32];random.nextBytes(bytes);
    String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),token="mapp_"+serviceId+"_"+secret;
    Status result=transactions.execute(tx->{
      AppService app=app(serviceId);if(!app.isEnabled())throw new IllegalArgumentException("请先启用 App，再签发对接凭据");
      AppIntegrationAccess row=access.locked(serviceId).orElseGet(()->new AppIntegrationAccess(serviceId,label,String.join(",",scopes),actor));
      row.setName(label);row.setEnvironments(String.join(",",scopes));row.setTokenHash(hash(token));
      row.setTokenPrefix("mapp_"+serviceId+"_"+secret.substring(0,8));row.setRevokedAt(null);row.setUpdatedBy(actor);
      return status(app,access.saveAndFlush(row));
    });
    return new Issued(Objects.requireNonNull(result),token);
  }

  public synchronized Status revoke(Long serviceId,String operator){
    String actor=actor(operator);
    return transactions.execute(tx->{
      AppService app=app(serviceId);AppIntegrationAccess row=access.locked(serviceId).orElse(null);
      if(row!=null&&(row.getTokenHash()!=null||row.getRevokedAt()==null)){
        row.setTokenHash(null);row.setRevokedAt(LocalDateTime.now(clock));row.setUpdatedBy(actor);row=access.saveAndFlush(row);
      }
      return status(app,row);
    });
  }

  /** No expiry is implied by nulls: a populated digest, valid scopes and an enabled App are all required. */
  public TokenPrincipal validateAccess(String token,String method,String path){
    if(token==null||method==null||path==null)return null;
    Matcher match=TOKEN.matcher(token);if(!match.matches())return null;
    Long serviceId=positiveId(match.group(1));if(serviceId==null)return null;
    AppService app=services.findById(serviceId).orElse(null);
    if(app==null||!app.isEnabled()||app.getServiceType()!=ServiceType.FLUTTER_ANDROID)return null;
    AppIntegrationAccess row=access.findByServiceId(serviceId).orElse(null);
    if(row==null||!Objects.equals(row.getServiceId(),serviceId)||row.getRevokedAt()!=null||row.getTokenHash()==null
        ||!row.getTokenHash().matches("[a-f0-9]{64}")||!MessageDigest.isEqual(row.getTokenHash().getBytes(StandardCharsets.US_ASCII),hash(token).getBytes(StandardCharsets.US_ASCII)))return null;
    Set<String> scopes=storedEnvironments(row);if(scopes.isEmpty())return null;
    TokenPrincipal principal=new TokenPrincipal(serviceId,scopes,"app-integration:"+serviceId);
    if("GET".equals(method)&&path.equals("/api/mobile/apps/"+serviceId+"/releases"))return principal;
    Matcher release=RELEASE_PATH.matcher(path);if(!release.matches())return null;
    String suffix=release.group(2);
    if(!("GET".equals(method)&&(suffix==null||suffix.equals("/download"))||"POST".equals(method)&&"/confirm".equals(suffix)))return null;
    Long taskId=positiveId(release.group(1));if(taskId==null)return null;
    ReleaseTask task=tasks.findById(taskId).orElse(null);
    if(!permitted(task,principal))return null;
    MobileDelivery delivery=deliveries.findByReleaseTaskId(taskId).orElse(null);
    return delivery!=null&&Objects.equals(delivery.getArtifactId(),task.getArtifactId())?principal:null;
  }

  public ReleasePage listReleases(TokenPrincipal principal,String environmentName,Long beforeId,Integer requestedLimit){
    if(principal==null||principal.serviceId()<=0||principal.environments().isEmpty()||!ENVIRONMENTS.containsAll(principal.environments()))throw denied();
    AppService app=app(principal.serviceId());if(!app.isEnabled())throw denied();
    int limit=requestedLimit==null?20:requestedLimit;
    if(limit<1||limit>100)throw new IllegalArgumentException("每页数量必须在 1 到 100 之间");
    if(beforeId!=null&&beforeId<=0)throw new IllegalArgumentException("分页游标必须是正整数");
    Set<String> scopes=principal.environments();
    if(environmentName!=null){
      if(!ENVIRONMENTS.contains(environmentName))throw new IllegalArgumentException("环境参数必须为 dev、test 或 prod");
      if(!scopes.contains(environmentName))throw denied();
      scopes=Set.of(environmentName);
    }
    var page=tasks.findIntegrationDeliveries(principal.serviceId(),TaskKind.DEPLOY,scopes,beforeId,PageRequest.of(0,limit));
    List<ReleaseTask> rows=page.getContent();
    // Defence in depth: never trust a query result to expand the authenticated scope.
    if(rows.stream().anyMatch(task->!permitted(task,principal)))throw denied();
    List<MobileReleaseService.Detail> items=rows.stream().map(task->mobile.detail(task.getId())).toList();
    Long next=page.hasNext()&&!rows.isEmpty()?rows.get(rows.size()-1).getId():null;
    return new ReleasePage(items,next,page.hasNext());
  }

  private boolean permitted(ReleaseTask task,TokenPrincipal principal){return task!=null&&task.getTaskKind()==TaskKind.DEPLOY
      &&"MOBILE".equals(task.getDeliveryType())&&!task.isSimulate()&&task.getArtifactId()!=null
      &&Objects.equals(task.getServiceId(),principal.serviceId())&&principal.environments().contains(task.getEnvironmentName());}
  private AppService app(Long id){
    if(id==null||id<=0)throw new IllegalArgumentException("App 服务编号无效");
    AppService value=services.findById(id).orElseThrow(()->new IllegalArgumentException("App 服务不存在"));
    if(value.getServiceType()!=ServiceType.FLUTTER_ANDROID)throw denied();return value;
  }
  private Status status(AppService app,AppIntegrationAccess row){
    if(row==null)return new Status(app.getId(),null,List.of(),false,null,null,null,null,null,null);
    Set<String> scopes=storedEnvironments(row);
    boolean active=app.isEnabled()&&row.getRevokedAt()==null&&row.getTokenHash()!=null&&row.getTokenHash().matches("[a-f0-9]{64}")&&!scopes.isEmpty();
    return new Status(app.getId(),row.getName(),ENVIRONMENTS.stream().filter(scopes::contains).toList(),active,row.getTokenPrefix(),row.getCreatedAt(),row.getUpdatedAt(),row.getRevokedAt(),row.getCreatedBy(),row.getUpdatedBy());
  }
  private static List<String> validatedEnvironments(List<String> values){
    if(values==null||values.isEmpty()||values.size()>3||values.stream().anyMatch(v->v==null||!ENVIRONMENTS.contains(v))||new HashSet<>(values).size()!=values.size())
      throw new IllegalArgumentException("请明确选择 dev、test、prod 中至少一个环境，且不能重复");
    return ENVIRONMENTS.stream().filter(values::contains).toList();
  }
  private static Set<String> storedEnvironments(AppIntegrationAccess row){
    if(row.getEnvironments()==null)return Set.of();
    try{return Set.copyOf(validatedEnvironments(Arrays.asList(row.getEnvironments().split(",",-1))));}
    catch(IllegalArgumentException invalid){return Set.of();}
  }
  private static String label(String name){if(name==null||name.isBlank()||name.trim().length()>100)throw new IllegalArgumentException("请输入 1 到 100 字的对接名称");return name.trim();}
  private static String actor(String operator){if(operator==null||operator.isBlank()||operator.length()>120)throw denied();return operator;}
  private static Long positiveId(String value){try{long id=Long.parseLong(value);return id>0?id:null;}catch(NumberFormatException invalid){return null;}}
  private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
  private static AccessDeniedException denied(){return new AccessDeniedException("对接凭据没有当前 App 或环境的访问权限");}
}
