package com.mamathi.hookrelay;

import com.mamathi.hookrelay.model.*;
import com.mamathi.hookrelay.repository.*;
import com.mamathi.hookrelay.service.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HookRelayIntegrationTest {

    @Autowired private WebhookService webhookService;
    @Autowired private DeliveryScheduler deliveryScheduler;
    @Autowired private DeliveryAttemptRepository deliveryRepo;
    @Autowired private SubscriptionRepository subscriptionRepo;
    @Autowired private EventRepository eventRepo;
    @Autowired private HmacSigner hmacSigner;

    private HttpServer mockReceiver;
    private int receiverPort;

    @BeforeEach
    void setUp() throws Exception {
        deliveryRepo.deleteAll();
        eventRepo.deleteAll();
        subscriptionRepo.deleteAll();
        mockReceiver = HttpServer.create(new InetSocketAddress(0), 0);
        receiverPort = mockReceiver.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (mockReceiver != null) mockReceiver.stop(0);
    }

    @Test
    void deliversSignedWebhookAndVerifiesHmac() {
        AtomicReference<String> receivedSig = new AtomicReference<>();
        AtomicReference<String> receivedTs = new AtomicReference<>();

        mockReceiver.createContext("/webhook", exchange -> {
            receivedSig.set(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
            receivedTs.set(exchange.getRequestHeaders().getFirst("X-Webhook-Timestamp"));
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) { os.write("OK".getBytes(StandardCharsets.UTF_8)); }
        });
        mockReceiver.start();

        WebhookSubscription sub = new WebhookSubscription();
        sub.setEventType("order.paid");
        sub.setTargetUrl("http://localhost:" + receiverPort + "/webhook");
        sub.setSecretKey("super_secret_key");
        webhookService.createSubscription(sub);

        String payload = "{\"orderId\":101,\"amount\":499}";
        webhookService.ingestEvent("idem-001", "order.paid", payload);
        deliveryScheduler.processPendingDeliveries();

        List<DeliveryAttempt> deliveries = deliveryRepo.findAll();
        assertEquals(1, deliveries.size());
        assertEquals(DeliveryStatus.DELIVERED, deliveries.get(0).getStatus());

        String expectedSig = hmacSigner.sign("super_secret_key", Long.parseLong(receivedTs.get()), payload);
        assertEquals(expectedSig, receivedSig.get());
    }

    @Test
    void deduplicatesEventsByIdempotencyKey() {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setEventType("user.created");
        sub.setTargetUrl("http://localhost:" + receiverPort + "/noop");
        sub.setSecretKey("sec");
        webhookService.createSubscription(sub);

        Map<String, Object> first = webhookService.ingestEvent("dup-key-99", "user.created", "{\"id\":1}");
        Map<String, Object> second = webhookService.ingestEvent("dup-key-99", "user.created", "{\"id\":1}");

        assertEquals(false, first.get("deduplicated"));
        assertEquals(true, second.get("deduplicated"));
        assertEquals(1, deliveryRepo.count());
    }

    @Test
    void retriesWithExponentialBackoffAndMovesToDeadLetter() {
        AtomicInteger calls = new AtomicInteger(0);
        mockReceiver.createContext("/flaky", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
        });
        mockReceiver.start();

        WebhookSubscription sub = new WebhookSubscription();
        sub.setEventType("payment.failed");
        sub.setTargetUrl("http://localhost:" + receiverPort + "/flaky");
        sub.setSecretKey("sec");
        webhookService.createSubscription(sub);

        webhookService.ingestEvent("fail-key-1", "payment.failed", "{\"err\":true}");
        DeliveryAttempt attempt = deliveryRepo.findAll().get(0);

        for (int i = 1; i <= 5; i++) {
            deliveryScheduler.dispatch(attempt);
            attempt = deliveryRepo.findById(attempt.getId()).orElseThrow();
        }

        assertEquals(5, calls.get());
        assertEquals(DeliveryStatus.DEAD_LETTER, attempt.getStatus());

        DeliveryAttempt replayed = webhookService.replayDelivery(attempt.getId());
        assertEquals(DeliveryStatus.PENDING, replayed.getStatus());
        assertEquals(0, replayed.getAttemptCount());
    }

    @Test
    void benchmarkIngestionLatency() {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setEventType("bench.event");
        sub.setTargetUrl("http://localhost:" + receiverPort + "/bench");
        sub.setSecretKey("sec");
        webhookService.createSubscription(sub);

        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            webhookService.ingestEvent("bench-" + i, "bench.event", "{\"index\":" + i + "}");
        }
        double avgMs = ((System.nanoTime() - start) / 1_000_000.0) / 50.0;
        System.out.printf("MEASURED AVG INGESTION LATENCY (50 events): %.2f ms%n", avgMs);
        assertTrue(avgMs >= 0);
    }
}