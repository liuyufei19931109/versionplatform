package com.local.release.miniprogram;
import org.springframework.stereotype.Component;
import java.util.List;
@Component
public class MiniProgramActivitySource {
  private final MiniProgramSettings settings;
  private final MiniProgramJobRepository jobs;
  public MiniProgramActivitySource(MiniProgramSettings settings,MiniProgramJobRepository jobs){this.settings=settings;this.jobs=jobs;}
  public List<MiniProgramJob> active(){return settings.enabled()?jobs.findByStatusIn(List.of(MiniProgramJob.Status.PENDING,MiniProgramJob.Status.RUNNING)):List.of();}
  public List<MiniProgramJob> recent(String actor){return settings.enabled()?jobs.findTop20ByCreatedByOrderByCreatedAtDescIdDesc(actor):List.of();}
}
