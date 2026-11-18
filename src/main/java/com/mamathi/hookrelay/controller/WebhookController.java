package com.mamathi.hookrelay.controller;

import com.mamathi.hookrelay.model.DeliveryAttempt;
import com.mamathi.hookrelay.model.DeliveryStatus;
import com.mamathi.hookrelay.model.WebhookSubscription;
import com.mamathi.hookrelay.service.WebhookService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<WebhookSubscription> registerSubscription(@Valid @RequestBody WebhookSubscription sub) {
        return ResponseEntity.ok(webhookService.createSubscription(sub));
    }

    @GetMapping("/subscriptions")
    public List<WebhookSubscription> getSubscriptions() {
        return webhookService.listSubscriptions();
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> publishEvent(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam String eventType,
            @RequestBody String payload) {
        return ResponseEntity.ok(webhookService.ingestEvent(idempotencyKey, eventType, payload));
    }

    @GetMapping("/deliveries")
    public List<DeliveryAttempt> listDeliveries(@RequestParam(required = false) DeliveryStatus status) {
        return webhookService.getDeliveriesByStatus(status);
    }

    @PostMapping("/deliveries/{id}/replay")
    public ResponseEntity<DeliveryAttempt> replay(@PathVariable UUID id) {
        return ResponseEntity.ok(webhookService.replayDelivery(id));
    }
}
