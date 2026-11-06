package com.local.release.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** All Git operations use temporary local repositories; no credentials or network are required. */
class GitHubBranchReaderTest {
  @TempDir Path temp;
  static final String BRANCH="release20261017";

  @Test void onlyLsRemoteIsConstructedWithExplicitRefAndOptionsTerminator(){
    Path hooks=temp.resolve("empty hooks");String repository="git@github.com:example-org/sample.git";
    assertThat(GitHubBranchReader.command(hooks,repository,BRANCH)).containsExactly("git","-c","protocol.ext.allow=never","-c","credential.interactive=false","-c","core.hooksPath="+hooks,"ls-remote","--heads","--",repository,"refs/heads/"+BRANCH);
    assertThat(GitHubBranchReader.command(hooks,"https://github.com/example-org/a;echo-owned.git",BRANCH)).contains("https://github.com/example-org/a;echo-owned.git").doesNotContain("sh","bash","cmd","powershell","push","fetch","clone","checkout");
  }

  @Test void maliciousRepositoryAndNonManagedRefsAreRejectedBeforeProcessStart(){
    for(String repository:Arrays.asList(null,"","-c alias.x=bad","ext::anything","https://github.com/org/repo\n--upload-pack=bad","repo\rname","repo\0name"))
      assertThatThrownBy(()->GitHubBranchReader.command(temp,repository,BRANCH)).isInstanceOf(IllegalArgumentException.class);
    for(String branch:Arrays.asList(null,"","master","main","release20261017;echo bad","release20261017/other","refs/heads/release20261017","release2026101","release202610177","release２０２６１０１７","-c"))
      assertThatThrownBy(()->GitHubBranchReader.command(temp,"git@github.com:org/repo.git",branch)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void actualHeadReadsDoNotModifyAnyRemoteFileOrCreateLocalRepository()throws Exception{
    Path work=Files.createDirectory(temp.resolve("source")),remote=temp.resolve("remote.git");
    git(temp,"init","--bare",remote.toString());git(work,"init","-b","master");git(work,"config","user.name","Readonly Fixture");git(work,"config","user.email","readonly@example.invalid");
    Files.writeString(work.resolve("app.txt"),"current source");git(work,"add","app.txt");git(work,"commit","-m","fixture");String initial=git(work,"rev-parse","HEAD");
    git(work,"remote","add","origin",remote.toString());git(work,"push","origin","master");git(remote,"update-ref","refs/heads/"+BRANCH,initial);
    Map<String,String> before=digests(remote);Path readRoot=temp.resolve("readonly-work");GitHubBranchReader reader=new GitHubBranchReader(readRoot.toString());
    assertThat(reader.head(remote.toString(),BRANCH)).isEqualTo(initial);assertThat(reader.head(remote.toString(),"release20261018")).isNull();
    assertThat(digests(remote)).isEqualTo(before);assertThat(Files.exists(readRoot.resolve(".git"))).isFalse();assertThat(Files.exists(readRoot.resolve("FETCH_HEAD"))).isFalse();
    try(var files=Files.walk(readRoot)){assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();}
    Files.writeString(work.resolve("app.txt"),"advanced source");git(work,"add","app.txt");git(work,"commit","-m","advanced");String next=git(work,"rev-parse","HEAD");git(work,"push","origin","HEAD:refs/heads/"+BRANCH);
    Map<String,String> advanced=digests(remote);assertThat(reader.head(remote.toString(),BRANCH)).isEqualTo(next);assertThat(digests(remote)).isEqualTo(advanced);
  }

  @Test void failedHeadLookupDoesNotExposeRawGitOutputOrRepositoryPath(){
    String privateName="private-fixture-password=hidden";String remote=temp.resolve(privateName).toString();
    assertThatThrownBy(()->new GitHubBranchReader(temp.resolve("reader").toString()).head(remote,BRANCH))
      .isInstanceOf(java.io.IOException.class).hasMessage("Remote head check failed").hasMessageNotContaining(privateName);
  }

  private static Map<String,String> digests(Path root)throws Exception{
    Map<String,String> values=new TreeMap<>();try(var files=Files.walk(root)){for(Path file:files.filter(Files::isRegularFile).toList())values.put(root.relativize(file).toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));}return values;
  }
  private static String git(Path directory,String... args)throws Exception{List<String> command=new ArrayList<>(List.of("git"));command.addAll(Arrays.asList(args));return BoundedProcessRunner.run(command,directory,null,30,null).trim();}
}
