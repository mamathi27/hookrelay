package com.mamathi.hookrelay.repository;

import com.mamathi.hookrelay.model.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {
    List<WebhookSubscription> findByEventTypeAndActiveTrue(String eventType);
}