package com.local.release.miniprogram;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface MiniProgramProjectRepository extends JpaRepository<MiniProgramProject,Long> {
  List<MiniProgramProject> findAllByOrderByCreatedAtDescIdDesc();
}
