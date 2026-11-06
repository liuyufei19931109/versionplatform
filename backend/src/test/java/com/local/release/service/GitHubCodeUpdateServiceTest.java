package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.local.release.domain.*;
import com.local.release.repo.*;
import com.local.release.security.CredentialSecretCrypto;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class GitHubCodeUpdateServiceTest {
  @Autowired GitHubWebhookDeliveryRepository deliveries;
  @Autowired GitHubBranchObservationRepository observations;
  @Autowired AppServiceRepository services;
  @Autowired VersionBatchRepository versions;
  @Autowired VersionRepositoryRepository repositories;
  @Autowired VersionMemberRepository members;
  @Autowired VersionPreviewRepository previews;
  @Autowired AdminUserRepository users;
  @Autowired ReleaseTaskRepository tasks;
  @Autowired ArtifactRepository artifacts;
  @Autowired MobileArtifactRepository mobile;
  @Autowired ProductionMergeRepository merges;
  @Autowired GitHubPushEventRepository pushEvents;
  @Autowired GitHubEventReadStateRepository eventReads;
  @Autowired PlatformTransactionManager manager;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  static final String SECRET="test-only-webhook-secret-not-a-live-credential",BRANCH="release20261020",REPO="git@github.com:example-org/server.git",FULL="example-org/server",SHA="a".repeat(40),NEXT="b".repeat(40);
  ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  GitHubBranchReader git;GitHubCodeUpdateService updates;VersionManagementService management;
  GitHubEventService eventFeed;
  AppService backend,frontend,app;VersionBatch version;
  @BeforeEach void setup()throws Exception{
    eventReads.deleteAll();pushEvents.deleteAll();deliveries.deleteAll();observations.deleteAll();members.deleteAll();repositories.deleteAll();previews.deleteAll();versions.deleteAll();mobile.deleteAll();artifacts.deleteAll();merges.deleteAll();tasks.deleteAll();services.deleteAll();users.deleteAll();
    user("admin",List.of());user("web",List.of("versions","builds"));user("mobile",List.of("versions","apps"));user("no-version",List.of("builds"));
    git=mock(GitHubBranchReader.class);when(git.head(anyString(),anyString())).thenReturn(SHA);
    management=new VersionManagementService(versions,repositories,members,previews,services,users,mock(VersionGitRepository.class),mock(CredentialSecretCrypto.class),json,manager);
    eventFeed=new GitHubEventService(new GitHubWebhookSettings(true,SECRET,"example-org"),pushEvents,eventReads,services,users,merges,manager);
    eventFeed.clock=Clock.fixed(Instant.parse("2026-10-17T12:01:00Z"),ZoneOffset.UTC);
    updates=newService(true,SECRET);
    backend=service("backend",ServiceType.BACKEND,REPO);frontend=service("frontend",ServiceType.FRONTEND,"https://github.com/example-org/server.git");app=service("app",ServiceType.FLUTTER_ANDROID,"git@github.com:example-org/mobile.git");
    app.setConfigJson(appConfiguration());services.saveAndFlush(app);
    version=versions.saveAndFlush(new VersionBatch(LocalDate.of(2026,10,20),BRANCH,"admin"));bind(backend);bind(frontend);bind(app);
  }
  @AfterEach void close(){updates.stop();management.shutdown();}

  @Test void rawSignatureFailsClosedAndOrganizationPingDoesNotCreateBusinessObservation()throws Exception{
    byte[] payload="{\"organization\":{\"login\":\"example-org\"},\"zen\":\"你好\"}".getBytes(StandardCharsets.UTF_8);String id=UUID.randomUUID().toString();
    assertThat(updates.receive(id,"ping",sign(payload),payload).status()).isEqualTo("PING");
    assertThat(updates.receive(id,"ping",sign(payload),payload).status()).isEqualTo("DUPLICATE");
    assertThat(deliveries.count()).isEqualTo(1);assertThat(observations.count()).isZero();verifyNoInteractions(git);
    assertThatThrownBy(()->updates.receive(UUID.randomUUID().toString(),"ping","sha256="+"0".repeat(64),payload)).isInstanceOf(GitHubWebhookException.class);
    byte[] changed=Arrays.copyOf(payload,payload.length+1);changed[changed.length-1]=' ';
    assertThatThrownBy(()->updates.receive(UUID.randomUUID().toString(),"ping",sign(payload),changed)).hasMessageContaining("签名");
    assertThatThrownBy(()->updates.receive(id,"ping",sign(changed),changed)).hasMessageContaining("内容不一致");
    for(String secret:List.of("","short"," ".repeat(64))){var disabled=newService(true,secret);try{assertThatThrownBy(()->disabled.receive(UUID.randomUUID().toString(),"ping",sign(payload),payload)).hasMessageContaining("未配置");disabled.reconcile();assertThat(disabled.updates(version.getId(),"admin").items()).isEmpty();}finally{disabled.stop();}}
    assertThat(observations.count()).isZero();verifyNoInteractions(git);
  }
  @Test void duplicatePrAndPushCreateOnlyOneObservationAndUseVerifiedHeadForOutOfOrderEvents()throws Exception{
    ObjectNode push=push(FULL,BRANCH,NEXT);String id=UUID.randomUUID().toString();send(id,"push",push);send(id,"push",push);send("pull_request",merged(FULL,BRANCH,NEXT));
    assertThat(deliveries.count()).isEqualTo(2);assertThat(observations.count()).isEqualTo(1);assertThat(item(backend).status()).isEqualTo("UNKNOWN");verifyNoInteractions(git);
    when(git.head(eq(REPO),eq(BRANCH))).thenReturn(NEXT);updates.reconcile();
    assertThat(item(backend).headSha()).isEqualTo(NEXT);assertThat(item(backend).status()).isEqualTo("PENDING_BUILD");assertThat(item(backend).eventType()).isEqualTo("MERGED");assertThat(item(backend).eventUrl()).isEqualTo("https://github.com/"+FULL+"/pull/7");
    send("push",push(FULL,BRANCH,SHA));updates.reconcile();
    assertThat(item(backend).headSha()).isEqualTo(NEXT);assertThat(item(backend).eventType()).isEqualTo("MERGED");
    verify(git,times(2)).head(REPO,BRANCH);assertThat(observations.count()).isEqualTo(2);assertThat(tasks.count()).isZero();
  }
  @Test void eventDuringNetworkReadCannotCommitAnOutdatedObservation()throws Exception{
    send("push",push(FULL,BRANCH,SHA));
    when(git.head(eq(REPO),eq(BRANCH))).thenAnswer(call->{send("push",push(FULL,BRANCH,NEXT));return SHA;});updates.reconcile();
    assertThat(item(backend).status()).isEqualTo("UNKNOWN");assertThat(item(backend).headSha()).isNull();
    when(git.head(eq(REPO),eq(BRANCH))).thenReturn(NEXT);updates.reconcile();assertThat(item(backend).headSha()).isEqualTo(NEXT);
  }
  @Test void unknownRepositoriesTagsUnmanagedBranchesAndNonMergedPullRequestsAreIgnored()throws Exception{
    assertThat(send("push",push("example-org/unknown",BRANCH,SHA)).status()).isEqualTo("IGNORED");
    ObjectNode tag=push(FULL,BRANCH,SHA);tag.put("ref","refs/tags/release20261020");assertThat(send("push",tag).status()).isEqualTo("IGNORED");
    assertThat(send("push",push(FULL,"release20261021",SHA)).status()).isEqualTo("RECORDED");
    ObjectNode open=merged(FULL,BRANCH,SHA);open.put("action","opened");assertThat(send("pull_request",open).status()).isEqualTo("IGNORED");
    ObjectNode closed=merged(FULL,BRANCH,SHA);((ObjectNode)closed.path("pull_request")).put("merged",false);assertThat(send("pull_request",closed).status()).isEqualTo("IGNORED");
    ObjectNode wrong=push(FULL,BRANCH,SHA);((ObjectNode)wrong.path("repository").path("owner")).put("login","attacker");assertThatThrownBy(()->send("push",wrong)).hasMessageContaining("组织");
    assertThat(deliveries.count()).isEqualTo(1);assertThat(observations.count()).isZero();verifyNoInteractions(git);
    ObjectNode trusted=push(FULL,BRANCH,SHA);((ObjectNode)trusted.path("repository")).put("clone_url","http://127.0.0.1:1234/steal");send("push",trusted);updates.reconcile();verify(git).head(REPO,BRANCH);verify(git,never()).head(contains("127.0.0.1"),anyString());
  }
  @Test void errorsKeepLastObservationDeletionIsVerifiedAndChangedServiceDoesNotReuseOldState()throws Exception{
    updates.reconcile();assertThat(item(backend).headSha()).isEqualTo(SHA);
    when(git.head(eq(REPO),eq(BRANCH))).thenThrow(new java.io.IOException("token=do-not-display"));send("push",push(FULL,BRANCH,NEXT));updates.reconcile();
    assertThat(item(backend).status()).isEqualTo("SYNC_ERROR");assertThat(item(backend).headSha()).isEqualTo(SHA);assertThat(item(backend).message()).doesNotContain("token");
    when(git.head(eq(REPO),eq(BRANCH))).thenReturn(null);ObjectNode deleted=push(FULL,BRANCH,"0".repeat(40));deleted.put("deleted",true);send("push",deleted);updates.reconcile();
    assertThat(item(backend).status()).isEqualTo("BRANCH_DELETED");
    backend.setGitRepo("git@github.com:example-org/changed.git");services.saveAndFlush(backend);assertThat(item(backend).status()).isEqualTo("SERVICE_CHANGED");
  }
  @Test void buildStatusesRequireExactServiceBranchHeadAndActualSuccessfulArtifact()throws Exception{
    updates.reconcile();assertThat(item(backend).status()).isEqualTo("NO_UPDATES");assertThat(item(backend).buildStatus()).isEqualTo("PENDING_BUILD");
    ReleaseTask build=build(backend,"build",SHA,TaskStatus.RUNNING,false);assertThat(item(backend).status()).isEqualTo("BUILDING");
    build.setStatus(TaskStatus.FAILED);tasks.saveAndFlush(build);assertThat(item(backend).status()).isEqualTo("BUILD_FAILED");
    build.setStatus(TaskStatus.SUCCESS);tasks.saveAndFlush(build);assertThat(item(backend).status()).isEqualTo("NO_UPDATES");
    Artifact good=artifact(backend,build,SHA,BRANCH);assertThat(item(backend).status()).isEqualTo("BUILT");assertThat(item(frontend).status()).isEqualTo("NO_UPDATES");
    assertThat(item(backend).buildTaskId()).isEqualTo(build.getId());
    assertThat(item(backend).artifactId()).isEqualTo(good.getId());
    assertThat(item(frontend).artifactId()).isNull();
    build(backend,"build",SHA,TaskStatus.FAILED,false);assertThat(item(backend).status()).isEqualTo("BUILT");
    artifacts.delete(good);ReleaseTask otherCommit=build(backend,"build",NEXT,TaskStatus.SUCCESS,false);artifact(backend,otherCommit,NEXT,BRANCH);
    ReleaseTask simulated=build(frontend,"build",SHA,TaskStatus.SUCCESS,true);artifact(frontend,simulated,SHA,BRANCH);assertThat(item(frontend).status()).isEqualTo("NO_UPDATES");
    ReleaseTask wrongBranch=new ReleaseTask(frontend.getId(),TaskKind.BUILD,null,"build","master",null,false);wrongBranch.setCommitId(SHA);wrongBranch.setStatus(TaskStatus.SUCCESS);wrongBranch=tasks.saveAndFlush(wrongBranch);artifact(frontend,wrongBranch,SHA,"master");assertThat(item(frontend).status()).isEqualTo("NO_UPDATES");
    assertThat(item(backend).headSha()).isEqualTo(SHA);assertThat(item(backend).status()).isEqualTo("BUILD_FAILED");
  }
  @Test void appEnvironmentsAreIsolatedAndMustHaveMatchingSignedMobileMetadata()throws Exception{
    updates.reconcile();var initial=item(app);assertThat(initial.environments()).extracting(GitHubCodeUpdateService.Environment::environmentName).containsExactly("test","prod");
    ReleaseTask pre=build(app,"test",SHA,TaskStatus.SUCCESS,false);Artifact archive=artifact(app,pre,SHA,BRANCH);
    assertThat(environment("test").status()).isEqualTo("PENDING_BUILD");
    var config=FlutterBuildConfiguration.fromTask(pre);
    MobileArtifact metadata=new MobileArtifact(archive.getId(),pre.getId(),app.getId(),config.applicationId(),"1.0",1,"f".repeat(64),1024,config.expectedCertificateSha256(),SHA,"3.47.3","synthetic-only.apk",null,"lib/main.dart","APK");metadata.setBuildEnvironmentName("test");metadata.setBuildMode("DEBUG");metadata=mobile.saveAndFlush(metadata);
    assertThat(environment("test").status()).isEqualTo("BUILT");assertThat(environment("prod").status()).isEqualTo("PENDING_BUILD");
    ReleaseTask prod=build(app,"prod",SHA,TaskStatus.RUNNING,false);assertThat(environment("prod").status()).isEqualTo("BUILDING");
    prod.setStatus(TaskStatus.FAILED);tasks.saveAndFlush(prod);assertThat(environment("prod").status()).isEqualTo("BUILD_FAILED");assertThat(environment("test").status()).isEqualTo("BUILT");
    metadata.setBuildEnvironmentName("prod");mobile.saveAndFlush(metadata);assertThat(environment("test").status()).isEqualTo("PENDING_BUILD");
  }
  @Test void permissionFilteringIsAppliedBeforeReturningCodeOrAppStatus()throws Exception{
    updates.reconcile();assertThat(updates.updates(version.getId(),"web").items()).extracting(GitHubCodeUpdateService.Item::serviceId).containsExactly(backend.getId(),frontend.getId());
    assertThat(updates.updates(version.getId(),"mobile").items()).extracting(GitHubCodeUpdateService.Item::serviceId).containsExactly(app.getId());
    assertThatThrownBy(()->updates.updates(version.getId(),"no-version")).isInstanceOf(AccessDeniedException.class);
    var disabled=newService(false,SECRET);try{assertThat(disabled.updates(version.getId(),"web").items()).isEmpty();assertThatThrownBy(()->disabled.updates(version.getId(),"no-version")).isInstanceOf(AccessDeniedException.class);}finally{disabled.stop();}
  }
  @Test void durablePendingSurvivesRecreationAndIdleCompensationDoesNotTouchExistingRecords()throws Exception{
    send("push",push(FULL,BRANCH,NEXT));when(git.head(eq(REPO),eq(BRANCH))).thenReturn(NEXT);updates.stop();updates=newService(true,SECRET);updates.reconcile();
    assertThat(item(backend).status()).isEqualTo("PENDING_BUILD");clearInvocations(git);updates.reconcile();verifyNoInteractions(git);
    updates.clock=Clock.offset(updates.clock,Duration.ofMinutes(6));updates.reconcile();verify(git).head(REPO,BRANCH);
    assertThat(tasks.count()).isZero();assertThat(artifacts.count()).isZero();assertThat(versions.count()).isEqualTo(1);assertThat(members.count()).isEqualTo(3);assertThat(repositories.findByVersionId(version.getId())).allSatisfy(r->assertThat(r.getStatus()).isEqualTo("SUCCESS"));
    assertThat(item(backend).baselineSha()).isEqualTo(SHA);
  }
  @Test void initialHeadIsQuietRepeatedHeadIsQuietAndFirstPushNeedsBuild()throws Exception{
    updates.reconcile();assertThat(item(backend).status()).isEqualTo("NO_UPDATES");assertThat(item(backend).baselineSha()).isEqualTo(SHA);assertThat(item(backend).buildStatus()).isEqualTo("PENDING_BUILD");
    send("push",push(FULL,BRANCH,SHA));updates.reconcile();assertThat(item(backend).status()).isEqualTo("NO_UPDATES");
    when(git.head(eq(REPO),eq(BRANCH))).thenReturn(NEXT);send("push",push(FULL,BRANCH,NEXT));updates.reconcile();
    assertThat(item(backend).status()).isEqualTo("PENDING_BUILD");assertThat(item(backend).headSha()).isEqualTo(NEXT);assertThat(item(backend).baselineSha()).isEqualTo(SHA);
    send("push",push(FULL,BRANCH,NEXT));updates.reconcile();assertThat(item(backend).status()).isEqualTo("PENDING_BUILD");assertThat(tasks.count()).isZero();
  }
  @Test void staleBuildCannotAcknowledgeNewerHeadButExactBuildDoesAndSurvivesRestart()throws Exception{
    when(git.head(eq(REPO),eq(BRANCH))).thenReturn(NEXT);updates.reconcile();
    ReleaseTask older=build(backend,"build",NEXT,TaskStatus.RUNNING,false);assertThat(item(backend).status()).isEqualTo("BUILDING");
    String newest="c".repeat(40);when(git.head(eq(REPO),eq(BRANCH))).thenReturn(newest);send("push",push(FULL,BRANCH,newest));updates.reconcile();
    older.setStatus(TaskStatus.SUCCESS);tasks.saveAndFlush(older);artifact(backend,older,NEXT,BRANCH);
    assertThat(item(backend).status()).isEqualTo("PENDING_BUILD");assertThat(item(backend).baselineSha()).isEqualTo(SHA);
    ReleaseTask exact=build(backend,"build",newest,TaskStatus.SUCCESS,false);artifact(backend,exact,newest,BRANCH);
    assertThat(item(backend).status()).isEqualTo("BUILT");assertThat(item(backend).baselineSha()).isEqualTo(newest);assertThat(item(frontend).status()).isEqualTo("PENDING_BUILD");
    updates.stop();updates=newService(true,SECRET);updates.reconcile();assertThat(item(backend).baselineSha()).isEqualTo(newest);assertThat(item(backend).status()).isEqualTo("BUILT");
    when(git.head(eq(REPO),eq(BRANCH))).thenReturn(SHA);send("push",push(FULL,BRANCH,SHA));updates.reconcile();assertThat(item(backend).status()).isEqualTo("PENDING_BUILD");
  }
  @Test void oldRowsInitializeOnlyFromFreshRemoteHeadAndNeverResetExistingMemberBaselines()throws Exception{
    updates.reconcile();VersionMember before=members.findByVersionIdAndServiceId(version.getId(),backend.getId()).orElseThrow();
    jdbc.update("update version_member set code_watch_baseline_sha=null where id=?",before.getId());
    assertThat(item(backend).status()).isEqualTo("UNKNOWN");
    var disabled=newService(false,SECRET);try{disabled.reconcile();assertThat(members.findById(before.getId()).orElseThrow().getCodeWatchBaselineSha()).isNull();}finally{disabled.stop();}
    when(git.head(eq(REPO),eq(BRANCH))).thenReturn(NEXT);updates.reconcile();
    assertThat(item(backend).status()).isEqualTo("NO_UPDATES");assertThat(item(backend).baselineSha()).isEqualTo(NEXT);assertThat(item(frontend).status()).isEqualTo("PENDING_BUILD");assertThat(item(frontend).baselineSha()).isEqualTo(SHA);
    assertThat(members.findById(before.getId()).orElseThrow().getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    updates.stop();updates=newService(true,SECRET);updates.clock=Clock.offset(updates.clock,Duration.ofMinutes(6));updates.reconcile();assertThat(item(frontend).status()).isEqualTo("PENDING_BUILD");
    String later="c".repeat(40);when(git.head(eq(REPO),eq(BRANCH))).thenReturn(later);send("push",push(FULL,BRANCH,later));updates.reconcile();assertThat(item(backend).status()).isEqualTo("PENDING_BUILD");assertThat(item(backend).baselineSha()).isEqualTo(NEXT);
  }
  @Test void appNewCommitRemainsPendingUntilEveryConfiguredEnvironmentHasAnExactArtifact()throws Exception{
    updates.reconcile();assertThat(item(app).status()).isEqualTo("NO_UPDATES");assertThat(item(app).buildStatus()).isEqualTo("PENDING_BUILD");
    when(git.head(eq(app.getGitRepo()),eq(BRANCH))).thenReturn(NEXT);send("push",push("example-org/mobile",BRANCH,NEXT));updates.reconcile();
    signedArtifact(build(app,"test",NEXT,TaskStatus.SUCCESS,false));
    assertThat(environment("test").status()).isEqualTo("BUILT");assertThat(environment("prod").status()).isEqualTo("PENDING_BUILD");assertThat(item(app).status()).isEqualTo("PENDING_BUILD");assertThat(item(app).baselineSha()).isEqualTo(SHA);
    signedArtifact(build(app,"prod",SHA,TaskStatus.SUCCESS,false));assertThat(item(app).status()).isEqualTo("PENDING_BUILD");
    signedArtifact(build(app,"prod",NEXT,TaskStatus.SUCCESS,false));assertThat(item(app).status()).isEqualTo("BUILT");assertThat(item(app).baselineSha()).isEqualTo(NEXT);
  }
  @Test void lateSharedRepositoryMemberIgnoresStaleObservationAndKeepsExistingMemberReminder()throws Exception{
    when(git.head(eq(REPO),eq(BRANCH))).thenReturn(NEXT);updates.reconcile();assertThat(item(backend).status()).isEqualTo("PENDING_BUILD");
    AppService added=service("late-service",ServiceType.BACKEND,REPO);Long repositoryId=members.findByVersionIdAndServiceId(version.getId(),backend.getId()).orElseThrow().getRepositoryId();
    VersionMember member=members.saveAndFlush(new VersionMember(version.getId(),repositoryId,added));Instant registered=updates.clock.instant().plusSeconds(1);String registeredHead="c".repeat(40);
    members.initializeCodeWatchBaseline(member.getId(),registeredHead,registered);
    assertThat(item(added).status()).isEqualTo("UNKNOWN");assertThat(item(backend).status()).isEqualTo("PENDING_BUILD");
    when(git.head(eq(REPO),eq(BRANCH))).thenReturn(registeredHead);updates.clock=Clock.fixed(registered.plusSeconds(1),ZoneOffset.UTC);updates.reconcile();
    assertThat(item(added).status()).isEqualTo("NO_UPDATES");assertThat(item(backend).status()).isEqualTo("PENDING_BUILD");assertThat(item(backend).baselineSha()).isEqualTo(SHA);
    String pushed="d".repeat(40);when(git.head(eq(REPO),eq(BRANCH))).thenReturn(pushed);send("push",push(FULL,BRANCH,pushed));updates.reconcile();assertThat(item(added).status()).isEqualTo("PENDING_BUILD");assertThat(item(added).baselineSha()).isEqualTo(registeredHead);
  }
  @Test void existingPendingRowsAreReconciledOnFreshWorkerWithoutAnyOuterTransaction()throws Exception{
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    observations.saveAndFlush(new GitHubBranchObservation(PolicyGitRepository.identity(REPO),BRANCH));
    observations.saveAndFlush(new GitHubBranchObservation(PolicyGitRepository.identity(app.getGitRepo()),BRANCH));
    long caller=Thread.currentThread().threadId();
    when(git.head(anyString(),eq(BRANCH))).thenAnswer(call->{
      assertThat(Thread.currentThread().threadId()).isNotEqualTo(caller);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return NEXT;
    });
    try(ExecutorService executor=Executors.newSingleThreadExecutor()){
      executor.submit(()->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();updates.reconcile();assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}).get(15,TimeUnit.SECONDS);
    }
    assertThat(observations.findAll()).hasSize(2).allSatisfy(o->{assertThat(o.getSyncStatus()).isEqualTo("SYNCED");assertThat(o.getRequestedRevision()).isEqualTo(o.getCheckedRevision());assertThat(o.getCheckedAt()).isNotNull();assertThat(o.getHeadSha()).isEqualTo(NEXT);});
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }
  @Test void bellReceivesOrdinaryBranchesWithoutAnyVersionAndDeduplicatesDeliveryNotPr()throws Exception{
    members.deleteAll();repositories.deleteAll();versions.deleteAll();
    ObjectNode payload=push(FULL,"developer/fix-catalog",SHA);String id=UUID.randomUUID().toString();
    assertThat(send(id,"push",payload).status()).isEqualTo("RECORDED");
    assertThat(send(id,"push",payload).status()).isEqualTo("DUPLICATE");
    assertThat(send("pull_request",merged(FULL,"developer/fix-catalog",SHA)).status()).isEqualTo("IGNORED");
    assertThat(pushEvents.count()).isEqualTo(1);assertThat(observations.count()).isZero();assertThat(tasks.count()).isZero();
    var feed=eventFeed.feed("no-version");assertThat(feed.enabled()).isTrue();assertThat(feed.unread()).isTrue();
    assertThat(feed.items()).singleElement().satisfies(e->{
      assertThat(e.branch()).isEqualTo("developer/fix-catalog");assertThat(e.commitId()).isEqualTo(SHA);
      assertThat(e.services()).extracting(GitHubEventService.EventService::id).containsExactly(backend.getId(),frontend.getId());
      assertThat(e.services()).allMatch(GitHubEventService.EventService::canBuild);
      assertThat(e.eventUrl()).isEqualTo("https://github.com/"+FULL+"/commit/"+SHA);
    });
    verifyNoInteractions(git);
  }
  @Test void bellUsesReceiptTimeAndStableIdAndFiltersPermissionsBeforeTakingFive()throws Exception{
    Instant base=Instant.parse("2026-10-17T12:00:00Z");
    for(int index=0;index<7;index++){
      updates.clock=Clock.fixed(base.plusSeconds(index),ZoneOffset.UTC);ObjectNode payload=push(FULL,"feature/"+index,SHA);
      ((ObjectNode)payload.path("head_commit")).put("timestamp","2099-01-01T00:00:00Z");send("push",payload);
    }
    for(int index=0;index<8;index++){updates.clock=Clock.fixed(base.plusSeconds(20+index),ZoneOffset.UTC);send("push",push("example-org/mobile","app/"+index,SHA));}
    var web=eventFeed.feed("no-version");assertThat(web.items()).extracting(GitHubEventService.Item::branch).containsExactly("feature/6","feature/5","feature/4","feature/3","feature/2");
    assertThat(web.items().getFirst().receivedAt()).isEqualTo(base.plusSeconds(6));
    assertThat(eventFeed.feed("mobile").items()).extracting(GitHubEventService.Item::branch).containsExactly("app/7","app/6","app/5","app/4","app/3");
    updates.clock=Clock.fixed(base.plusSeconds(50),ZoneOffset.UTC);send("push",push(FULL,"same-time/first",SHA));send("push",push(FULL,"same-time/second",SHA));
    assertThat(eventFeed.feed("web").items().getFirst().branch()).isEqualTo("same-time/second");
  }
  @Test void bellFiltersSharedRepositoryServicesAndBuildActionsAndHandlesDisabledSettings()throws Exception{
    app.setGitRepo(REPO);services.saveAndFlush(app);user("viewer",List.of("services"));user("dashboard-only",List.of("dashboard"));
    send("push",push(FULL,"feature/shared",SHA));
    assertThat(eventFeed.feed("web").items().getFirst().services()).extracting(GitHubEventService.EventService::id).containsExactly(backend.getId(),frontend.getId());
    assertThat(eventFeed.feed("mobile").items().getFirst().services()).extracting(GitHubEventService.EventService::id).containsExactly(app.getId());
    assertThat(eventFeed.feed("viewer").items().getFirst().services()).noneMatch(GitHubEventService.EventService::canBuild);
    backend.setEnabled(false);services.saveAndFlush(backend);
    assertThat(eventFeed.feed("admin").items().getFirst().services().getFirst().canBuild()).isFalse();
    assertThat(eventFeed.feed("dashboard-only").items()).isEmpty();
    assertThatThrownBy(()->eventFeed.feed("unknown")).isInstanceOf(AccessDeniedException.class);
    var disabled=new GitHubEventService(new GitHubWebhookSettings(false,SECRET,"example-org"),pushEvents,eventReads,services,users,merges,manager);
    assertThat(disabled.feed("admin")).isEqualTo(new GitHubEventService.Feed(false,false,null,List.of()));
  }
  @Test void bellReadStateIsUserSpecificIdempotentAndCannotMarkFutureOrHiddenEvents()throws Exception{
    send("push",push(FULL,"master",SHA));
    eventFeed.clock=updates.clock; // Master is deliberately held until its origin is classified.
    send("push",push(FULL,"feature/read",NEXT));var shown=eventFeed.feed("web");assertThat(shown.items()).hasSize(1);
    List<Long> shownIds=shown.items().stream().map(GitHubEventService.Item::id).toList();
    eventFeed.markRead("web",Long.MAX_VALUE,shownIds);eventFeed.markRead("web",shown.latestEventId(),shownIds);
    assertThat(eventReads.count()).isEqualTo(1);assertThat(eventFeed.feed("web").unread()).isFalse();assertThat(eventFeed.feed("no-version").unread()).isTrue();
    eventFeed.clock=Clock.offset(updates.clock,Duration.ofSeconds(16));
    assertThat(eventFeed.feed("web").items()).filteredOn(e->e.branch().equals("master")).singleElement().matches(GitHubEventService.Item::unread);
    send("push",push(FULL,"feature/next",SHA));assertThat(eventFeed.feed("web").items().getFirst().unread()).isTrue();
    eventFeed.markRead("web",shown.latestEventId(),shownIds);assertThat(eventFeed.feed("web").items().getFirst().unread()).isTrue();
    assertThatThrownBy(()->eventFeed.markRead("web",0L,List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->eventFeed.markRead("web",1L,List.of(2L))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void bellNeverRecordsTagsDeletedRefsInvalidBranchesOrPrDuplicates()throws Exception{
    ObjectNode deleted=push(FULL,"feature/deleted","0".repeat(40));deleted.put("deleted",true);send("push",deleted);
    ObjectNode tag=push(FULL,"tag",SHA);tag.put("ref","refs/tags/v1");send("push",tag);
    for(String branch:List.of("a".repeat(256),"x\ncommand","../../master","feature/.secret","feature/x.lock","a b","a\\b"))send("push",push(FULL,branch,SHA));
    assertThat(pushEvents.count()).isZero();
    ObjectNode push=push(FULL,BRANCH,SHA);push.put("compare","https://attacker.invalid/");((ObjectNode)push.path("head_commit")).put("message","x\n"+"y".repeat(400));
    send("push",push);send("pull_request",merged(FULL,BRANCH,SHA));assertThat(pushEvents.count()).isEqualTo(1);
    var item=eventFeed.feed("admin").items().getFirst();assertThat(item.summary()).hasSize(300).doesNotContain("\n");assertThat(item.eventUrl()).startsWith("https://github.com/");
  }
  @Test void bellHoldsRunningProductionMergeThenSuppressesExactProofButReleasesHumanPush()throws Exception{
    ReleaseTask deploy=new ReleaseTask(backend.getId(),TaskKind.DEPLOY,null,"prod",BRANCH,null,false);deploy.setCommitId(NEXT);deploy.setStatus(TaskStatus.SUCCESS);deploy=tasks.saveAndFlush(deploy);
    BranchCheck proof=new BranchCheck(100L,backend.getId(),NEXT);proof.complete(SHA,BranchCheck.Status.PASSED,"synthetic","encrypted",PolicyGitRepository.identity(REPO));
    ProductionMerge merge=new ProductionMerge(deploy.getId(),backend.getId(),NEXT);merge.proof(proof);merge.start();merge=merges.saveAndFlush(merge);
    ObjectNode platform=push(FULL,"master",NEXT);platform.put("before",SHA);send("push",platform);
    ObjectNode human=push(FULL,"master","c".repeat(40));human.put("before",NEXT);send("push",human);
    assertThat(eventFeed.feed("web").items()).isEmpty();
    // The human push can advance master before the platform's post-push verification fetch.
    // Suppression matches the actual pushed build Commit, never the later observed baselineAfter.
    merge.baselines(SHA,"c".repeat(40));merge.update(ProductionMerge.Status.MERGED,"MERGED","done");merges.saveAndFlush(merge);
    assertThat(eventFeed.feed("web").items()).singleElement().satisfies(e->assertThat(e.commitId()).isEqualTo("c".repeat(40)));
    deploy.setStatus(TaskStatus.FAILED);tasks.saveAndFlush(deploy);assertThat(eventFeed.feed("web").items()).hasSize(2); // No matching successful release means no silent suppression.
  }

  @Test void bellHoldsRecoveredAttemptedMergeButNotFreshQueuedMergeOrOrdinaryBranches()throws Exception{
    ReleaseTask deploy=new ReleaseTask(backend.getId(),TaskKind.DEPLOY,null,"prod",BRANCH,null,false);deploy.setCommitId(NEXT);deploy.setStatus(TaskStatus.SUCCESS);deploy=tasks.saveAndFlush(deploy);
    BranchCheck proof=new BranchCheck(100L,backend.getId(),NEXT);proof.complete(SHA,BranchCheck.Status.PASSED,"synthetic","encrypted",PolicyGitRepository.identity(REPO));
    ProductionMerge merge=new ProductionMerge(deploy.getId(),backend.getId(),NEXT);merge.proof(proof);merge=merges.saveAndFlush(merge);
    ObjectNode master=push(FULL,"master",NEXT);master.put("before",SHA);send("push",master);
    assertThat(merge.getAttempts()).isZero();assertThat(eventFeed.feed("web").items()).hasSize(1);
    merge.start();merge.update(ProductionMerge.Status.QUEUED,"RECOVERED","restart recovery");merge=merges.saveAndFlush(merge);
    assertThat(merge.getAttempts()).isEqualTo(1);assertThat(eventFeed.feed("web").items()).isEmpty();
    send("push",push(FULL,"feature/during-recovery",SHA));
    assertThat(eventFeed.feed("web").items()).extracting(GitHubEventService.Item::branch).containsExactly("feature/during-recovery");
    merge.baselines(SHA,NEXT);merge.update(ProductionMerge.Status.MERGED,"MERGED","recovered and verified");merges.saveAndFlush(merge);
    assertThat(eventFeed.feed("web").items()).extracting(GitHubEventService.Item::branch).containsExactly("feature/during-recovery");
  }

  @Test void productionMergeIsSilentlyRecognizedOnlyByExactProofAndCanBeMatchedAfterCallback()throws Exception{
    ObjectNode master=push(FULL,"master",NEXT);master.put("before",SHA);String id=UUID.randomUUID().toString();assertThat(send(id,"push",master).status()).isEqualTo("SILENT");
    assertThat(observations.count()).isZero();assertThat(deliveries.findByDeliveryId(id).orElseThrow().getStatus()).isEqualTo("MASTER_CHECK");
    ReleaseTask deploy=new ReleaseTask(backend.getId(),TaskKind.DEPLOY,null,"prod",BRANCH,null,false);deploy.setCommitId(NEXT);deploy.setStatus(TaskStatus.SUCCESS);deploy=tasks.saveAndFlush(deploy);
    BranchCheck proof=new BranchCheck(100L,backend.getId(),NEXT);proof.complete(SHA,BranchCheck.Status.PASSED,"synthetic","encrypted",PolicyGitRepository.identity(REPO));
    ProductionMerge merge=new ProductionMerge(deploy.getId(),backend.getId(),NEXT);merge.proof(proof);merge.baselines(SHA,NEXT);merge.update(ProductionMerge.Status.MERGED,"MERGED","done");merges.saveAndFlush(merge);
    updates.reconcile();GitHubWebhookDelivery stored=deliveries.findByDeliveryId(id).orElseThrow();assertThat(stored.getStatus()).isEqualTo("PLATFORM_MERGE");assertThat(stored.getPlatformReleaseTaskId()).isEqualTo(deploy.getId());
    assertThat(observations.findAll()).allSatisfy(o->assertThat(o.getBranch()).isEqualTo(BRANCH));
    ObjectNode manual=push(FULL,"master",NEXT);manual.put("before","c".repeat(40));String other=UUID.randomUUID().toString();send(other,"push",manual);updates.reconcile();assertThat(deliveries.findByDeliveryId(other).orElseThrow().getStatus()).isEqualTo("MASTER_CHECK");
  }

  private GitHubCodeUpdateService newService(boolean enabled,String secret){var value=new GitHubCodeUpdateService(new GitHubWebhookSettings(enabled,secret,"example-org"),deliveries,observations,services,versions,repositories,members,management,tasks,artifacts,mobile,merges,git,json,manager,eventFeed);value.clock=Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"),ZoneOffset.UTC);return value;}
  private GitHubCodeUpdateService.Item item(AppService s){return updates.updates(version.getId(),"admin").items().stream().filter(i->i.serviceId().equals(s.getId())).findFirst().orElseThrow();}
  private GitHubCodeUpdateService.Environment environment(String name){return item(app).environments().stream().filter(e->e.environmentName().equals(name)).findFirst().orElseThrow();}
  private AppService service(String name,ServiceType type,String repo){AppService s=new AppService();s.setName(name);s.setServiceKey(name);s.setServiceType(type);s.setGitRepo(repo);return services.saveAndFlush(s);}
  private void user(String username,List<String> permissions){AdminUser user=new AdminUser(username,"unused");user.setMenuPermissions(permissions);users.saveAndFlush(user);}
  private void bind(AppService s){String identity=PolicyGitRepository.identity(s.getGitRepo());VersionRepository repository=repositories.findByVersionIdAndRepositoryIdentity(version.getId(),identity).orElseGet(()->{VersionRepository r=new VersionRepository(version.getId(),identity,"unused","admin");r.prepare("unused",SHA,"master","admin");r.result("SUCCESS",null,"created");return repositories.saveAndFlush(r);});VersionMember member=members.saveAndFlush(new VersionMember(version.getId(),repository.getId(),s));members.initializeCodeWatchBaseline(member.getId(),SHA,updates.clock.instant());}
  private ReleaseTask build(AppService s,String env,String sha,TaskStatus status,boolean simulate){ReleaseTask task=new ReleaseTask(s.getId(),TaskKind.BUILD,null,env,BRANCH,null,simulate);task.setCommitId(sha);task.setStatus(status);if(s.getServiceType()==ServiceType.FLUTTER_ANDROID){task.setDeliveryType("MOBILE");task.setMobileBuildConfigSnapshot(FlutterBuildConfiguration.snapshot(s,env));}return tasks.saveAndFlush(task);}
  private Artifact artifact(AppService s,ReleaseTask task,String sha,String branch){return artifacts.saveAndFlush(new Artifact(s.getId(),task.getId(),"v1",branch,sha,s.getServiceType()==ServiceType.FLUTTER_ANDROID?null:"registry.example/service:verified"));}
  private void signedArtifact(ReleaseTask task){var config=FlutterBuildConfiguration.fromTask(task);Artifact archive=artifact(app,task,task.getCommitId(),BRANCH);MobileArtifact metadata=new MobileArtifact(archive.getId(),task.getId(),app.getId(),config.applicationId(),"1.0",1,"f".repeat(64),1024,config.expectedCertificateSha256(),task.getCommitId(),"3.47.3","synthetic-only.apk",null,"lib/main.dart","APK");metadata.setBuildEnvironmentName(task.getEnvironmentName());metadata.setBuildMode(config.buildMode());mobile.saveAndFlush(metadata);}
  private String appConfiguration(){ObjectNode root=json.createObjectNode(),common=root.putObject("mobileBuild"),envs=common.putObject("environments");common.put("flutterVersion","3.47.3");common.put("outputFormat","APK");common.put("target","lib/main.dart");for(String env:List.of("test","prod")){ObjectNode profile=envs.putObject(env);profile.put("enabled",true);profile.put("applicationId","com.example.app"+(env.equals("test")?".pre":""));profile.put("expectedCertificateSha256",(env.equals("test")?"ab":"cd").repeat(32));profile.put("signingProfile","profile-"+env);profile.put("buildMode",env.equals("test")?"DEBUG":"RELEASE");profile.put("allowDebugSigning",env.equals("test"));}return root.toString();}
  private ObjectNode repository(ObjectNode root,String full){ObjectNode repo=root.putObject("repository");repo.put("full_name",full);repo.putObject("owner").put("login","example-org");root.putObject("sender").put("login","developer");return root;}
  private ObjectNode push(String full,String branch,String sha){ObjectNode root=repository(json.createObjectNode(),full);root.put("ref","refs/heads/"+branch);root.put("after",sha);root.put("before","0".repeat(40));root.put("deleted",false);root.putObject("head_commit").put("message","changed code").put("timestamp","2026-10-17T12:00:00Z");return root;}
  private ObjectNode merged(String full,String branch,String sha){ObjectNode root=repository(json.createObjectNode(),full);root.put("action","closed");ObjectNode pr=root.putObject("pull_request");pr.put("merged",true);pr.put("number",7);pr.put("title","合并新的代码");pr.put("merge_commit_sha",sha);pr.put("merged_at","2026-10-17T12:00:00Z");pr.putObject("merged_by").put("login","reviewer");pr.putObject("base").put("ref",branch).putObject("repo").put("full_name",full);return root;}
  private GitHubCodeUpdateService.Receipt send(String event,ObjectNode payload)throws Exception{return send(UUID.randomUUID().toString(),event,payload);}
  private GitHubCodeUpdateService.Receipt send(String id,String event,ObjectNode payload)throws Exception{byte[] body=json.writeValueAsBytes(payload);return updates.receive(id,event,sign(body),body);}
  private static String sign(byte[] body)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return "sha256="+HexFormat.of().formatHex(mac.doFinal(body));}
}
