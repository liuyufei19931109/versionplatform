package com.local.release.repo;

import com.local.release.domain.*;
import com.local.release.domain.ModelEnums.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface GitHubPushEventRepository extends JpaRepository<GitHubPushEvent,Long> {
  /** Filter before LIMIT so another service's events can never crowd out this user's latest five. */
  @Query("""
    select e from GitHubPushEvent e where e.repositoryFullName in :repositories
    and (e.branch <> 'master' or (e.receivedAt <= :masterBefore and e.repositoryIdentity not in :pendingRepositories
      and not exists (select m.id from ProductionMerge m, ReleaseTask t
        where m.repositoryIdentity = e.repositoryIdentity and m.baselineBefore = e.beforeSha and m.commitId = e.commitId
        and m.status in :mergedStatuses and t.id = m.releaseTaskId and t.taskKind = :deployKind and t.status = :successStatus
        and t.simulate = false and t.environmentName = 'prod' and t.serviceId = m.serviceId and t.commitId = m.commitId)))
    order by e.receivedAt desc, e.id desc
    """)
  List<GitHubPushEvent> latestVisible(@Param("repositories") Collection<String> repositories,@Param("masterBefore") Instant masterBefore,
    @Param("pendingRepositories") Collection<String> pendingRepositories,@Param("mergedStatuses") Collection<ProductionMerge.Status> statuses,
    @Param("deployKind") TaskKind deployKind,@Param("successStatus") TaskStatus successStatus,Pageable page);
}
