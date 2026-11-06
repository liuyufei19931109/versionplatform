package com.local.release.service;

import com.local.release.domain.ModelEnums.StepType;
import com.local.release.domain.ModelEnums.TaskStatus;
import com.local.release.repo.DeploymentEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.local.release.domain.ModelEnums.TaskStatus.*;
import static com.local.release.service.DeploymentReview.Action.*;
import static com.local.release.service.DeploymentReview.State.*;

class DeploymentReviewTest {
  private static DeploymentEvent event(long id,long artifact,String environment,String targets,TaskStatus status) {
    var time=LocalDateTime.of(2026,11,6,10,0).plusMinutes(id);
    return new DeploymentEvent(id,artifact,artifact+100,environment,targets,"operator",status,
        status==FAILED?StepType.START_SERVICE:null,time,status==PENDING?null:time,status==PENDING||status==RUNNING?null:time);
  }
  private static DeploymentReview.Result review(List<Long> targets,DeploymentEvent... history) {
    return DeploymentReview.evaluate(1L,10L,"prod",targets,List.of(history));
  }

  @Test void sameArtifactCanHaveMultipleEnvironmentSuccessesAndFailedLatestAttempt() {
    var result=DeploymentReview.history(List.of(event(1,10,"test","1",SUCCESS),event(2,10,"prod","2",SUCCESS),event(3,10,"test","1",FAILED)));
    assertThat(result).hasSize(2);
    var pre=result.stream().filter(h->h.environment().equals("test")).findFirst().orElseThrow();
    assertThat(pre.latestSuccess().taskId()).isEqualTo(1L);
    assertThat(pre.latestAttempt().status()).isEqualTo(FAILED);
  }

  @Test void differentNodesReceiveIndependentFirstChangeRepeatAndRollbackResults() {
    var history=List.of(event(1,10,"prod","1,2",SUCCESS),event(2,20,"prod","2,3",SUCCESS),event(3,10,"test","4",SUCCESS));
    var result=DeploymentReview.evaluate(7L,10L,"prod",List.of(4L,1L,2L,3L),history);
    assertThat(result.action()).isEqualTo(CONFIRM);
    assertThat(result.nodes()).extracting(DeploymentReview.Node::state).containsExactly(REPEAT,ROLLBACK,CHANGE,FIRST);
    assertThat(result.nodes().get(1).latestSuccess().buildTaskId()).isEqualTo(120L);
    assertThatThrownBy(()->result.verify(null)).isInstanceOf(DeploymentReview.Conflict.class);
    result.verify(result.confirmationToken());
  }

  @Test void pendingOverlapBlocksOnlyMatchingArtifactAndNodesAndCannotBeAcknowledged() {
    var job=event(12,10,"prod","2,3",PENDING);
    var result=review(List.of(1L,2L),job,event(13,20,"prod","1",RUNNING),event(14,10,"test","1",RUNNING));
    assertThat(result.activeTasks()).extracting(DeploymentReview.Stamp::taskId).containsExactly(12L);
    assertThat(result.exactActiveTask(List.of(1L,2L))).isEmpty();
    assertThat(result.exactActiveTask(List.of(3L,2L))).contains(12L);
    assertThatThrownBy(()->result.verify(null)).isInstanceOf(DeploymentReview.Conflict.class);
    assertThat(review(List.of(1L),job).action()).isEqualTo(ALLOW);
  }

  @Test void incompleteOrMalformedNodeHistoryCannotBeUsedAsEvidenceOfNodeSuccess() {
    var failed=event(2,10,"prod","1,2",FAILED);
    var malformed=event(3,20,"prod","1,bad",SUCCESS);
    var old=event(1,10,"prod","1",SUCCESS);
    var partial=review(List.of(1L,2L,3L),old,failed);
    assertThat(partial.nodes()).extracting(DeploymentReview.Node::state).containsExactly(UNKNOWN,UNKNOWN,FIRST);
    assertThat(partial.nodes().get(1).latestSuccess()).isNull();
    var legacy=review(List.of(3L),old,malformed);
    assertThat(legacy.nodes().getFirst().state()).isEqualTo(UNKNOWN);
    assertThat(legacy.nodes().getFirst().latestSuccess()).isNull();
    assertThat(legacy.nodes().getFirst().latestAttempt().scopeKnown()).isFalse();
    var knownAgain=review(List.of(3L),malformed,event(4,10,"prod","3",SUCCESS));
    assertThat(knownAgain.nodes().getFirst().state()).isEqualTo(REPEAT);
  }

  @Test void confirmationBoundToTargetNodesAndLatestSuccessfulRecord() {
    var first=event(1,10,"prod","1,2",SUCCESS);
    var issued=review(List.of(2L,1L),first).confirmationToken();
    assertThat(issued).hasSize(64);
    review(List.of(1L,2L),first).verify(issued);
    assertThatThrownBy(()->review(List.of(1L),first).verify(issued)).isInstanceOf(DeploymentReview.Conflict.class);
    assertThatThrownBy(()->review(List.of(1L,2L),first,event(2,20,"prod","2",SUCCESS)).verify(issued))
        .isInstanceOf(DeploymentReview.Conflict.class);
    assertThatThrownBy(()->review(List.of(1L,2L),first,event(2,10,"prod","1",RUNNING)).verify(issued))
        .isInstanceOf(DeploymentReview.Conflict.class);
  }
}
