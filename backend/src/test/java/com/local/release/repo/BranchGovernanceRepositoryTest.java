package com.local.release.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.release.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
class BranchGovernanceRepositoryTest {
  @Autowired BranchCheckRepository checks;@Autowired ProductionMergeRepository merges;@Autowired ReleaseTaskRepository tasks;
  @Test void preservesEachAttemptFindsFirstProvenanceAndDoesNotExposeSecrets()throws Exception{
    BranchCheck first=new BranchCheck(10L,1L,"a".repeat(40));first.complete("b".repeat(40),BranchCheck.Status.PASSED,"pass","encrypted-private-url","identity-first");first=checks.saveAndFlush(first);
    BranchCheck later=new BranchCheck(10L,1L,"a".repeat(40));later.complete("c".repeat(40),BranchCheck.Status.BLOCKED,"blocked","other","identity-first");later=checks.saveAndFlush(later);
    assertThat(checks.findByTaskIdOrderByIdDesc(10L)).hasSize(2);assertThat(checks.findFirstByTaskIdAndStatusOrderByIdAsc(10L,BranchCheck.Status.PASSED)).get().extracting(BranchCheck::getId).isEqualTo(first.getId());
    assertThat(checks.findFirstByTaskIdOrderByIdDesc(10L)).get().extracting(BranchCheck::getId).isEqualTo(later.getId());
    String json=new ObjectMapper().findAndRegisterModules().writeValueAsString(first);assertThat(json).doesNotContain("encrypted-private-url","repositoryEncrypted","repositoryIdentity","identity-first");
  }
  @Test void pendingRecoveryOnlyFindsExplicitNewSuccessFlagsAndMergeIsIndependent(){
    ReleaseTask old=new ReleaseTask(1L,ModelEnums.TaskKind.DEPLOY,3L,"prod","release",null,false);old.setStatus(ModelEnums.TaskStatus.SUCCESS);tasks.saveAndFlush(old);
    ReleaseTask recent=new ReleaseTask(1L,ModelEnums.TaskKind.DEPLOY,3L,"prod","release",null,false);recent.setStatus(ModelEnums.TaskStatus.SUCCESS);recent.setMergeEnqueuePending(true);recent=tasks.saveAndFlush(recent);
    assertThat(tasks.findTop50ByMergeEnqueuePendingTrueOrderByIdAsc()).extracting(ReleaseTask::getId).containsExactly(recent.getId());
    ProductionMerge merge=merges.saveAndFlush(new ProductionMerge(recent.getId(),1L,"a".repeat(40)));merge.update(ProductionMerge.Status.NEEDS_ATTENTION,"DIVERGED","manual merge");merges.saveAndFlush(merge);
    assertThat(merges.findByReleaseTaskId(recent.getId())).get().extracting(ProductionMerge::getStatus).isEqualTo(ProductionMerge.Status.NEEDS_ATTENTION);assertThat(tasks.findById(recent.getId())).get().extracting(ReleaseTask::getStatus).isEqualTo(ModelEnums.TaskStatus.SUCCESS);
  }
}
