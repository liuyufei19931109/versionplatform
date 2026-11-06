package com.local.release.service;

import com.local.release.domain.*;
import com.local.release.repo.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import java.time.LocalDateTime;
import java.util.List;
import static com.local.release.domain.ModelEnums.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.ANY)
class TaskActivityPersistenceTest {
  @Autowired ReleaseTaskRepository tasks;
  @Autowired RuntimeOperationRepository operations;

  @Test void recentQueriesAreOwnerScopedBoundedTerminalOnlyAndUseCompletionTimeRatherThanTaskId(){
    LocalDateTime now=LocalDateTime.of(2026,10,18,10,0),since=now.minusDays(1);
    List<TaskStatus> terminal=List.of(TaskStatus.SUCCESS,TaskStatus.FAILED,TaskStatus.CANCELLED);
    ReleaseTask retried=saveTask("operator",TaskStatus.FAILED,now.minusMinutes(20));
    ReleaseTask newer=saveTask("operator",TaskStatus.SUCCESS,now.minusMinutes(2));
    saveTask("other",TaskStatus.SUCCESS,now);
    saveTask("operator",TaskStatus.SUCCESS,now.minusDays(2));
    saveTask("operator",TaskStatus.RUNNING,now);
    saveTask("operator",TaskStatus.WAITING_CONFIRMATION,now);
    assertThat(tasks.findTop50ByCreatedByAndStatusInAndFinishedAtAfterOrderByFinishedAtDescIdDesc("operator",terminal,since))
      .extracting(ReleaseTask::getId).containsExactly(newer.getId(),retried.getId());
    retried.setFinishedAt(now);retried.setStatus(TaskStatus.SUCCESS);tasks.saveAndFlush(retried);
    assertThat(tasks.findTop50ByCreatedByAndStatusInAndFinishedAtAfterOrderByFinishedAtDescIdDesc("operator",terminal,since))
      .extracting(ReleaseTask::getId).containsExactly(retried.getId(),newer.getId());
    for(int i=0;i<51;i++)saveTask("operator",TaskStatus.SUCCESS,now.plusSeconds(i+1));
    assertThat(tasks.findTop50ByCreatedByAndStatusInAndFinishedAtAfterOrderByFinishedAtDescIdDesc("operator",terminal,since)).hasSize(50);

    RuntimeOperation first=saveOperation("operator",TaskStatus.SUCCESS,now.minusSeconds(30),"first");
    RuntimeOperation last=saveOperation("operator",TaskStatus.CANCELLED,now.minusSeconds(2),"last");
    saveOperation("other",TaskStatus.SUCCESS,now,"foreign");
    saveOperation("operator",TaskStatus.FAILED,now.minusDays(2),"old");
    saveOperation("operator",TaskStatus.PENDING,now,"pending");
    assertThat(operations.findTop50ByCreatedByAndStatusInAndFinishedAtAfterOrderByFinishedAtDescIdDesc("operator",terminal,since))
      .extracting(RuntimeOperation::getId).containsExactly(last.getId(),first.getId());
  }

  private ReleaseTask saveTask(String owner,TaskStatus status,LocalDateTime finish){
    ReleaseTask task=new ReleaseTask(1L,TaskKind.BUILD,null,"build","feature/example","",false);
    task.setCreatedBy(owner);task.setStatus(status);task.setFinishedAt(finish);return tasks.saveAndFlush(task);
  }
  private RuntimeOperation saveOperation(String owner,TaskStatus status,LocalDateTime finish,String key){
    RuntimeOperation operation=new RuntimeOperation(1L,1L,1L,"service","service","test","node","192.0.2.1",owner,key,"fingerprint");
    operation.setStatus(status);operation.setFinishedAt(finish);return operations.saveAndFlush(operation);
  }
}
