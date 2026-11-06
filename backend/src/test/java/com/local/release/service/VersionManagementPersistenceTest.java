package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.ServiceType;
import com.local.release.repo.*;
import com.local.release.security.CredentialSecretCrypto;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class VersionManagementPersistenceTest {
  @Autowired VersionBatchRepository versions;
  @Autowired VersionRepositoryRepository repositories;
  @Autowired VersionMemberRepository members;
  @Autowired VersionPreviewRepository previews;
  @Autowired AppServiceRepository services;
  @Autowired AdminUserRepository users;
  @Autowired PlatformTransactionManager manager;
  VersionGitRepository git;VersionManagementService service;CredentialSecretCrypto crypto;
  static final String SHA="a".repeat(40),REPO="git@example.invalid:team/backend.git";
  AppService backend,frontend,app;
  @BeforeEach void setup()throws Exception{
    members.deleteAll();repositories.deleteAll();previews.deleteAll();versions.deleteAll();services.deleteAll();users.deleteAll();
    git=mock(VersionGitRepository.class);crypto=mock(CredentialSecretCrypto.class);
    when(crypto.encrypt(anyString())).thenAnswer(call->Base64.getEncoder().encodeToString(call.<String>getArgument(0).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    when(crypto.decrypt(anyString())).thenAnswer(call->new String(Base64.getDecoder().decode(call.<String>getArgument(0)),java.nio.charset.StandardCharsets.UTF_8));
    service=new VersionManagementService(versions,repositories,members,previews,services,users,git,crypto,new ObjectMapper().findAndRegisterModules(),manager);
    user("admin",List.of());user("legacy",List.of("dashboard","services","builds","release"));user("web",List.of("versions","builds"));user("mobile",List.of("versions","apps"));user("version-only",List.of("versions"));user("history",List.of("versions","releases"));
    backend=app("backend",ServiceType.BACKEND,REPO);frontend=app("frontend",ServiceType.FRONTEND,"https://example.invalid/team/backend.git");app=app("mobile",ServiceType.FLUTTER_ANDROID,"git@example.invalid:team/mobile.git");
    when(git.preview(anyString(),anyString())).thenReturn(new VersionGitRepository.Snapshot(SHA,"confirmed master",null));
  }
  @AfterEach void stop(){service.shutdown();}
  @Test void sameDaySharedRepositoryAndRepeatedConfirmationAreDurableAndIdempotent()throws Exception{
    var preview=service.preview(List.of(backend.getId(),frontend.getId()),null,"admin");
    assertThat(preview.versionDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));assertThat(preview.items()).hasSize(2);
    var created=service.create(preview.previewId(),"admin");await(created.id());
    assertThat(versions.count()).isEqualTo(1);assertThat(repositories.count()).isEqualTo(1);assertThat(members.count()).isEqualTo(2);
    assertThat(members.findByVersionIdOrderByIdAsc(created.id())).allSatisfy(m->{assertThat(m.getCodeWatchBaselineSha()).isEqualTo(SHA);assertThat(m.getCodeWatchStartedAt()).isNotNull();});
    assertThat(service.create(preview.previewId(),"admin").id()).isEqualTo(created.id());verify(git,times(1)).preview(anyString(),anyString());verify(git,times(1)).create(anyString(),anyString(),eq(SHA));
    var extra=service.preview(List.of(app.getId()),null,"admin");assertThat(extra.versionId()).isEqualTo(created.id());service.create(extra.previewId(),"admin");await(created.id());
    assertThat(service.detail(created.id(),"admin").totalCount()).isEqualTo(3);assertThat(services.findById(backend.getId()).orElseThrow().getDefaultBranch()).isEqualTo("main");
  }
  @Test void sharedRepositoryCanAddAnotherServiceAfterDevelopersAdvanceTheBranch()throws Exception{
    var preview=service.preview(List.of(backend.getId()),null,"admin");var created=service.create(preview.previewId(),"admin");await(created.id());clearInvocations(git);
    when(git.preview(anyString(),anyString())).thenReturn(new VersionGitRepository.Snapshot("b".repeat(40),"new master","c".repeat(40)));
    var append=service.preview(List.of(frontend.getId()),created.id(),"admin");assertThat(append.canConfirm()).isTrue();assertThat(append.items().getFirst().status()).isEqualTo("ALREADY_CREATED");assertThat(append.items().getFirst().baselineSha()).isEqualTo(SHA);
    service.create(append.previewId(),"admin");assertThat(service.detail(created.id(),"admin").successCount()).isEqualTo(2);verify(git).preview(frontend.getGitRepo(),created.branch());verify(git,never()).create(anyString(),anyString(),anyString());
    assertThat(members.findByVersionIdAndServiceId(created.id(),backend.getId()).orElseThrow().getCodeWatchBaselineSha()).isEqualTo(SHA);
    assertThat(members.findByVersionIdAndServiceId(created.id(),frontend.getId()).orElseThrow().getCodeWatchBaselineSha()).isEqualTo("c".repeat(40));
    assertThat(repositories.findByVersionId(created.id()).getFirst().getBaselineSha()).isEqualTo(SHA);
  }
  @Test void appendingMemberCannotSwallowChangesAfterPreviewOrRegisterAnUnverifiedBranch()throws Exception{
    var preview=service.preview(List.of(backend.getId()),null,"admin");var created=service.create(preview.previewId(),"admin");await(created.id());
    var append=service.preview(List.of(frontend.getId()),created.id(),"admin");
    when(git.preview(anyString(),anyString())).thenReturn(new VersionGitRepository.Snapshot(SHA,"master",null));
    assertThatThrownBy(()->service.create(append.previewId(),"admin")).hasMessageContaining("分支不存在");assertThat(members.findByVersionIdAndServiceId(created.id(),frontend.getId())).isEmpty();
    when(git.preview(anyString(),anyString())).thenAnswer(call->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new VersionGitRepository.Snapshot(SHA,"master","d".repeat(40));});
    service.create(append.previewId(),"admin");assertThat(members.findByVersionIdAndServiceId(created.id(),frontend.getId()).orElseThrow().getCodeWatchBaselineSha()).isEqualTo("d".repeat(40));
  }
  @Test void partialFailuresCanRetryOnlyFailedRepositoryWithoutDuplicatingSuccessfulOne()throws Exception{
    doThrow(new VersionGitRepository.Failure("PUSH_REJECTED","write denied")).when(git).create(eq(app.getGitRepo()),anyString(),eq(SHA));
    var preview=service.preview(List.of(backend.getId(),app.getId()),null,"admin");Long id=service.create(preview.previewId(),"admin").id();await(id);
    var result=service.detail(id,"admin");assertThat(result.status()).isEqualTo("PARTIAL");assertThat(result.successCount()).isEqualTo(1);assertThat(result.failedCount()).isEqualTo(1);
    doNothing().when(git).create(eq(app.getGitRepo()),anyString(),eq(SHA));
    var retry=service.preview(List.of(app.getId()),id,"admin");service.create(retry.previewId(),"admin");await(id);assertThat(service.detail(id,"admin").status()).isEqualTo("SUCCESS");
    verify(git,times(1)).create(eq(REPO),anyString(),eq(SHA));verify(git,times(2)).create(eq(app.getGitRepo()),anyString(),eq(SHA));
  }
  @Test void restartMarksUncertainAttemptsWithoutClaimingExistingSameShaBranch()throws Exception{
    VersionBatch version=versions.saveAndFlush(new VersionBatch(LocalDate.now(ZoneId.of("Asia/Shanghai")),"release20261017","admin"));
    VersionRepository repository=new VersionRepository(version.getId(),PolicyGitRepository.identity(REPO),crypto.encrypt(REPO),"admin");repository.prepare(crypto.encrypt(REPO),SHA,"master","admin");repository.result("RUNNING",null,"running");repository=repositories.saveAndFlush(repository);members.saveAndFlush(new VersionMember(version.getId(),repository.getId(),backend));
    service.recover();assertThat(repositories.findById(repository.getId()).orElseThrow().getStatus()).isEqualTo("UNKNOWN");verifyNoInteractions(git);
    when(git.preview(anyString(),anyString())).thenReturn(new VersionGitRepository.Snapshot(SHA,"master",SHA));
    var retry=service.preview(List.of(backend.getId()),version.getId(),"admin");assertThat(retry.canConfirm()).isFalse();assertThat(retry.items().getFirst().status()).isEqualTo("CONFLICT");
    assertThatThrownBy(()->service.create(retry.previewId(),"admin")).hasMessageContaining("没有可创建");verify(git,never()).create(anyString(),anyString(),anyString());
  }
  @Test void ordinaryUsersCannotMutateAndAllReadAggregatesFilterBeforeCounting()throws Exception{
    var preview=service.preview(List.of(backend.getId(),app.getId()),null,"admin");Long id=service.create(preview.previewId(),"admin").id();await(id);
    for(String operator:List.of("legacy","web","mobile","version-only")){
      assertThatThrownBy(()->service.preview(List.of(backend.getId()),null,operator)).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(()->service.create(preview.previewId(),operator)).isInstanceOf(AccessDeniedException.class);
    }
    assertThatThrownBy(()->service.list("legacy")).isInstanceOf(AccessDeniedException.class);
    assertThat(service.detail(id,"web").items()).extracting(VersionManagementService.Item::serviceId).containsExactly(backend.getId());
    assertThat(service.detail(id,"mobile").items()).extracting(VersionManagementService.Item::serviceId).containsExactly(app.getId());
    assertThat(service.detail(id,"web").totalCount()).isEqualTo(1);assertThat(service.detail(id,"mobile").successCount()).isEqualTo(1);
    assertThat(service.list("version-only")).isEmpty();assertThat(service.list("history")).isEmpty();assertThat(service.latest("version-only")).isNull();
    assertThatThrownBy(()->service.detail(id,"version-only")).isInstanceOf(AccessDeniedException.class);
    String serialized=new ObjectMapper().findAndRegisterModules().writeValueAsString(service.detail(id,"web"));assertThat(serialized).doesNotContain(app.getName(),"encryptedRepository",REPO,"repositoryIdentity");
  }
  @Test void changedConfigurationOrExpiredPreviewCannotCreateAnything()throws Exception{
    var preview=service.preview(List.of(backend.getId()),null,"admin");backend.setGitRepo("git@example.invalid:other/repository.git");services.saveAndFlush(backend);
    assertThatThrownBy(()->service.create(preview.previewId(),"admin")).hasMessageContaining("配置已变化");assertThat(versions.count()).isZero();verify(git,never()).create(anyString(),anyString(),anyString());
    var fresh=service.preview(List.of(backend.getId()),null,"admin");var persisted=previews.findByToken(fresh.previewId()).orElseThrow();ReflectionTestUtils.setField(persisted,"expiresAt",Instant.now().minusSeconds(1));previews.saveAndFlush(persisted);
    assertThatThrownBy(()->service.create(fresh.previewId(),"admin")).hasMessageContaining("过期");
  }
  @Test void missingRepositoryDoesNotCreatePermanentUnrecoverablePlaceholder()throws Exception{
    app.setGitRepo(null);services.saveAndFlush(app);var preview=service.preview(List.of(backend.getId(),app.getId()),null,"admin");Long id=service.create(preview.previewId(),"admin").id();await(id);
    assertThat(service.detail(id,"admin").items()).hasSize(1);
    app.setGitRepo("git@example.invalid:team/mobile.git");services.saveAndFlush(app);var retry=service.preview(List.of(app.getId()),id,"admin");service.create(retry.previewId(),"admin");await(id);assertThat(service.detail(id,"admin").totalCount()).isEqualTo(2);
  }
  @Test void historicalVersionAllowsFailedRetryButRejectsAddingNewService()throws Exception{
    LocalDate yesterday=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);VersionBatch version=versions.saveAndFlush(new VersionBatch(yesterday,"release"+yesterday.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),"admin"));
    VersionRepository repository=new VersionRepository(version.getId(),PolicyGitRepository.identity(REPO),crypto.encrypt(REPO),"admin");repository.prepare(crypto.encrypt(REPO),SHA,"master","admin");repository.result("FAILED","READ_FAILED","read failed");repository=repositories.saveAndFlush(repository);members.saveAndFlush(new VersionMember(version.getId(),repository.getId(),backend));
    assertThat(service.detail(version.getId(),"admin").canAppend()).isFalse();assertThatThrownBy(()->service.preview(List.of(app.getId()),version.getId(),"admin")).hasMessageContaining("历史版本不能补充");
    var retry=service.preview(List.of(backend.getId()),version.getId(),"admin");service.create(retry.previewId(),"admin");await(version.getId());assertThat(service.detail(version.getId(),"admin").status()).isEqualTo("SUCCESS");
  }
  @Test void baselineChangedDuringExecutionIsPersistedAndRequiresNewPreview()throws Exception{
    doThrow(new VersionGitRepository.Failure("BASELINE_CHANGED","master changed")).when(git).create(anyString(),anyString(),eq(SHA));
    var preview=service.preview(List.of(backend.getId()),null,"admin");Long id=service.create(preview.previewId(),"admin").id();await(id);
    assertThat(service.detail(id,"admin").items().getFirst().errorCode()).isEqualTo("BASELINE_CHANGED");assertThat(service.detail(id,"admin").failedCount()).isEqualTo(1);
  }
  @Test void concurrentIndependentConfirmationsShareOneDailyVersionAndOneCreation()throws Exception{
    var first=service.preview(List.of(backend.getId()),null,"admin");var second=service.preview(List.of(backend.getId()),null,"admin");CountDownLatch start=new CountDownLatch(1);
    try(ExecutorService calls=Executors.newFixedThreadPool(2)){
      Future<VersionManagementService.View> a=calls.submit(()->{start.await();return service.create(first.previewId(),"admin");});
      Future<VersionManagementService.View> b=calls.submit(()->{start.await();return service.create(second.previewId(),"admin");});start.countDown();
      Long id=a.get(8,TimeUnit.SECONDS).id();assertThat(b.get(8,TimeUnit.SECONDS).id()).isEqualTo(id);await(id);
    }
    assertThat(versions.count()).isEqualTo(1);assertThat(repositories.count()).isEqualTo(1);assertThat(members.count()).isEqualTo(1);verify(git,times(1)).create(anyString(),anyString(),eq(SHA));
  }
  @Test void candidatesListAndLatestHonorBothCurrentAndHistoricalServiceFamily()throws Exception{
    assertThat(service.candidates("web")).extracting(VersionManagementService.Candidate::serviceId).containsExactly(backend.getId(),frontend.getId());
    assertThat(service.candidates("mobile")).extracting(VersionManagementService.Candidate::serviceId).containsExactly(app.getId());
    var preview=service.preview(List.of(backend.getId(),app.getId()),null,"admin");Long id=service.create(preview.previewId(),"admin").id();await(id);
    assertThat(service.latest("web").items()).extracting(VersionManagementService.Item::serviceId).containsExactly(backend.getId());
    assertThat(service.list("mobile").getFirst().items()).extracting(VersionManagementService.Item::serviceId).containsExactly(app.getId());
    backend.setServiceType(ServiceType.FLUTTER_ANDROID);services.saveAndFlush(backend);
    assertThat(service.latest("web")).isNull();
    assertThat(service.latest("mobile").items()).extracting(VersionManagementService.Item::serviceId).containsExactly(app.getId());
  }
  @Test void futureDateIsLockedThroughConfirmationAndIndependentPreviewsShareThatVersion()throws Exception{
    at("2026-10-17T10:00:00");LocalDate future=LocalDate.of(2026,11,5);
    var first=service.preview(List.of(backend.getId()),null,future,"admin");
    var second=service.preview(List.of(app.getId()),null,future,"admin");
    assertThat(first.versionDate()).isEqualTo(future);assertThat(first.branch()).isEqualTo("release20261105");
    Long id=service.create(first.previewId(),"admin").id();await(id);
    assertThat(service.create(second.previewId(),"admin").id()).isEqualTo(id);await(id);
    var result=service.detail(id,"admin");assertThat(result.versionDate()).isEqualTo(future);assertThat(result.branch()).isEqualTo("release20261105");assertThat(result.canAppend()).isTrue();
    assertThat(result.items()).hasSize(2);assertThat(versions.count()).isEqualTo(1);
    verify(git).create(REPO,"release20261105",SHA);verify(git).create(app.getGitRepo(),"release20261105",SHA);
    var sameDate=service.preview(List.of(backend.getId()),null,future,"admin");assertThat(sameDate.versionId()).isEqualTo(id);
    assertThat(sameDate.items().getFirst().status()).isEqualTo("ALREADY_CREATED");
    assertThat(service.create(first.previewId(),"admin").id()).isEqualTo(id);
  }
  @Test void futureVersionAllowsAppendWithItsOriginalDateWithoutDefaultingToToday()throws Exception{
    at("2026-10-17T10:00:00");LocalDate future=LocalDate.of(2026,10,30);
    var first=service.preview(List.of(backend.getId()),null,future,"admin");Long id=service.create(first.previewId(),"admin").id();await(id);clearInvocations(git);
    var append=service.preview(List.of(frontend.getId()),id,"admin");
    assertThat(append.versionDate()).isEqualTo(future);assertThat(append.branch()).isEqualTo("release20261030");assertThat(append.canConfirm()).isTrue();
    when(git.preview(eq(frontend.getGitRepo()),eq("release20261030"))).thenReturn(new VersionGitRepository.Snapshot(SHA,"master",SHA));
    service.create(append.previewId(),"admin");assertThat(service.detail(id,"admin").successCount()).isEqualTo(2);verify(git).preview(frontend.getGitRepo(),"release20261030");verify(git,never()).create(anyString(),anyString(),anyString());
    var other=service.preview(List.of(app.getId()),id,future,"admin");service.create(other.previewId(),"admin");await(id);
    assertThat(service.detail(id,"admin").totalCount()).isEqualTo(3);verify(git).create(app.getGitRepo(),"release20261030",SHA);
  }
  @Test void explicitDateCannotRetargetAnExistingVersion()throws Exception{
    at("2026-10-17T10:00:00");LocalDate future=LocalDate.of(2026,11,5);
    var first=service.preview(List.of(backend.getId()),null,future,"admin");Long id=service.create(first.previewId(),"admin").id();await(id);clearInvocations(git);long previewCount=previews.count();
    assertThatThrownBy(()->service.preview(List.of(app.getId()),id,LocalDate.of(2026,10,17),"admin")).hasMessageContaining("原版本日期不一致");
    assertThat(previews.count()).isEqualTo(previewCount);assertThat(members.count()).isEqualTo(1);assertThat(repositories.count()).isEqualTo(1);verifyNoInteractions(git);
    assertThat(versions.findById(id).orElseThrow().getVersionDate()).isEqualTo(future);
  }
  @Test void newVersionRejectsPastDatesAndNonFourDigitYearsButAllowsFarFuture()throws Exception{
    at("2026-10-17T10:00:00");LocalDate past=LocalDate.of(2026,10,16);
    versions.saveAndFlush(new VersionBatch(past,"release20261016","admin"));
    assertThatThrownBy(()->service.preview(List.of(backend.getId()),null,past,"admin")).hasMessageContaining("今天或未来");
    for(LocalDate invalid:List.of(LocalDate.of(10000,1,1),LocalDate.of(999,12,31)))assertThatThrownBy(()->service.preview(List.of(backend.getId()),null,invalid,"admin")).hasMessageContaining("四位年份");
    assertThat(previews.count()).isZero();assertThat(members.count()).isZero();verifyNoInteractions(git);
    var far=service.preview(List.of(backend.getId()),null,LocalDate.of(9999,12,31),"admin");assertThat(far.branch()).isEqualTo("release99991231");
    Long id=service.create(far.previewId(),"admin").id();await(id);assertThat(service.detail(id,"admin").versionDate()).isEqualTo(LocalDate.of(9999,12,31));
  }
  @Test void newPreviewCannotCreateYesterdayAfterShanghaiMidnight()throws Exception{
    at("2026-10-17T23:59:00");var preview=service.preview(List.of(backend.getId()),null,"admin");
    at("2026-10-18T00:01:00");assertThatThrownBy(()->service.create(preview.previewId(),"admin")).hasMessageContaining("日期已变化");
    assertThat(versions.count()).isZero();assertThat(repositories.count()).isZero();assertThat(members.count()).isZero();
    assertThat(previews.findByToken(preview.previewId()).orElseThrow().getConsumedVersionId()).isNull();verify(git,never()).create(anyString(),anyString(),anyString());
  }
  @Test void existingVersionCannotAppendFromAnEarlierPreviewAfterMidnightButCanRetryItsMembers()throws Exception{
    at("2026-10-17T23:58:00");doThrow(new VersionGitRepository.Failure("PUSH_REJECTED","denied")).when(git).create(eq(REPO),anyString(),anyString());
    var initial=service.preview(List.of(backend.getId()),null,"admin");Long id=service.create(initial.previewId(),"admin").id();await(id);
    at("2026-10-17T23:59:00");var append=service.preview(List.of(app.getId()),id,"admin");
    at("2026-10-18T00:01:00");clearInvocations(git);
    assertThatThrownBy(()->service.create(append.previewId(),"admin")).hasMessageContaining("历史版本不能补充");
    assertThat(versions.count()).isEqualTo(1);assertThat(repositories.count()).isEqualTo(1);assertThat(members.count()).isEqualTo(1);
    assertThat(previews.findByToken(append.previewId()).orElseThrow().getConsumedVersionId()).isNull();verifyNoInteractions(git);
    assertThat(service.detail(id,"admin").canAppend()).isFalse();
    doNothing().when(git).create(eq(REPO),anyString(),anyString());var retry=service.preview(List.of(backend.getId()),id,"admin");
    assertThat(retry.versionDate()).isEqualTo(LocalDate.of(2026,10,17));service.create(retry.previewId(),"admin");await(id);
    assertThat(service.detail(id,"admin").status()).isEqualTo("SUCCESS");verify(git).create(REPO,"release20261017",SHA);
    assertThat(service.create(initial.previewId(),"admin").id()).isEqualTo(id);
  }
  @Test void FuturePreviewRemainsValidWhenSelectedDateBecomesTodayAtMidnight()throws Exception{
    at("2026-10-17T23:59:00");var preview=service.preview(List.of(backend.getId()),null,LocalDate.of(2026,10,18),"admin");
    at("2026-10-18T00:01:00");Long id=service.create(preview.previewId(),"admin").id();await(id);
    assertThat(service.detail(id,"admin").branch()).isEqualTo("release20261018");verify(git).create(REPO,"release20261018",SHA);
  }
  @Test void explicitlyLinksExistingCodeAndSharedMembersWithoutCreatingBranchesOrChangingSuccessfulRepositories()throws Exception{
    Long id=conflictingVersion();
    VersionRepository untouched=repositories.findByVersionIdAndRepositoryIdentity(id,PolicyGitRepository.identity(app.getGitRepo())).orElseThrow();
    var preview=service.previewLink(id,backend.getId(),"admin");
    assertThat(preview.headSha()).isEqualTo("b".repeat(40));
    assertThat(preview.services()).extracting(VersionManagementService.LinkTarget::serviceId).containsExactly(backend.getId(),frontend.getId());
    assertThat(service.detail(id,"admin").successCount()).isEqualTo(1);
    String response=new ObjectMapper().findAndRegisterModules().writeValueAsString(preview);
    assertThat(response).doesNotContain(REPO,"encryptedRepository","configurations");
    var result=service.linkExisting(id,preview.previewId(),"admin");
    assertThat(result.status()).isEqualTo("SUCCESS");assertThat(result.successCount()).isEqualTo(3);
    assertThat(result.items().getFirst().message()).contains("已关联已有分支","b".repeat(40));
    assertThat(result.items().getFirst().baselineSha()).isEqualTo(SHA);
    assertThat(members.findByVersionIdAndServiceId(id,backend.getId()).orElseThrow().getCodeWatchBaselineSha()).isEqualTo("b".repeat(40));
    assertThat(members.findByVersionIdAndServiceId(id,frontend.getId()).orElseThrow().getCodeWatchBaselineSha()).isEqualTo("b".repeat(40));
    VersionRepository linked=repositories.findByVersionIdAndRepositoryIdentity(id,PolicyGitRepository.identity(REPO)).orElseThrow();
    assertThat(linked.getRequestedBy()).isEqualTo("admin");
    assertThat(repositories.findById(untouched.getId()).orElseThrow()).usingRecursiveComparison().isEqualTo(untouched);
    assertThat(versions.count()).isEqualTo(1);assertThat(repositories.count()).isEqualTo(2);assertThat(members.count()).isEqualTo(3);
    assertThat(service.linkExisting(id,preview.previewId(),"admin").id()).isEqualTo(id);
    verify(git,times(2)).preview(REPO,preview.branch());verify(git,never()).create(anyString(),anyString(),anyString());
    assertThat(previews.findByToken(preview.previewId()).orElseThrow().getConsumedVersionId()).isEqualTo(id);
    assertThat(services.findById(backend.getId()).orElseThrow().getDefaultBranch()).isEqualTo("main");
  }
  @Test void changedHeadChangedMasterAndDeletedBranchCannotConsumeLinkConfirmation()throws Exception{
    Long id=conflictingVersion();
    for(var changed:List.of(new VersionGitRepository.Snapshot(SHA,"master","c".repeat(40)),
      new VersionGitRepository.Snapshot("c".repeat(40),"new master","b".repeat(40)),new VersionGitRepository.Snapshot(SHA,"master",null))){
      when(git.preview(eq(REPO),anyString())).thenReturn(new VersionGitRepository.Snapshot(SHA,"master","b".repeat(40)));
      var preview=service.previewLink(id,backend.getId(),"admin");
      when(git.preview(eq(REPO),anyString())).thenReturn(changed);
      assertThatThrownBy(()->service.linkExisting(id,preview.previewId(),"admin")).hasMessageContaining("已变化");
      assertThat(previews.findByToken(preview.previewId()).orElseThrow().getConsumedVersionId()).isNull();
      assertThat(service.detail(id,"admin").items().getFirst().status()).isEqualTo("CONFLICT");
    }
    verify(git,never()).create(anyString(),anyString(),anyString());
  }
  @Test void missingOrUnreadableRemoteBranchCannotProduceLinkPreview()throws Exception{
    Long id=conflictingVersion();long count=previews.count();
    when(git.preview(eq(REPO),anyString())).thenReturn(new VersionGitRepository.Snapshot(SHA,"master",null));
    assertThatThrownBy(()->service.previewLink(id,backend.getId(),"admin")).hasMessageContaining("远程分支不存在");
    when(git.preview(eq(REPO),anyString())).thenThrow(new VersionGitRepository.Failure("READ_FAILED","无法读取仓库，请检查凭据"));
    assertThatThrownBy(()->service.previewLink(id,backend.getId(),"admin")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("检查凭据");
    assertThat(previews.count()).isEqualTo(count);assertThat(service.detail(id,"admin").successCount()).isEqualTo(1);
  }
  @Test void linkRequiresAdministratorMatchingOperationVersionAndPreviewOwner()throws Exception{
    Long id=conflictingVersion();var preview=service.previewLink(id,backend.getId(),"admin");
    for(String operator:List.of("legacy","web","mobile","version-only")){
      assertThatThrownBy(()->service.previewLink(id,backend.getId(),operator)).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(()->service.linkExisting(id,preview.previewId(),operator)).isInstanceOf(AccessDeniedException.class);
    }
    assertThatThrownBy(()->service.linkExisting(id+1,preview.previewId(),"admin")).hasMessageContaining("不匹配");
    var createPreview=service.preview(List.of(app.getId()),id,"admin");
    assertThatThrownBy(()->service.linkExisting(id,createPreview.previewId(),"admin")).hasMessageContaining("不匹配");
    assertThatThrownBy(()->service.create(preview.previewId(),"admin")).hasMessageContaining("关联已有分支入口");
    VersionPreview stored=previews.findByToken(preview.previewId()).orElseThrow();
    ReflectionTestUtils.setField(stored,"operator","web");previews.saveAndFlush(stored);
    assertThatThrownBy(()->service.linkExisting(id,preview.previewId(),"admin")).isInstanceOf(AccessDeniedException.class);
    verify(git,never()).create(anyString(),anyString(),anyString());
  }
  @Test void expiredLinkOrChangedSharedServiceConfigurationCannotBeConfirmed()throws Exception{
    Long id=conflictingVersion();var expired=service.previewLink(id,backend.getId(),"admin");
    VersionPreview stored=previews.findByToken(expired.previewId()).orElseThrow();
    ReflectionTestUtils.setField(stored,"expiresAt",Instant.now().minusSeconds(1));previews.saveAndFlush(stored);clearInvocations(git);
    assertThatThrownBy(()->service.linkExisting(id,expired.previewId(),"admin")).hasMessageContaining("过期");verifyNoInteractions(git);
    var changed=service.previewLink(id,backend.getId(),"admin");frontend.setName("renamed shared service");services.saveAndFlush(frontend);clearInvocations(git);
    assertThatThrownBy(()->service.linkExisting(id,changed.previewId(),"admin")).hasMessageContaining("配置或版本状态已变化");verifyNoInteractions(git);
    assertThat(previews.findByToken(changed.previewId()).orElseThrow().getConsumedVersionId()).isNull();
    var redirected=service.previewLink(id,backend.getId(),"admin");backend.setGitRepo("git@example.invalid:other/repository.git");services.saveAndFlush(backend);clearInvocations(git);
    assertThatThrownBy(()->service.linkExisting(id,redirected.previewId(),"admin")).hasMessageContaining("仓库已变更");verifyNoInteractions(git);
  }
  @Test void activeSuccessfulAndUnrelatedServicesCannotBeLinkedAndRetryInvalidatesOldLinkPreview()throws Exception{
    Long id=conflictingVersion();
    assertThatThrownBy(()->service.previewLink(id,app.getId(),"admin")).hasMessageContaining("仅可关联");
    AppService other=app("outside",ServiceType.BACKEND,REPO);
    assertThatThrownBy(()->service.previewLink(id,other.getId(),"admin")).hasMessageContaining("不属于此版本");
    var preview=service.previewLink(id,backend.getId(),"admin");
    VersionRepository repository=repositories.findByVersionIdAndRepositoryIdentity(id,PolicyGitRepository.identity(REPO)).orElseThrow();
    repository.prepare(crypto.encrypt(REPO),SHA,"master","admin");repositories.saveAndFlush(repository);clearInvocations(git);
    assertThatThrownBy(()->service.linkExisting(id,preview.previewId(),"admin")).hasMessageContaining("仅可关联");
    assertThatThrownBy(()->service.previewLink(id,backend.getId(),"admin")).hasMessageContaining("仅可关联");
    repository.result("CONFLICT","BRANCH_EXISTS","conflict");repositories.saveAndFlush(repository);
    assertThatThrownBy(()->service.linkExisting(id,preview.previewId(),"admin")).hasMessageContaining("状态已变化");verifyNoInteractions(git);
  }
  @Test void configurationAndPermissionsAreRecheckedAfterRemoteReadBeforeLinkIsPersisted()throws Exception{
    Long id=conflictingVersion();var preview=service.previewLink(id,backend.getId(),"admin");
    when(git.preview(eq(REPO),anyString())).thenAnswer(call->{
      frontend.setName("changed during read");services.saveAndFlush(frontend);
      return new VersionGitRepository.Snapshot(SHA,"master","b".repeat(40));
    });
    assertThatThrownBy(()->service.linkExisting(id,preview.previewId(),"admin")).hasMessageContaining("配置或版本状态已变化");
    assertThat(previews.findByToken(preview.previewId()).orElseThrow().getConsumedVersionId()).isNull();
    when(git.preview(eq(REPO),anyString())).thenReturn(new VersionGitRepository.Snapshot(SHA,"master","b".repeat(40)));
    var second=service.previewLink(id,backend.getId(),"admin");
    when(git.preview(eq(REPO),anyString())).thenAnswer(call->{
      AdminUser admin=users.findByUsername("admin").orElseThrow();admin.setEnabled(false);users.saveAndFlush(admin);
      return new VersionGitRepository.Snapshot(SHA,"master","b".repeat(40));
    });
    assertThatThrownBy(()->service.linkExisting(id,second.previewId(),"admin")).isInstanceOf(AccessDeniedException.class);
    assertThat(previews.findByToken(second.previewId()).orElseThrow().getConsumedVersionId()).isNull();
    assertThat(repositories.findByVersionIdAndRepositoryIdentity(id,PolicyGitRepository.identity(REPO)).orElseThrow().getStatus()).isEqualTo("CONFLICT");
  }
  private Long conflictingVersion()throws Exception{
    when(git.preview(eq(REPO),anyString())).thenReturn(new VersionGitRepository.Snapshot(SHA,"confirmed master","b".repeat(40)));
    var preview=service.preview(List.of(backend.getId(),frontend.getId(),app.getId()),null,"admin");
    Long id=service.create(preview.previewId(),"admin").id();await(id);clearInvocations(git);return id;
  }
  private void at(String local){service.clock=Clock.fixed(LocalDateTime.parse(local).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneOffset.UTC);}
  private void await(Long id)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(service.detail(id,"admin").runningCount()>0&&System.nanoTime()<end)Thread.sleep(15);assertThat(service.detail(id,"admin").runningCount()).isZero();}
  private void user(String name,List<String> permissions){AdminUser user=new AdminUser(name,"unused");user.setMenuPermissions(permissions);users.saveAndFlush(user);}
  private AppService app(String name,ServiceType type,String repository){AppService s=new AppService();s.setName(name);s.setServiceKey(name);s.setServiceType(type);s.setGitRepo(repository);return services.saveAndFlush(s);}
}
