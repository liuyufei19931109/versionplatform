package com.local.release.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class VersionGitRepositoryTest {
  @TempDir Path temp;
  Path work,remote;VersionGitRepository git;String initial;
  final String branch="release20261017";
  @BeforeEach void setup()throws Exception{
    work=Files.createDirectory(temp.resolve("work"));remote=temp.resolve("remote.git");
    command(temp,"init","--bare",remote.toString());command(work,"init","-b","master");
    command(work,"config","user.name","Version Test");command(work,"config","user.email","versions@example.invalid");
    command(work,"remote","add","origin",remote.toString());initial=commit("initial master");command(work,"push","origin","master");
    git=new VersionGitRepository(temp.resolve("cache").toString());
  }
  @Test void createsExactlyOneBranchAtConfirmedMasterWithoutChangingMasterOrExistingDeveloperBranches()throws Exception{
    command(work,"branch","developer");command(work,"push","origin","developer");
    var preview=git.preview(remote.toString(),branch);
    assertThat(preview.sha()).isEqualTo(initial);assertThat(preview.summary()).isEqualTo("initial master");assertThat(preview.existingBranchSha()).isNull();
    git.create(remote.toString(),branch,preview.sha());
    assertThat(command(remote,"rev-parse",branch)).isEqualTo(initial);
    assertThat(command(remote,"rev-parse","master")).isEqualTo(initial);assertThat(command(remote,"rev-parse","developer")).isEqualTo(initial);
  }
  @Test void existingSameShaAndFastForwardableBranchesAreNeverAdoptedOrChanged()throws Exception{
    command(remote,"update-ref","refs/heads/"+branch,initial);
    assertThatThrownBy(()->git.create(remote.toString(),branch,initial)).isInstanceOfSatisfying(VersionGitRepository.Failure.class,e->assertThat(e.code()).isEqualTo("BRANCH_EXISTS"));
    String next=commit("new master");command(work,"push","origin","master");
    assertThatThrownBy(()->git.create(remote.toString(),branch,next)).hasMessageContaining("已存在");
    assertThat(command(remote,"rev-parse",branch)).isEqualTo(initial);
  }
  @Test void readingExistingDeveloperBranchForLinkingLeavesAllRemoteRefsIntact()throws Exception{
    command(work,"checkout","-b",branch);String head=commit("existing release work");command(work,"push","origin",branch);
    String refs=command(remote,"show-ref");
    var snapshot=git.preview(remote.toString(),branch);
    assertThat(snapshot.sha()).isEqualTo(initial);assertThat(snapshot.existingBranchSha()).isEqualTo(head);
    assertThat(command(remote,"show-ref")).isEqualTo(refs);
  }
  @Test void masterAdvanceAfterPreviewRequiresFreshConfirmation()throws Exception{
    var preview=git.preview(remote.toString(),branch);String latest=commit("advanced after preview");command(work,"push","origin","master");
    assertThatThrownBy(()->git.create(remote.toString(),branch,preview.sha())).isInstanceOfSatisfying(VersionGitRepository.Failure.class,e->assertThat(e.code()).isEqualTo("BASELINE_CHANGED"));
    assertThat(command(remote,"for-each-ref","--format=%(refname)","refs/heads/"+branch)).isEmpty();assertThat(command(remote,"rev-parse","master")).isEqualTo(latest);
  }
  @Test void twoIndependentCachesRacingForSameShaHaveExactlyOneCreator()throws Exception{
    var other=new VersionGitRepository(temp.resolve("cache-2").toString());
    git.preview(remote.toString(),branch);other.preview(remote.toString(),branch);
    CountDownLatch ready=new CountDownLatch(1);
    try(ExecutorService workers=Executors.newFixedThreadPool(2)){
      Callable<String> first=()->{ready.await();try{git.create(remote.toString(),branch,initial);return "CREATED";}catch(VersionGitRepository.Failure e){return e.code();}};
      Callable<String> second=()->{ready.await();try{other.create(remote.toString(),branch,initial);return "CREATED";}catch(VersionGitRepository.Failure e){return e.code();}};
      Future<String> a=workers.submit(first),b=workers.submit(second);ready.countDown();List<String> results=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
      assertThat(results.stream().filter("CREATED"::equals)).hasSize(1);assertThat(results).anyMatch(s->Set.of("BRANCH_EXISTS","PUSH_REJECTED").contains(s));
    }
    assertThat(command(remote,"rev-parse",branch)).isEqualTo(initial);
  }
  @Test void missingMasterNeverFallsBackToMain()throws Exception{
    command(remote,"update-ref","refs/heads/main",initial);command(remote,"symbolic-ref","HEAD","refs/heads/main");command(remote,"update-ref","-d","refs/heads/master");
    assertThatThrownBy(()->git.preview(remote.toString(),branch)).isInstanceOfSatisfying(VersionGitRepository.Failure.class,e->assertThat(e.code()).isEqualTo("MASTER_MISSING"));
    assertThat(command(remote,"for-each-ref","--format=%(refname)","refs/heads/"+branch)).isEmpty();
  }
  @Test void remoteWriteRejectionDoesNotClaimSuccessOrExposeRawHookOutput()throws Exception{
    Path hook=remote.resolve("hooks/pre-receive");Files.writeString(hook,"#!/bin/sh\necho 'token=private-fixture-value' >&2\nexit 1\n");
    try{Files.setPosixFilePermissions(hook,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
    assertThatThrownBy(()->git.create(remote.toString(),branch,initial)).isInstanceOfSatisfying(VersionGitRepository.Failure.class,e->{assertThat(e.code()).isEqualTo("PUSH_REJECTED");assertThat(e.getMessage()).doesNotContain("private-fixture-value");});
    assertThat(command(remote,"for-each-ref","--format=%(refname)","refs/heads/"+branch)).isEmpty();
  }
  @Test void configuredFollowTagsCannotPushTagsAsSideEffect()throws Exception{
    git.preview(remote.toString(),branch);Path cache=temp.resolve("cache").resolve(PolicyGitRepository.identity(remote.toString()));
    command(cache,"config","user.name","Version Test");command(cache,"config","user.email","versions@example.invalid");
    command(cache,"tag","-a","unrelated-tag","-m","must stay local",initial);command(cache,"config","push.followTags","true");
    git.create(remote.toString(),branch,initial);assertThat(command(remote,"tag","--list")).isEmpty();
  }
  @Test void unsafeRefOrExternalProtocolIsRejectedBeforeRunningGit(){
    assertThatThrownBy(()->git.preview("ext::anything",branch)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->git.preview(remote.toString(),"master")).isInstanceOf(IllegalArgumentException.class);
  }
  private String commit(String message)throws Exception{Files.writeString(work.resolve("history.txt"),message);command(work,"add","history.txt");command(work,"commit","-m",message);return command(work,"rev-parse","HEAD");}
  private String command(Path directory,String... args)throws Exception{List<String> command=new ArrayList<>(List.of("git"));command.addAll(Arrays.asList(args));return BoundedProcessRunner.run(command,directory,null,30,null).trim();}
}
