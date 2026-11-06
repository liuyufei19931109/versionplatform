package com.local.release.repo;

import com.local.release.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import java.util.List;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class ReleaseHistoryRepositoryTest {
  @Autowired ReleaseTaskRepository tasks;
  @Autowired ServiceEnvironmentRepository environments;
  @Autowired TestEntityManager entityManager;

  @Test void environmentQueryExcludesOtherEnvironmentsBuildsAndSimulationsButRetainsFailures() {
    ReleaseTask success = task("test", TaskKind.DEPLOY, false, TaskStatus.SUCCESS);
    ReleaseTask failed = task("test", TaskKind.DEPLOY, false, TaskStatus.FAILED);
    ReleaseTask cancelled = task("test", TaskKind.DEPLOY, false, TaskStatus.CANCELLED);
    task("prod", TaskKind.DEPLOY, false, TaskStatus.SUCCESS);
    task("dev", TaskKind.DEPLOY, false, TaskStatus.SUCCESS);
    task("test", TaskKind.BUILD, false, TaskStatus.SUCCESS);
    task("test", TaskKind.DEPLOY, true, TaskStatus.SUCCESS);
    entityManager.clear();
    assertThat(tasks.findByTaskKindAndEnvironmentNameAndSimulateFalse(TaskKind.DEPLOY, "test"))
      .extracting(ReleaseTask::getId).containsExactlyInAnyOrder(success.getId(), failed.getId(), cancelled.getId());
  }

  @Test void fetchesBoundNodesAndEmptyConfigurationsInOneEnvironment() {
    config(1L, "test", List.of(3L, 4L)); config(2L, "test", List.of()); config(1L, "prod", List.of(5L));
    entityManager.clear();
    List<ServiceEnvironment> found = environments.findHistoryConfigurations("test");
    entityManager.clear();
    assertThat(found).hasSize(2).allSatisfy(config -> assertThat(config.getEnvironmentName()).isEqualTo("test"));
    assertThat(found.stream().filter(config -> config.getServiceId().equals(1L)).findFirst().orElseThrow().getServerIds())
      .containsExactlyInAnyOrder(3L, 4L);
    assertThat(found.stream().filter(config -> config.getServiceId().equals(2L)).findFirst().orElseThrow().getServerIds()).isEmpty();
  }

  @Test void deploymentReviewReadsOnlyRealServerDeploymentsForRequestedServiceAndKeepsLegacyArtifactIds() {
    var artifact=entityManager.persistAndFlush(new Artifact(35L,99L,"v1","main","a".repeat(40),"image:v1"));
    var current=new ReleaseTask(35L,TaskKind.DEPLOY,artifact.getId(),"prod","main","",false);
    current.setTargetServerIds(List.of(4L,5L)); current.setCreatedBy("yuan"); current.setStatus(TaskStatus.SUCCESS);
    tasks.saveAndFlush(current);
    var legacy=task("dev",TaskKind.DEPLOY,false,TaskStatus.FAILED);
    var mobile=task("prod",TaskKind.DEPLOY,false,TaskStatus.SUCCESS);mobile.setDeliveryType("MOBILE");tasks.saveAndFlush(mobile);
    task("test",TaskKind.DEPLOY,true,TaskStatus.SUCCESS);
    task("test",TaskKind.BUILD,false,TaskStatus.SUCCESS);
    tasks.saveAndFlush(new ReleaseTask(36L,TaskKind.DEPLOY,artifact.getId(),"test","main","",false));
    entityManager.clear();
    var result=tasks.deploymentEvents(35L,TaskKind.DEPLOY);
    assertThat(result).extracting(DeploymentEvent::taskId).containsExactlyInAnyOrder(current.getId(),legacy.getId());
    var event=result.stream().filter(e->current.getId().equals(e.taskId())).findFirst().orElseThrow();
    assertThat(event.buildTaskId()).isEqualTo(99L);
    assertThat(event.targets()).isEqualTo("4,5");
    assertThat(event.actor()).isEqualTo("yuan");
  }

  private ReleaseTask task(String environment, TaskKind kind, boolean simulate, TaskStatus status) {
    ReleaseTask task = new ReleaseTask(35L, kind, 10L, environment, "main", "in-memory only", simulate);
    task.setStatus(status); return tasks.saveAndFlush(task);
  }
  private void config(Long serviceId, String environment, List<Long> nodes) {
    ServiceEnvironment config = new ServiceEnvironment(); config.setServiceId(serviceId); config.setEnvironmentName(environment); config.setServerIds(nodes);
    environments.saveAndFlush(config);
  }
}
