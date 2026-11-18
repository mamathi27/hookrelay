package com.mamathi.hookrelay.service;

import com.mamathi.hookrelay.model.*;
import com.mamathi.hookrelay.repository.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

@Component
public class DeliveryScheduler {

    private static final int MAX_RETRIES = 5;
    private static final int CIRCUIT_BREAKER_THRESHOLD = 10;

    private final DeliveryAttemptRepository deliveryRepo;
    private final EventRepository eventRepo;
    private final SubscriptionRepository subscriptionRepo;
    private final HmacSigner hmacSigner;
    private final RestClient restClient;

    public DeliveryScheduler(DeliveryAttemptRepository deliveryRepo,
                             EventRepository eventRepo,
                             SubscriptionRepository subscriptionRepo,
                             HmacSigner hmacSigner) {
        this.deliveryRepo = deliveryRepo;
        this.eventRepo = eventRepo;
        this.subscriptionRepo = subscriptionRepo;
        this.hmacSigner = hmacSigner;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedDelay = 1000)
    public void processPendingDeliveries() {
        List<DeliveryAttempt> due = deliveryRepo.findByStatusInAndNextRetryAtLessThanEqual(
            List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), Instant.now()
        );
        for (DeliveryAttempt attempt : due) {
            dispatch(attempt);
        }
    }

    public void dispatch(DeliveryAttempt attempt) {
        WebhookEvent event = eventRepo.findById(attempt.getEventId()).orElse(null);
        WebhookSubscription sub = subscriptionRepo.findById(attempt.getSubscriptionId()).orElse(null);
        if (event == null || sub == null) return;

        long timestamp = Instant.now().getEpochSecond();
        String signature = hmacSigner.sign(sub.getSecretKey(), timestamp, event.getPayload());
        long startNanos = System.nanoTime();

        attempt.setAttemptCount(attempt.getAttemptCount() + 1);
        attempt.setUpdatedAt(Instant.now());

        try {
            ResponseEntity<Void> response = restClient.post()
                .uri(sub.getTargetUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Webhook-Id", attempt.getId().toString())
                .header("X-Webhook-Event", event.getEventType())
                .header("X-Webhook-Timestamp", String.valueOf(timestamp))
                .header("X-Webhook-Signature", signature)
                .body(event.getPayload())
                .retrieve()
                .toBodilessEntity();

            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            attempt.setStatus(DeliveryStatus.DELIVERED);
            attempt.setLastHttpStatus(response.getStatusCode().value());
            attempt.setLatencyMs(latencyMs);
            attempt.setLastError(null);

            sub.setConsecutiveFailures(0);
            subscriptionRepo.save(sub);
        } catch (Exception ex) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            attempt.setLatencyMs(latencyMs);
            attempt.setLastError(ex.getMessage());

            int failures = sub.getConsecutiveFailures() + 1;
            sub.setConsecutiveFailures(failures);
            if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
                sub.setActive(false);
            }
            subscriptionRepo.save(sub);

            if (attempt.getAttemptCount() >= MAX_RETRIES) {
                attempt.setStatus(DeliveryStatus.DEAD_LETTER);
            } else {
                long backoffSeconds = (long) Math.pow(2, attempt.getAttemptCount());
                attempt.setStatus(DeliveryStatus.RETRYING);
                attempt.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));
            }
        }
        deliveryRepo.save(attempt);
    }
}