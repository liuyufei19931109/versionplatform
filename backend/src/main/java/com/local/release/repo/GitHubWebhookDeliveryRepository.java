package com.local.release.repo;
import com.local.release.domain.GitHubWebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface GitHubWebhookDeliveryRepository extends JpaRepository<GitHubWebhookDelivery,Long> {
  Optional<GitHubWebhookDelivery> findByDeliveryId(String deliveryId);
  List<GitHubWebhookDelivery> findTop30ByRepositoryIdentityAndBranchAndEventShaOrderByIdDesc(String identity,String branch,String sha);
  List<GitHubWebhookDelivery> findTop100ByStatusOrderByIdDesc(String status);
}
