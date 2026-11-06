package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.web.ReleaseTaskView;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real H2 repositories and transactions, with all download/publication side effects mocked. */
@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class AppIntegrationAccessServiceTest {
  @Autowired AppIntegrationAccessRepository credentials;
  @Autowired AppServiceRepository apps;
  @Autowired ReleaseTaskRepository tasks;
  @Autowired MobileDeliveryRepository deliveries;
  @Autowired PlatformTransactionManager transactions;
  MobileReleaseService mobile;
  AppIntegrationAccessService service;
  AppService app,other,server;
  static final String DIGEST="a".repeat(64),COMMIT="b".repeat(40);
  @BeforeEach void setup(){
    credentials.deleteAll();deliveries.deleteAll();tasks.deleteAll();apps.deleteAll();
    mobile=mock(MobileReleaseService.class);service=instance();
    app=app("app-a",ServiceType.FLUTTER_ANDROID);other=app("app-b",ServiceType.FLUTTER_ANDROID);server=app("backend",ServiceType.BACKEND);
    when(mobile.detail(anyLong())).thenAnswer(call->{
      ReleaseTask task=tasks.findById(call.getArgument(0)).orElseThrow();
      MobileArtifact metadata=new MobileArtifact(task.getArtifactId(),10L,task.getServiceId(),"com.example.app","1.0",1,DIGEST,3,"c".repeat(64),COMMIT,"3.47.3","private");
      metadata.setBuildEnvironmentName(task.getEnvironmentName());metadata.setBuildMode("RELEASE");
      return new MobileReleaseService.Detail(ReleaseTaskView.one(task,apps),metadata,null,null,"WAITING",null,null);
    });
  }
  AppIntegrationAccessService instance(){
    var value=new AppIntegrationAccessService(credentials,apps,tasks,deliveries,mobile,transactions);
    value.clock=Clock.fixed(Instant.parse("2026-10-17T07:00:00Z"),ZoneId.of("Asia/Shanghai"));return value;
  }
  AppService app(String key,ServiceType type){AppService value=new AppService();value.setName(key);value.setServiceKey(key);value.setServiceType(type);return apps.saveAndFlush(value);}
  ReleaseTask task(AppService owner,String environment,TaskKind kind,String deliveryType,boolean simulate,boolean withDelivery){
    ReleaseTask value=new ReleaseTask(owner.getId(),kind,100L,environment,"release20261017",null,simulate);
    value.setDeliveryType(deliveryType);value.setStatus(TaskStatus.WAITING_CONFIRMATION);value.setCommitId(COMMIT);value=tasks.saveAndFlush(value);
    if(withDelivery)deliveries.saveAndFlush(new MobileDelivery(value.getId(),100L,DIGEST));return value;
  }
  ReleaseTask release(AppService owner,String environment){return task(owner,environment,TaskKind.DEPLOY,"MOBILE",false,true);}
  AppIntegrationAccessService.Issued issue(String... environments){return service.issue(app.getId(),"version-center",List.of(environments),"admin");}
  String path(ReleaseTask task){return "/api/mobile/releases/"+task.getId();}
  void denied(String token,String method,String path){assertThat(service.validateAccess(token,method,path)).as("%s %s",method,path).isNull();}

  @Test void tokenIsPermanentHashedAndSurvivesServiceRecreation()throws Exception{
    ReleaseTask pre=release(app,"test");var issued=issue("test");
    assertThat(issued.access().active()).isTrue();assertThat(issued.access().environments()).containsExactly("test");
    Object stored=credentials.findByServiceId(app.getId()).orElseThrow();
    String storedHash=(String)ReflectionTestUtils.getField(stored,"tokenHash");assertThat(storedHash).matches("[a-f0-9]{64}").isNotEqualTo(issued.token());
    String publicJson=new ObjectMapper().findAndRegisterModules().writeValueAsString(stored);assertThat(publicJson).doesNotContain(issued.token(),storedHash,"tokenHash");
    service.clock=Clock.offset(service.clock,Duration.ofDays(3650));assertThat(service.validateAccess(issued.token(),"GET",path(pre))).isNotNull();
    service=instance();service.clock=Clock.offset(service.clock,Duration.ofDays(7300));assertThat(service.validateAccess(issued.token(),"GET",path(pre)+"/download")).isNotNull();
    assertThat(credentials.count()).isEqualTo(1);assertThat(service.status(app.getId()).active()).isTrue();
  }

  @Test void rotationAndRevocationInvalidateOldTokensAndDoNotAffectAnotherApp(){
    ReleaseTask pre=release(app,"test"),foreign=release(other,"test");var first=issue("test");var otherToken=service.issue(other.getId(),"other-center",List.of("test"),"admin");
    var second=issue("test","prod");assertThat(second.token()).isNotEqualTo(first.token());denied(first.token(),"GET",path(pre));
    assertThat(service.validateAccess(second.token(),"GET",path(pre))).isNotNull();assertThat(service.validateAccess(otherToken.token(),"GET",path(foreign))).isNotNull();
    assertThat(service.revoke(app.getId(),"admin").active()).isFalse();denied(second.token(),"GET",path(pre));assertThat(service.status(app.getId()).revokedAt()).isNotNull();
    assertThat(service.validateAccess(otherToken.token(),"GET",path(foreign))).isNotNull();assertThat(credentials.count()).isEqualTo(2);
  }

  @Test void scopedTokenAllowsOnlyTheExactSupportedOperationsForItsAppAndEnvironment(){
    ReleaseTask pre=release(app,"test"),prod=release(app,"prod"),foreign=release(other,"test");var issued=issue("test");String token=issued.token();
    assertThat(service.validateAccess(token,"GET","/api/mobile/apps/"+app.getId()+"/releases")).isNotNull();
    assertThat(service.validateAccess(token,"GET",path(pre))).isNotNull();assertThat(service.validateAccess(token,"GET",path(pre)+"/download")).isNotNull();assertThat(service.validateAccess(token,"POST",path(pre)+"/confirm")).isNotNull();
    for(ReleaseTask rejected:List.of(prod,foreign)){denied(token,"GET",path(rejected));denied(token,"GET",path(rejected)+"/download");denied(token,"POST",path(rejected)+"/confirm");}
    denied(token,"GET","/api/mobile/apps/"+other.getId()+"/releases");verify(mobile,never()).confirm(any(),any(),any());verify(mobile,never()).downloadFile(any());
  }

  @Test void buildServerSimulationMissingDeliveryAndUnknownTasksAreRejected(){
    String token=issue("test").token();
    ReleaseTask mismatched=task(app,"test",TaskKind.DEPLOY,"MOBILE",false,false);deliveries.saveAndFlush(new MobileDelivery(mismatched.getId(),101L,DIGEST));
    List<ReleaseTask> invalid=List.of(task(app,"test",TaskKind.BUILD,"MOBILE",false,true),task(app,"test",TaskKind.DEPLOY,"SERVER",false,true),task(app,"test",TaskKind.DEPLOY,"MOBILE",true,true),task(app,"test",TaskKind.DEPLOY,"MOBILE",false,false),release(server,"test"),mismatched);
    for(ReleaseTask task:invalid){denied(token,"GET",path(task));denied(token,"POST",path(task)+"/confirm");}
    denied(token,"GET","/api/mobile/releases/9223372036854775807");
  }

  @Test void disabledDeletedAndNonAppServicesCannotUseOrReceiveIntegrationAccess(){
    ReleaseTask pre=release(app,"test");String token=issue("test").token();app.setEnabled(false);apps.saveAndFlush(app);
    denied(token,"GET",path(pre));denied(token,"GET","/api/mobile/apps/"+app.getId()+"/releases");assertThatThrownBy(()->issue("test")).isInstanceOf(RuntimeException.class);
    app.setEnabled(true);apps.saveAndFlush(app);apps.delete(app);denied(token,"GET",path(pre));
    assertThatThrownBy(()->service.issue(server.getId(),"invalid",List.of("test"),"admin")).isInstanceOf(RuntimeException.class);
  }

  @Test void credentialScopeMustBeExplicitAndCannotContainUnknownEnvironments(){
    for(List<String> environments:List.of(List.<String>of(),List.of("all"),List.of("*"),List.of("test","staging")))
      assertThatThrownBy(()->service.issue(app.getId(),"invalid",environments,"admin")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(()->service.issue(app.getId(),"invalid",null,"admin")).isInstanceOf(RuntimeException.class);
    assertThat(credentials.count()).isZero();
  }

  @Test void unsupportedRoutesVerbsAndEncodedAliasesCannotEscalatePrivileges(){
    ReleaseTask pre=release(app,"test");String token=issue("test").token();String base=path(pre);
    for(String route:List.of("/api/users","/api/servers","/api/runtime/restarts","/api/apps/signing-profiles","/api/apps/services/"+app.getId()+"/integration-access","/api/mobile/releases","/api/mobile/artifacts/100",base+"/access-token",base+"/merge/retry",base+"/download-extra",base+"/",base+";ignored=1","/api/apps/mobile/releases/"+pre.getId(),"/api/mobile/releases/+"+pre.getId(),"/api/mobile/releases/%2B"+pre.getId(),"/api//mobile/releases/"+pre.getId()))
      for(String method:List.of("GET","POST","DELETE"))denied(token,method,route);
    denied(token,"POST",base);denied(token,"DELETE",base);denied(token,"HEAD",base+"/download");denied(token,"GET",base+"/confirm");
    denied(token+"x","GET",base);denied("invalid","GET",base);denied(null,"GET",base);
    denied(token.substring(0,token.length()-1)+(token.endsWith("a")?"b":"a"),"GET",base);
  }

  @Test void aDisabledAppStillPermitsExplicitRevocationButNotTokenUse(){
    ReleaseTask pre=release(app,"test");String token=issue("test").token();app.setEnabled(false);apps.saveAndFlush(app);
    assertThat(service.status(app.getId()).active()).isFalse();assertThat(service.revoke(app.getId(),"admin").revokedAt()).isNotNull();
    app.setEnabled(true);apps.saveAndFlush(app);denied(token,"GET",path(pre));
  }

  @Test void missingHashOrDamagedStoredScopeNeverMeansPermanentUnrestrictedAccess(){
    ReleaseTask pre=release(app,"test");
    for(String invalidHash:Arrays.asList(null,"","bad-hash")){
      String token=issue("test").token();var row=credentials.findByServiceId(app.getId()).orElseThrow();row.setTokenHash(invalidHash);credentials.saveAndFlush(row);
      denied(token,"GET",path(pre));assertThat(service.status(app.getId()).active()).isFalse();
    }
    for(String invalidScope:List.of("","test,","test,prod,unknown","test,test","*")){
      String token=issue("test").token();var row=credentials.findByServiceId(app.getId()).orElseThrow();row.setEnvironments(invalidScope);credentials.saveAndFlush(row);
      denied(token,"GET",path(pre));assertThat(service.status(app.getId()).active()).isFalse();assertThat(service.status(app.getId()).environments()).isEmpty();
    }
  }

  @Test void listPaginationFiltersBeforeApplyingLimitAndCannotLeakAnotherAppEnvironmentOrSimulation(){
    ReleaseTask first=release(app,"test"),second=release(app,"test"),third=release(app,"test");
    release(app,"prod");release(other,"test");task(app,"test",TaskKind.DEPLOY,"MOBILE",true,true);task(app,"test",TaskKind.BUILD,"MOBILE",false,true);task(app,"test",TaskKind.DEPLOY,"SERVER",false,true);task(app,"test",TaskKind.DEPLOY,"MOBILE",false,false);
    String token=issue("test").token();var principal=service.validateAccess(token,"GET","/api/mobile/apps/"+app.getId()+"/releases");
    var page=service.listReleases(principal,null,null,2);assertThat(page.items()).extracting(item->item.task().id()).containsExactly(third.getId(),second.getId());assertThat(page.hasMore()).isTrue();assertThat(page.nextBeforeId()).isEqualTo(second.getId());
    var next=service.listReleases(principal,"test",page.nextBeforeId(),2);assertThat(next.items()).extracting(item->item.task().id()).containsExactly(first.getId());assertThat(next.hasMore()).isFalse();
    assertThatThrownBy(()->service.listReleases(principal,"prod",null,20)).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->service.listReleases(principal,"*",null,20)).isInstanceOf(RuntimeException.class);
    verify(mobile,times(3)).detail(anyLong());
  }

  @Test void listRejectsInvalidPageParametersInsteadOfFallingBackToUnboundedQueries(){
    String token=issue("test").token();var principal=service.validateAccess(token,"GET","/api/mobile/apps/"+app.getId()+"/releases");
    for(int limit:new int[]{-1,0,101,Integer.MAX_VALUE})assertThatThrownBy(()->service.listReleases(principal,"test",null,limit)).isInstanceOf(IllegalArgumentException.class);
    for(long before:new long[]{-1,0})assertThatThrownBy(()->service.listReleases(principal,"test",before,20)).isInstanceOf(IllegalArgumentException.class);
    verify(mobile,never()).detail(anyLong());
  }
}
