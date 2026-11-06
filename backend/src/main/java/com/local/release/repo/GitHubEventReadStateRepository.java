package com.local.release.repo;
import com.local.release.domain.GitHubEventReadState;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface GitHubEventReadStateRepository extends JpaRepository<GitHubEventReadState,Long> {
  List<GitHubEventReadState> findByUserIdAndEventIdIn(Long userId,Collection<Long> eventIds);
}
