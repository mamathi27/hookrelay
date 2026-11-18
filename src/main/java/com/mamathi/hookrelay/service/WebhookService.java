package com.mamathi.hookrelay.service;

import com.mamathi.hookrelay.model.*;
import com.mamathi.hookrelay.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class WebhookService {

    private final SubscriptionRepository subscriptionRepo;
    private final EventRepository eventRepo;
    private final DeliveryAttemptRepository deliveryRepo;

    public WebhookService(SubscriptionRepository subscriptionRepo,
                          EventRepository eventRepo,
                          DeliveryAttemptRepository deliveryRepo) {
        this.subscriptionRepo = subscriptionRepo;
        this.eventRepo = eventRepo;
        this.deliveryRepo = deliveryRepo;
    }

    public WebhookSubscription createSubscription(WebhookSubscription sub) {
        return subscriptionRepo.save(sub);
    }

    public List<WebhookSubscription> listSubscriptions() {
        return subscriptionRepo.findAll();
    }

    @Transactional
    public Map<String, Object> ingestEvent(String idempotencyKey, String eventType, String payload) {
        Optional<WebhookEvent> existing = eventRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return Map.of(
                "eventId", existing.get().getId(),
                "deduplicated", true,
                "queuedDeliveries", deliveryRepo.findByEventId(existing.get().getId()).size()
            );
        }

        WebhookEvent event = new WebhookEvent();
        event.setIdempotencyKey(idempotencyKey);
        event.setEventType(eventType);
        event.setPayload(payload);
        event = eventRepo.save(event);

        List<WebhookSubscription> subscribers = subscriptionRepo.findByEventTypeAndActiveTrue(eventType);
        for (WebhookSubscription sub : subscribers) {
            DeliveryAttempt attempt = new DeliveryAttempt();
            attempt.setEventId(event.getId());
            attempt.setSubscriptionId(sub.getId());
            attempt.setStatus(DeliveryStatus.PENDING);
            attempt.setNextRetryAt(Instant.now());
            deliveryRepo.save(attempt);
        }

        return Map.of(
            "eventId", event.getId(),
            "deduplicated", false,
            "queuedDeliveries", subscribers.size()
        );
    }

    public List<DeliveryAttempt> getDeliveriesByStatus(DeliveryStatus status) {
        return status == null ? deliveryRepo.findAll() : deliveryRepo.findByStatus(status);
    }

    @Transactional
    public DeliveryAttempt replayDelivery(UUID deliveryId) {
        DeliveryAttempt attempt = deliveryRepo.findById(deliveryId)
            .orElseThrow(() -> new NoSuchElementException("Delivery not found: " + deliveryId));

        subscriptionRepo.findById(attempt.getSubscriptionId()).ifPresent(sub -> {
            sub.setActive(true);
            sub.setConsecutiveFailures(0);
            subscriptionRepo.save(sub);
        });

        attempt.setStatus(DeliveryStatus.PENDING);
        attempt.setAttemptCount(0);
        attempt.setNextRetryAt(Instant.now());
        attempt.setUpdatedAt(Instant.now());
        return deliveryRepo.save(attempt);
    }
}