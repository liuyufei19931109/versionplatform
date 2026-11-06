package com.local.release.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PolicyGitRepositoryTest {
  @TempDir Path temp;
  Path work,remote;PolicyGitRepository policy;
  @BeforeEach void setup()throws Exception{
    work=Files.createDirectory(temp.resolve("work"));remote=temp.resolve("remote.git");
    git(temp,"init","--bare",remote.toString());git(work,"init","-b","master");git(work,"config","user.name","Test");git(work,"config","user.email","test@example.invalid");
    git(work,"remote","add","origin",remote.toString());commit("initial");git(work,"push","origin","master");policy=new PolicyGitRepository(temp.resolve("private-policy").toString());
  }
  @Test void usesFullAncestryAndBlocksStaleBranch()throws Exception{
    String old=git(work,"rev-parse","HEAD");git(work,"branch","release-old");
    for(int i=0;i<5;i++)commit("master "+i);String latest=git(work,"rev-parse","HEAD");git(work,"push","origin","master");
    git(work,"checkout","release-old");String stale=commit("stale release");git(work,"push","origin","release-old");
    var rejected=policy.check(remote.toString(),stale);assertThat(rejected.baselineSha()).isEqualTo(latest);assertThat(rejected.containsBaseline()).isFalse();
    git(work,"checkout","master");String good=commit("current release");git(work,"push","origin","HEAD:release-current");
    assertThat(policy.check(remote.toString(),good).containsBaseline()).isTrue();assertThat(policy.check(remote.toString(),old).containsBaseline()).isFalse();
  }
  @Test void mergesOnlyDeployedCommitNotLaterReleaseHead()throws Exception{
    String before=git(work,"rev-parse","master");git(work,"checkout","-b","release");String deployed=commit("released version");commit("not yet released");git(work,"push","origin","release");
    var result=policy.mergeFixedCommit(remote.toString(),deployed);
    assertThat(result.outcome()).isEqualTo("MERGED");assertThat(result.before()).isEqualTo(before);assertThat(result.after()).isEqualTo(deployed);
    assertThat(git(remote,"rev-parse","master")).isEqualTo(deployed);assertThat(git(remote,"rev-parse","release")).isNotEqualTo(deployed);
    assertThat(policy.mergeFixedCommit(remote.toString(),before).outcome()).isEqualTo("ALREADY_MERGED");assertThat(git(remote,"rev-parse","master")).isEqualTo(deployed);
  }
  @Test void concurrentMasterDivergenceNeverCreatesMergeTreeOrForcePushes()throws Exception{
    git(work,"checkout","-b","release");String deployed=commit("release");git(work,"push","origin","release");
    git(work,"checkout","master");String advanced=commit("different production change");git(work,"push","origin","master");
    assertThatThrownBy(()->policy.mergeFixedCommit(remote.toString(),deployed)).isInstanceOf(PolicyGitRepository.GitPolicyException.class).hasMessageContaining("其他变更");
    assertThat(git(remote,"rev-parse","master")).isEqualTo(advanced);
  }
  @Test void missingMasterAndMissingCommitFailClosed()throws Exception{
    String sha=git(work,"rev-parse","HEAD");git(remote,"symbolic-ref","HEAD","refs/heads/other");git(remote,"update-ref","-d","refs/heads/master");
    assertThatThrownBy(()->policy.check(remote.toString(),sha)).hasMessageContaining("master");
    git(work,"push","origin","master");assertThatThrownBy(()->policy.check(remote.toString(),"a".repeat(40))).hasMessageContaining("Commit");
  }
  @Test void serverHookRejectionRemainsRejected()throws Exception{
    String before=git(work,"rev-parse","HEAD");git(work,"checkout","-b","release");String deployed=commit("release");git(work,"push","origin","release");
    Path hook=remote.resolve("hooks/pre-receive");Files.writeString(hook,"#!/bin/sh\nexit 1\n");try{Files.setPosixFilePermissions(hook,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
    assertThatThrownBy(()->policy.mergeFixedCommit(remote.toString(),deployed)).isInstanceOf(PolicyGitRepository.GitPolicyException.class).hasMessageContaining("合回被拒绝");
    assertThat(git(remote,"rev-parse","master")).isEqualTo(before);
  }
  @Test void repositoryIdentityIgnoresTransportAndCredentials(){
    assertThat(PolicyGitRepository.identity("git@github.com:team/project.git")).isEqualTo(PolicyGitRepository.identity("https://token:secret@github.com/team/project"));
    assertThat(PolicyGitRepository.identity("ssh://git@github.com:2222/team/project.git")).isNotEqualTo(PolicyGitRepository.identity("git@github.com:team/project.git"));
    assertThatThrownBy(()->policy.check("ext::anything","a".repeat(40))).isInstanceOf(IllegalArgumentException.class);
  }
  private String commit(String message)throws Exception{Files.writeString(work.resolve("history.txt"),message);git(work,"add","history.txt");git(work,"commit","-m",message);return git(work,"rev-parse","HEAD");}
  private String git(Path dir,String... args)throws Exception{List<String> command=new ArrayList<>();command.add("git");command.addAll(Arrays.asList(args));return BoundedProcessRunner.run(command,dir,null,30,null).trim();}
}
