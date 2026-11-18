package com.mamathi.hookrelay.repository;

import com.mamathi.hookrelay.model.DeliveryAttempt;
import com.mamathi.hookrelay.model.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {
    List<DeliveryAttempt> findByStatusInAndNextRetryAtLessThanEqual(List<DeliveryStatus> statuses, Instant now);
    List<DeliveryAttempt> findByStatus(DeliveryStatus status);
    List<DeliveryAttempt> findByEventId(UUID eventId);
}