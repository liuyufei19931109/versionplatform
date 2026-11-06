package com.local.release.repo;

import com.local.release.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
class RuntimeOperationRepositoryTest {
  @Autowired RuntimeOperationRepository operations;
  @Autowired ReleaseTaskRepository tasks;
  @Autowired ArtifactRepository artifacts;
  @Test void restartAuditDoesNotCreateOrChangeBuildReleaseOrArtifactRows() {
    ReleaseTask task=new ReleaseTask(1L,TaskKind.DEPLOY,1L,"test","main","existing deployment",false);task.setStatus(TaskStatus.SUCCESS);
    task=tasks.saveAndFlush(task);
    RuntimeOperation operation=new RuntimeOperation(2L,1L,3L,"ui","ui","test","node","host","admin","request-key","fingerprint");
    operation.setStatus(TaskStatus.SUCCESS);operation.appendLog("health passed");operation=operations.saveAndFlush(operation);
    assertThat(operations.findByRequestKey("request-key")).get().extracting(RuntimeOperation::getId).isEqualTo(operation.getId());
    assertThat(operations.findTop50ByEnvironmentIdAndServerIdOrderByIdDesc(2L,3L)).hasSize(1);
    assertThat(operations.findTop50ByEnvironmentIdAndServerIdOrderByIdDesc(2L,4L)).isEmpty();
    assertThat(tasks.count()).isEqualTo(1);assertThat(tasks.findById(task.getId())).get().extracting(ReleaseTask::getStatus).isEqualTo(TaskStatus.SUCCESS);assertThat(artifacts.count()).isZero();
  }
}
