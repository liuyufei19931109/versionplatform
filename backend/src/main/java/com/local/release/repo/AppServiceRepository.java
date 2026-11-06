package com.local.release.repo;
import com.local.release.domain.AppService; import org.springframework.data.jpa.repository.JpaRepository;
public interface AppServiceRepository extends JpaRepository<AppService,Long>{ boolean existsByServiceKey(String key); }
