package com.local.release.repo;
import com.local.release.domain.VersionPreview;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface VersionPreviewRepository extends JpaRepository<VersionPreview,Long> {
  Optional<VersionPreview> findByToken(String token);
}
