# 🏗️ Project: Cloud-Native Event-Driven Telegram Bot

**Role:** Senior Staff Engineer & Cloud-Native Architect

**Domain:** Nigerian Mobile Data Vending (MTN, Airtel, Glo, 9mobile)

**Standard:** Java 21 / Spring Boot 3.4+ / Spring Modulith

---

## 🛠️ Tech Stack (2026 Standards)


| Component         | Technology                                                    |
| ----------------- | ------------------------------------------------------------- |
| **Backend**       | Java 21, Spring Boot 3.4+, Spring Modulith                    |
| **Messaging**     | Apache Kafka (KRaft mode) via Spring Cloud Stream             |
| **Persistence**   | PostgreSQL + PGVector (RAG) + JDBC Event Publication Registry |
| **AI Layer**      | Spring AI ChatClient, VectorStore, ChatMemory (per chat ID)   |
| **Observability** | Spring Boot Actuator, OpenTelemetry, Grafana/Prometheus/Tempo |
| **Resiliency**    | Spring Retry + Modulith Transactional Outbox (Kafka Failures) |
| **Caching**       | Redis (Session/PIN state) or Caffeine (Local fallback)        |
| **Bot Layer**     | TelegramBots SDK (Webhook/Long-polling)                       |

---

## ⚖️ Strict Design Rules

1. **Zero-Trust Auth:** No billing or data action can execute without a confirmed `UserSessionUnlocked` event for that chat ID.
2. **Transactional Integrity:** Every method publishing a domain event must be annotated with `@Transactional`. Events are stored in `event_publication` before Kafka forwarding.
3. **Modulithic Boundaries:** Strict package separation enforced:

* `com.yourapp.identity`: PIN validation, session management.
* `com.yourapp.billing`: Wallet balance, top-up, deductions.
* `com.yourapp.knowledge`: RAG queries, price lookup, network status.
* `com.yourapp.delivery`: Data provisioning APIs, Telegram notifications.
* `com.yourapp.admin`: Metrics, reporting, Grafana feeds.

4. **Observability:** Every Kafka message header must contain **TraceID** and **SpanID** from the active OpenTelemetry context.
5. **AI as Router Only:** The `ChatClient` resolves intent and calls Java `@Tool` methods. It **never** holds wallet state or calls external APIs directly.

---

## 📝 User Stories

### 🔐 Identity & Security (Trust Layer)

* **US.1 [PIN-Gated Access]:** Check Redis for session token. If missing, prompt for 6-digit PIN. Validate via BCrypt in Postgres. Publish `UserSessionUnlocked`.
* **US.2 [Identity Sync]:** Billing Module consumes `UserSessionUnlocked` and caches the authorized chat ID in Redis (15-min TTL).

### 🧠 Knowledge & Product Discovery (RAG Brain)

* **US.3 [Data Plan Inquiry]:** Use `VectorStore` similarity search (k=5) for cheapest plans. Return Telegram `InlineKeyboard`.
* **US.4 [Network Status]:** AI calls `@Tool checkNetworkStatus` to query `network_status_log`.
* **US.5 [FAQ/USSD Help]:** RAG search for balance check codes (e.g., `*312#`).

### 💸 Transactional Layer (Core Business)

* **US.6 [Direct Purchase Intent]:** AI extracts structured payload (Network, Size, Phone, PlanID, Price).

1. `@Tool validatePhoneNetwork`
2. `@Tool checkWalletBalance`
3. `@Tool initiatePurchase` (Atomic: Deduct balance → Publish `DataProvisionRequested`).

* **US.7 [Number-to-Network Validation]:** `NigerianPhoneValidator` prefix check:
* **MTN:** 0803, 0806, 0703, 0706, 0813, 0816, 0903, 0906
* **Airtel:** 0802, 0808, 0708, 0812, 0701, 0902
* **Glo:** 0805, 0807, 0705, 0815, 0905
* **9mobile:** 0809, 0817, 0818, 0908, 0909
* **US.8 [Wallet Top-up]:** AI provides virtual account via `@Tool getVirtualAccount`.

### 🛡️ Reliability & Ops (Modulith Advantage)

* **US.9 [Outbox Pattern]:** If Kafka is down, events stay in `event_publication` (Status: STARTED). Modulith retries every 60s. Alert Admin after 5 failures.
* **US.10 [Real-time Delivery]:** Delivery Module uses `@Retryable` (Exp. backoff) for vendor APIs. Publish `DataDelivered` or `DataDeliveryFailed` (triggers refund).
* **US.11 [Admin Grafana Dashboard]:** Custom Micrometer metrics for sales, failures, wallet top-ups, and RAG latency. Custom `VectorStoreHealthIndicator`.

---

## 🚀 Implementation Deliverables

### 1. Infrastructure (`docker-compose.yml`)

Services: PostgreSQL 16 (+pgvector), Kafka (KRaft), Redis 7, Grafana, Prometheus, Tempo, OTel Collector. Kafka @ `localhost:9092`.

### 2. Dependency Management (`pom.xml`)

Spring Modulith, Spring Cloud Stream Kafka, Spring AI (OpenAI + PGVector), Micrometer Tracing (OTel), TelegramBots SDK.

### 3. Module Structure

Full package tree with `@ApplicationModule` annotations for architectural verification.

### 4. Logic & Integration

* `IdentityService.java`: BCrypt + `ApplicationEventPublisher`.
* `PurchaseOrchestrationTools.java`: AI-facing `@Tool` methods.
* `DeliveryEventConsumer.java`: Kafka consumption + `@Retryable` vendor logic.
* `VectorStoreHealthIndicator.java`: Embedding count validation.
* `application.yml`: Comprehensive config for Modulith, Kafka, and AI layers.

---

**Would you like me to start by generating the `docker-compose.yml` and `pom.xml` to establish the foundation?**
