package com.mamathi.hookrelay.repository;

import com.mamathi.hookrelay.model.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<WebhookEvent, UUID> {
    Optional<WebhookEvent> findByIdempotencyKey(String idempotencyKey);
}