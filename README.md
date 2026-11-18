# HookRelay — Reliable Webhook Delivery & Retry Engine

A Spring Boot backend service for reliable, authenticated, and idempotent event-driven webhook delivery across microservices.

## Key Engineering Features
- **Idempotent Event Ingestion:** Deduplicates incoming webhook events via unique `Idempotency-Key` headers and database indexing (`idx_idempotency_key`), preventing duplicate downstream side effects.
- **HMAC-SHA256 Payload Signing:** Signs every outbound webhook payload (`timestamp + "." + payload`) using `HmacSHA256` (`X-Webhook-Signature` and `X-Webhook-Timestamp` headers) to prevent spoofing and replay attacks.
- **Exponential Backoff & Dead-Letter Queue (DLQ):** Background `@Scheduled` worker retries failed deliveries (`2^attempt` seconds) up to 5 attempts before routing persistent failures to `DEAD_LETTER` status with a manual replay endpoint (`POST /api/v1/deliveries/{id}/replay`).
- **Automatic Circuit Breaker:** Automatically pauses subscriptions after 10 consecutive endpoint failures to protect worker throughput.

## REST API Endpoints
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/v1/subscriptions` | Register a subscriber callback URL, event type, and HMAC secret |
| `GET` | `/api/v1/subscriptions` | List all registered webhook subscriptions |
| `POST` | `/api/v1/events` | Ingest an event with `Idempotency-Key` header |
| `GET` | `/api/v1/deliveries?status=DEAD_LETTER` | Query delivery attempts by status (`PENDING`, `DELIVERED`, `RETRYING`, `DEAD_LETTER`) |
| `POST` | `/api/v1/deliveries/{id}/replay` | Re-queue a failed/dead-lettered delivery and reset circuit breaker |

## Running Locally & Tests
```bash
./mvnw test
./mvnw spring-boot:run