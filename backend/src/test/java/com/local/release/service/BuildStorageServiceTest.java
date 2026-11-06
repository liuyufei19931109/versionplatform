package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import com.local.release.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuildStorageServiceTest {
  private static final String DIGEST="sha256:"+"a".repeat(64),IMAGE_ID="sha256:"+"b".repeat(64);
  private static final String IMAGE="registry.example.com/team/service:main-123456-b7";
  @TempDir Path temporary;
  final ObjectMapper mapper=new ObjectMapper();
  ReleaseTaskRepository tasks;ArtifactRepository artifacts;ReleaseStepRepository steps;PlatformTransactionManager tx;
  ReleaseTask task;Artifact artifact;ReleaseStep push;Storage storage;Path root;
  final List<String> events=new ArrayList<>();

  @BeforeEach void setup()throws Exception {
    tasks=mock(ReleaseTaskRepository.class);artifacts=mock(ArtifactRepository.class);steps=mock(ReleaseStepRepository.class);tx=mock(PlatformTransactionManager.class);
    when(tx.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
    doAnswer(call->{events.add("commit");return null;}).when(tx).commit(any());
    task=new ReleaseTask(2L,TaskKind.BUILD,null,"build","main","",false);
    entity(task,7L);task.setStatus(TaskStatus.SUCCESS);task.setCommitId("123456"+"1".repeat(34));task.setFinishedAt(LocalDateTime.now());
    artifact=new Artifact(2L,7L,"main-123456-b7","main",task.getCommitId(),IMAGE);entity(artifact,9L);
    push=new ReleaseStep(7L,StepType.PUSH_IMAGE);push.setStatus(StepStatus.SUCCESS);push.setLogText("push succeeded\n");
    when(tasks.findById(7L)).thenReturn(Optional.of(task));
    when(artifacts.findById(9L)).thenReturn(Optional.of(artifact));when(artifacts.findByBuildTaskId(7L)).thenReturn(Optional.of(artifact));
    when(artifacts.saveAndFlush(any())).thenAnswer(call->{events.add("save-digest");return call.getArgument(0);});
    when(steps.findByReleaseTaskIdOrderById(7L)).thenReturn(List.of(push));
    root=Files.createDirectory(temporary.resolve("workspaces"));
    Files.createDirectories(root.resolve("7/source"));Files.writeString(root.resolve("7/source/output.jar"),"disposable");
    storage=new Storage();storage.workspaceRoot=root.toString();
  }

  private void entity(BaseEntity entity,long id) {
    ReflectionTestUtils.setField(entity,"id",id);ReflectionTestUtils.setField(entity,"createdAt",LocalDateTime.of(2026,10,11,10,0).plusSeconds(id));
  }
  Path journal(){return root.resolve(".storage-maintenance/7.json");}

  class Storage extends BuildStorageService {
    List<List<String>> commands=new ArrayList<>();boolean otherPresent=true,registryAvailable=true,removeFails,containerReferenced,workspaceFails;
    String remoteDigest=DIGEST,localRepository="registry.example.com/team/service";
    int removedWorkspaces;
    Storage(){super(mapper,tasks,artifacts,steps,tx);}
    @Override String command(List<String> command,Path directory,long timeout)throws Exception {
      commands.add(command);assertThat(directory).isEqualTo(root);assertThat(timeout).isBetween(1L,60L);
      if(command.subList(0,3).equals(List.of("docker","image","ls")))return otherPresent?IMAGE_ID+"\n":"";
      if(command.subList(0,3).equals(List.of("docker","image","inspect")))return mapper.writeValueAsString(List.of(Map.of("Id",IMAGE_ID,"RepoTags",List.of(IMAGE),"RepoDigests",List.of(localRepository+"@"+DIGEST))));
      if(command.subList(0,3).equals(List.of("docker","manifest","inspect"))) {
        if(!registryAvailable)throw new IOException("registry timeout");
        return mapper.writeValueAsString(Map.of("Descriptor",Map.of("digest",remoteDigest)));
      }
      if(command.subList(0,2).equals(List.of("docker","ps")))return containerReferenced?"container-id":"";
      if(command.subList(0,3).equals(List.of("docker","image","rm"))) {
        assertThat(command).containsExactly("docker","image","rm","--no-prune",IMAGE);
        if(removeFails)throw new IOException("image in use");
        events.add("remove-image");otherPresent=false;return "untagged";
      }
      throw new AssertionError("Unexpected command: "+command);
    }
    @Override void removeWorkspace(Path directory,long taskId)throws IOException {
      if(workspaceFails)throw new IOException("permission denied");
      assertThat(taskId).isEqualTo(7L);assertThat(directory).isEqualTo(root);
      assertThat(artifact.getImageDigest()).isEqualTo(DIGEST);
      assertThat(events).contains("commit");removedWorkspaces++;events.add("remove-workspace");
    }
    void actualRemove(long id)throws IOException {super.removeWorkspace(root,id);}
  }

  @Test void verifiedDigestCommitsBeforeOnlyThisBuildOutputIsRemoved() {
    storage.afterSuccessfulBuild(7L);
    assertThat(events).containsSubsequence("save-digest","commit","remove-workspace","remove-image");
    assertThat(artifact.getImageDigest()).isEqualTo(DIGEST);assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(journal()).doesNotExist();assertThat(push.getLogText()).contains("本地构建文件已清理");
    verify(tasks,never()).save(any());verify(artifacts,never()).delete(any());
    assertThat(storage.commands).allSatisfy(args->assertThat(args).doesNotContain("prune","--force","-f","system","builder","buildx"));
  }

  @Test void unavailableRegistryPreservesBothOutputsAndQueuesMaintenanceWithoutFailingBuild() {
    storage.registryAvailable=false;storage.afterSuccessfulBuild(7L);
    assertThat(storage.otherPresent).isTrue();assertThat(storage.removedWorkspaces).isZero();assertThat(journal()).exists();
    assertThat(artifact.getImageDigest()).isNull();assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(push.getStatus()).isEqualTo(StepStatus.SUCCESS);assertThat(push.getLogText()).contains("registry timeout");
  }

  @Test void sameDigestFromAnotherRepositoryDoesNotAuthorizeDeletion() {
    storage.localRepository="registry.example.com/unrelated/service";storage.afterSuccessfulBuild(7L);
    assertThat(storage.removedWorkspaces).isZero();assertThat(storage.otherPresent).isTrue();assertThat(artifact.getImageDigest()).isNull();
  }

  @Test void changedRegistryTagDoesNotAuthorizeDeletion() {
    storage.remoteDigest="sha256:"+"c".repeat(64);storage.afterSuccessfulBuild(7L);
    assertThat(storage.removedWorkspaces).isZero();assertThat(storage.otherPresent).isTrue();assertThat(artifact.getImageDigest()).isNull();
  }

  @Test void digestCommitFailureLeavesLocalOutputsAndSuccessfulRecordUntouched() {
    doThrow(new IllegalStateException("database commit failed")).when(tx).commit(any());
    storage.afterSuccessfulBuild(7L);
    assertThat(storage.removedWorkspaces).isZero();assertThat(storage.otherPresent).isTrue();assertThat(journal()).exists();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
  }

  @ParameterizedTest @EnumSource(value=TaskStatus.class,names={"FAILED","PENDING","RUNNING","CANCELLED"})
  void nonSuccessfulBuildCannotBeCleaned(TaskStatus state) {
    task.setStatus(state);storage.afterSuccessfulBuild(7L);
    assertThat(storage.commands).isEmpty();assertThat(storage.removedWorkspaces).isZero();assertThat(journal()).doesNotExist();
    assertThat(task.getStatus()).isEqualTo(state);
  }

  @Test void imageRemovalFailureIsRetriedFromJournalAfterRecheckingRegistry() {
    storage.removeFails=true;storage.afterSuccessfulBuild(7L);
    assertThat(journal()).exists();assertThat(storage.otherPresent).isTrue();assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    storage.commands.clear();storage.removeFails=false;storage.afterSuccessfulBuild(7L);
    assertThat(storage.commands).anySatisfy(args->assertThat(args).containsExactly("docker","manifest","inspect","--verbose",IMAGE));
    assertThat(storage.otherPresent).isFalse();assertThat(journal()).doesNotExist();
  }

  @Test void laterSuccessfulBuildRetriesExistingMaintenanceWithoutScanningHistoricTasks() {
    storage.registryAvailable=false;storage.afterSuccessfulBuild(7L);assertThat(journal()).exists();
    ReleaseTask next=new ReleaseTask(2L,TaskKind.BUILD,null,"build","main","",false);
    entity(next,8L);next.setStatus(TaskStatus.SUCCESS);next.setCommitId(task.getCommitId());next.setFinishedAt(LocalDateTime.now());
    Artifact nextArtifact=new Artifact(2L,8L,"main-123456-b8","main",next.getCommitId(),"registry.example.com/team/service:main-123456-b8");entity(nextArtifact,10L);
    ReleaseStep nextPush=new ReleaseStep(8L,StepType.PUSH_IMAGE);nextPush.setStatus(StepStatus.SUCCESS);
    when(tasks.findById(8L)).thenReturn(Optional.of(next));when(artifacts.findById(10L)).thenReturn(Optional.of(nextArtifact));
    when(artifacts.findByBuildTaskId(8L)).thenReturn(Optional.of(nextArtifact));when(steps.findByReleaseTaskIdOrderById(8L)).thenReturn(List.of(nextPush));
    storage.registryAvailable=true;storage.afterSuccessfulBuild(8L);
    assertThat(journal()).doesNotExist();assertThat(storage.otherPresent).isFalse();assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
    verify(tasks,never()).findAll();verify(artifacts,never()).findAll();
  }

  @Test void directoryPermissionFailurePreservesImageAndCanRetry() {
    storage.workspaceFails=true;storage.afterSuccessfulBuild(7L);
    assertThat(storage.otherPresent).isTrue();assertThat(journal()).exists();assertThat(push.getLogText()).contains("permission denied");
    storage.workspaceFails=false;storage.afterSuccessfulBuild(7L);assertThat(journal()).doesNotExist();
  }

  @Test void localContainerReferencesPreventImageDeletion() {
    storage.containerReferenced=true;storage.afterSuccessfulBuild(7L);
    assertThat(storage.otherPresent).isTrue();assertThat(journal()).exists();
    assertThat(storage.commands).noneSatisfy(args->assertThat(args).contains("rm"));
  }

  @Test void localImageAlreadyRemovedCanRetryUsingPreviouslyCommittedDigest() {
    artifact.setImageDigest(DIGEST);storage.otherPresent=false;storage.afterSuccessfulBuild(7L);
    assertThat(storage.removedWorkspaces).isEqualTo(1);assertThat(journal()).doesNotExist();
  }

  @Test void missingUnverifiedImageDoesNotAuthorizeWorkspaceDeletion() {
    storage.otherPresent=false;storage.afterSuccessfulBuild(7L);
    assertThat(storage.removedWorkspaces).isZero();assertThat(journal()).exists();
  }

  @Test void unsafeJournalCannotBeOverwrittenAndDoesNotAffectSuccess()throws Exception {
    Files.createDirectory(journal().getParent());Files.createDirectory(journal());
    storage.afterSuccessfulBuild(7L);
    assertThat(storage.commands).isEmpty();assertThat(journal()).isDirectory();assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCESS);
  }

  @Test void staleJournalCannotTargetAnotherBuildWithReusedIdentifier()throws Exception {
    storage.registryAvailable=false;storage.afterSuccessfulBuild(7L);
    ReflectionTestUtils.setField(task,"createdAt",task.getCreatedAt().plusDays(1));storage.commands.clear();
    storage.afterSuccessfulBuild(7L);
    assertThat(storage.commands).isEmpty();assertThat(storage.removedWorkspaces).isZero();
  }

  @Test void disabledMaintenanceDoesNotCreateJournalsOrRunCommands() {
    storage.enabled=false;storage.afterSuccessfulBuild(7L);
    assertThat(storage.commands).isEmpty();assertThat(journal().getParent()).doesNotExist();
  }

  @Test void insecureFilesystemFailsClosedWhileSecureFilesystemRemovesOnlyTaskDirectory()throws Exception {
    Path other=Files.createDirectory(root.resolve("8"));Files.writeString(other.resolve("keep"),"retained");
    boolean supported;try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)){supported=entries instanceof SecureDirectoryStream<?>;}
    if(supported){storage.actualRemove(7);assertThat(root.resolve("7")).doesNotExist();}
    else{assertThatThrownBy(()->storage.actualRemove(7)).isInstanceOf(IOException.class).hasMessageContaining("安全目录删除");assertThat(root.resolve("7/source/output.jar")).exists();}
    assertThat(other.resolve("keep")).hasContent("retained");
    assertThatThrownBy(()->storage.actualRemove(-1)).isInstanceOf(IOException.class);
  }

  @Test void symbolicLinksInsideWorkspaceAreUnlinkedWithoutTraversingTheirTargets()throws Exception {
    try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)){assumeTrue(entries instanceof SecureDirectoryStream<?>);}
    Path outside=Files.createDirectory(temporary.resolve("outside"));Files.writeString(outside.resolve("keep"),"external");
    Files.createSymbolicLink(root.resolve("7/source/link"),outside);
    storage.actualRemove(7);assertThat(root.resolve("7")).doesNotExist();assertThat(outside.resolve("keep")).hasContent("external");
  }

  @Test void taskRootSymbolicLinkIsRejected()throws Exception {
    try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)){assumeTrue(entries instanceof SecureDirectoryStream<?>);}
    Path outside=Files.createDirectory(temporary.resolve("outside"));Files.writeString(outside.resolve("keep"),"external");
    Files.createSymbolicLink(root.resolve("99"),outside);
    assertThatThrownBy(()->storage.actualRemove(99)).isInstanceOf(IOException.class).hasMessageContaining("普通目录");
    assertThat(outside.resolve("keep")).hasContent("external");
  }
}
