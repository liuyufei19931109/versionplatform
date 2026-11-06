package com.local.release.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import static com.local.release.domain.ModelEnums.*;

@Entity @Table(name="release_step",uniqueConstraints=@UniqueConstraint(columnNames={"releaseTaskId","stepType"}))
public class ReleaseStep extends BaseEntity {
  @Column(nullable=false) private Long releaseTaskId;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private StepType stepType;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private StepStatus status=StepStatus.WAITING;
  @Lob @Column(columnDefinition="LONGTEXT") private String logText="";
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  protected ReleaseStep(){}
  public ReleaseStep(Long taskId,StepType type){releaseTaskId=taskId;stepType=type;}
  public Long getReleaseTaskId(){return releaseTaskId;} public StepType getStepType(){return stepType;}
  public StepStatus getStatus(){return status;} public void setStatus(StepStatus v){status=v;}
  public String getLogText(){return logText;} public void setLogText(String v){logText=v;}
  public void appendLog(String v){logText=(logText==null?"":logText)+v+System.lineSeparator();}
  public LocalDateTime getStartedAt(){return startedAt;} public void setStartedAt(LocalDateTime v){startedAt=v;}
  public LocalDateTime getFinishedAt(){return finishedAt;} public void setFinishedAt(LocalDateTime v){finishedAt=v;}
}
