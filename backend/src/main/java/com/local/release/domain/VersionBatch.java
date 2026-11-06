package com.local.release.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

/** A daily coordination record; independent from build artifacts and deployment environments. */
@Entity @Table(name="version_batch", uniqueConstraints=@UniqueConstraint(name="uk_version_batch_date",columnNames="versionDate"))
public class VersionBatch extends BaseEntity {
  @Column(nullable=false) private LocalDate versionDate;
  @Column(nullable=false,length=40) private String branch;
  @Column(nullable=false,length=64) private String createdBy;
  protected VersionBatch() {}
  public VersionBatch(LocalDate date,String branch,String operator){this.versionDate=date;this.branch=branch;this.createdBy=operator;}
  public LocalDate getVersionDate(){return versionDate;}
  public String getBranch(){return branch;}
  public String getCreatedBy(){return createdBy;}
}
