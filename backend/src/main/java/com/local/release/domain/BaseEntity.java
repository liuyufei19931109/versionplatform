package com.local.release.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@MappedSuperclass
public abstract class BaseEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(nullable=false, updatable=false) private LocalDateTime createdAt;
  @Column(nullable=false) private LocalDateTime updatedAt;
  @PrePersist void created(){ createdAt=updatedAt=LocalDateTime.now(); }
  @PreUpdate void updated(){ updatedAt=LocalDateTime.now(); }
  public Long getId(){ return id; }
  public LocalDateTime getCreatedAt(){ return createdAt; }
  public LocalDateTime getUpdatedAt(){ return updatedAt; }
}
