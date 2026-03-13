# DataBot NG

> An AI-powered Telegram bot for buying mobile data bundles in Nigeria. Users chat naturally, verify via PIN, and purchase MTN/Airtel/Glo/9mobile data instantly. Built with Java 21, Spring Modulith, Spring AI, Kafka, PostgreSQL + PGVector, and Redis.

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4+-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring Modulith](https://img.shields.io/badge/Spring%20Modulith-event--driven-green)](https://spring.io/projects/spring-modulith)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-RAG%20%2B%20Tools-yellow)](https://spring.io/projects/spring-ai)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-KRaft-black?logo=apachekafka)](https://kafka.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16%20%2B%20PGVector-blue?logo=postgresql)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Database Migrations](#database-migrations)
- [Running the Application](#running-the-application)
- [Running Tests](#running-tests)
- [Observability](#observability)
- [Kafka Topics](#kafka-topics)
- [API — AI Tool Methods](#api--ai-tool-methods)
- [Nigerian Phone Prefix Map](#nigerian-phone-prefix-map)
- [Non-Functional Requirements](#non-functional-requirements)
- [Contributing](#contributing)

---

## Overview

DataBot NG lets Nigerian users buy mobile data directly inside Telegram using plain language. A user types *"Send 2GB MTN to 08012345678"* — the AI parses the intent, validates the phone number against its network, checks the wallet balance, debits atomically, and delivers via the vendor API — all within a single conversation thread.

The system is built on three guarantees:

- **No lost transactions.** Every purchase is written to a Transactional Outbox before Kafka is notified. If Kafka goes down mid-purchase, the event is retried automatically when it recovers.
- **No double charges.** Wallet debits use optimistic locking (`@Version`). A duplicate request is rejected at the database level.
- **No silent failures.** Every failed delivery triggers a refund, a Telegram notification, and a dead-letter entry that alerts the admin.

---

## Features

| # | Feature | Description |
|---|---------|-------------|
| ✅ | PIN-gated sessions | 6-digit PIN verified with BCrypt. Locked for 15 min after 3 failures. Session stored in Redis with 15-min sliding TTL. |
| ✅ | Natural language purchase | Spring AI parses free-text messages and dispatches to typed Java `@Tool` methods. |
| ✅ | RAG price lookup | Price lists and FAQs are embedded with OpenAI `text-embedding-3-small` and stored in PGVector. Similarity search answers plan queries in < 3s. |
| ✅ | Network status check | Real-time UP/DEGRADED/DOWN status per network queried before purchase. |
| ✅ | Wallet top-up via bank | Virtual account per user. Providus/Wema webhook auto-credits the wallet on transfer. HMAC-SHA256 validated. |
| ✅ | Transactional outbox | Spring Modulith JPA outbox guarantees at-least-once Kafka delivery even during broker downtime. |
| ✅ | Vendor retry + refund | `@Retryable(3x, 2s→4s→8s)`. Full wallet refund if all retries fail. User notified either way. |
| ✅ | Grafana dashboard | Micrometer metrics, Prometheus scrape, Tempo traces, pre-built dashboard JSON. |
| ✅ | Admin alerts | Dead-letter Kafka events trigger direct Telegram HTTP alerts to the admin chat. |

---

## Architecture

DataBot NG is a **Spring Modulith monolith** — six bounded contexts in one deployable JVM process, communicating via Kafka events and a shared PostgreSQL database. This gives the simplicity of a monolith with the isolation boundaries of microservices, enforced at compile time by `ApplicationModules.verify()`.

```
┌─────────────────────────────────────────────────────────┐
│                   Spring Boot Application               │
│                                                         │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐             │
│   │ identity │  │ billing  │  │  sales   │             │
│   └──────────┘  └──────────┘  └──────────┘             │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐             │
│   │knowledge │  │ delivery │  │  admin   │             │
│   └──────────┘  └──────────┘  └──────────┘             │
│                                                         │
│   ┌─────────────────────────────────────────────────┐  │
│   │  Transactional Outbox  (event_publication)      │  │
│   └─────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
        │                    │                    │
   PostgreSQL 16         Apache Kafka           Redis 7
   + PGVector            (KRaft, 7 topics)     (session TTL)
```

**Purchase flow in brief:**

1. User sends message → `TelegramBotHandler` → `SessionGuard` checks Redis
2. If no session → PIN prompt → `IdentityService` validates, writes session
3. `ChatClient` (Spring AI) routes intent → `@Tool` methods
4. `OrderService.placeOrder()` debits wallet + saves Order + writes outbox event — all in one `@Transactional`
5. Modulith republisher delivers `DataProvisionRequested` to Kafka after commit
6. `DeliveryEventConsumer` calls vendor API with `@Retryable`
7. On success → `DataDelivered` event → Telegram confirmation
8. On failure → `DataDeliveryFailed` → wallet refunded → Telegram failure message

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 (virtual threads ready) |
| Framework | Spring Boot 3.4+ |
| Modularity | Spring Modulith (bounded context enforcement) |
| AI | Spring AI — GPT-4o chat, `text-embedding-3-small` embeddings |
| Messaging | Apache Kafka KRaft (no ZooKeeper) |
| Outbox | Spring Modulith Events JPA |
| ORM | Spring Data JPA + Hibernate |
| Primary DB | PostgreSQL 16 with PGVector extension |
| Cache | Redis 7 (session state, TTL-based) |
| Migrations | Flyway |
| Telegram | TelegramBots Spring Boot Starter |
| Retry | Spring Retry + AOP |
| Metrics | Micrometer + Prometheus |
| Tracing | OpenTelemetry → Grafana Tempo |
| Dashboards | Grafana |
| Containerisation | Docker Compose |
| Testing | JUnit 5, Spring Modulith Test, Testcontainers |

---

## Project Structure

```
databot-ng/
│
├── pom.xml
├── docker-compose.yml
├── .env.example
├── README.md
│
├── observability/
│   ├── prometheus.yml
│   ├── otel-collector-config.yml
│   └── grafana/
│       ├── datasources/
│       │   └── datasources.yml
│       └── dashboards/
│           └── databot-main.json
│
├── load-tests/
│   └── purchase-flow.js                          # k6 load test script
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── databot/
│   │   │           │
│   │   │           ├── DataBotApplication.java   # @SpringBootApplication entry point
│   │   │           │
│   │   │           ├── shared/                   # Shared kernel — visible to ALL modules
│   │   │           │   ├── Money.java            # @Embeddable value object
│   │   │           │   └── Network.java          # Enum: MTN, AIRTEL, GLO, NINE_MOBILE
│   │   │           │
│   │   │           │
│   │   │           ├── identity/                 # ── MODULE: IDENTITY ──────────────────
│   │   │           │   ├── package-info.java     # @ApplicationModule
│   │   │           │   │
│   │   │           │   ├── domain/               # Aggregate roots & value objects (PRIVATE)
│   │   │           │   │   ├── UserAccount.java  # <<AR>> @Entity — PIN logic lives here
│   │   │           │   │   ├── PinPolicy.java    # <<VO>> @Embeddable
│   │   │           │   │   └── AccountStatus.java# Enum: ACTIVE, LOCKED, SUSPENDED
│   │   │           │   │
│   │   │           │   ├── repository/           # JPA repositories (PRIVATE)
│   │   │           │   │   └── UserAccountRepository.java
│   │   │           │   │
│   │   │           │   ├── service/              # Orchestration services (PRIVATE)
│   │   │           │   │   └── IdentityService.java
│   │   │           │   │
│   │   │           │   ├── event/                # Domain events published to outbox (PUBLIC)
│   │   │           │   │   ├── UserSessionUnlocked.java # record — consumed by billing
│   │   │           │   │   └── AccountLocked.java       # record
│   │   │           │   │
│   │   │           │   ├── dto/                  # Request/response shapes (PUBLIC)
│   │   │           │   │   ├── RegisterRequest.java
│   │   │           │   │   ├── PinValidationResult.java
│   │   │           │   │   └── PhoneValidationResult.java
│   │   │           │   │
│   │   │           │   ├── api/                  # Module's public API surface (PUBLIC)
│   │   │           │   │   └── IdentityApi.java  # Interface — what delivery may call
│   │   │           │   │
│   │   │           │   ├── security/             # Session management (PRIVATE)
│   │   │           │   │   ├── SessionGuard.java # assertSessionActive(chatId)
│   │   │           │   │   └── AdminGuard.java   # assertAdmin(chatId) — chatId-based auth
│   │   │           │   │
│   │   │           │   └── validation/           # Phone validation (PRIVATE)
│   │   │           │       └── NigerianPhoneValidator.java
│   │   │           │
│   │   │           │
│   │   │           ├── billing/                  # ── MODULE: BILLING ───────────────────
│   │   │           │   ├── package-info.java     # @ApplicationModule
│   │   │           │   │
│   │   │           │   ├── domain/               # Aggregate roots & entities (PRIVATE)
│   │   │           │   │   ├── Wallet.java       # <<AR>> @Entity — debit/credit behaviour
│   │   │           │   │   ├── WalletTransaction.java # <<Entity>> owned by Wallet
│   │   │           │   │   ├── VirtualAccount.java    # <<Entity>> owned by Wallet
│   │   │           │   │   ├── WalletStatus.java      # Enum: ACTIVE, FROZEN, CLOSED
│   │   │           │   │   └── TransactionType.java   # Enum: CREDIT_TOPUP, DEBIT_PURCHASE, CREDIT_REFUND
│   │   │           │   │
│   │   │           │   ├── repository/           # JPA repositories (PRIVATE)
│   │   │           │   │   └── WalletRepository.java  # Only AR gets a repo
│   │   │           │   │
│   │   │           │   ├── service/              # Orchestration services (PRIVATE)
│   │   │           │   │   └── BillingService.java    # debit · credit · refund · getBalance
│   │   │           │   │
│   │   │           │   ├── event/                # Domain events (PUBLIC)
│   │   │           │   │   ├── WalletCredited.java    # record
│   │   │           │   │   └── WalletDebited.java     # record
│   │   │           │   │
│   │   │           │   ├── dto/                  # Request/response shapes (PUBLIC)
│   │   │           │   │   ├── WalletBalanceResult.java
│   │   │           │   │   ├── VirtualAccountResult.java
│   │   │           │   │   └── DebitResult.java
│   │   │           │   │
│   │   │           │   ├── api/                  # Module's public API surface (PUBLIC)
│   │   │           │   │   └── BillingApi.java   # Interface — what sales/delivery may call
│   │   │           │   │
│   │   │           │   ├── consumer/             # Kafka consumers (PRIVATE)
│   │   │           │   │   ├── SessionSyncConsumer.java           # identity.events → Redis
│   │   │           │   │   └── DeliveryFailureCompensationConsumer.java # refund on failure
│   │   │           │   │
│   │   │           │   └── controller/           # REST controllers (PRIVATE)
│   │   │           │       └── PaymentWebhookController.java # POST /webhook/payment
│   │   │           │
│   │   │           │
│   │   │           ├── sales/                    # ── MODULE: SALES ─────────────────────
│   │   │           │   ├── package-info.java     # @ApplicationModule
│   │   │           │   │
│   │   │           │   ├── domain/               # Aggregate roots & value objects (PRIVATE)
│   │   │           │   │   ├── Order.java        # <<AR>> @Entity — full state machine
│   │   │           │   │   ├── OrderItem.java    # <<VO>> @Embeddable
│   │   │           │   │   ├── PhoneNumber.java  # <<VO>> @Embeddable nested in OrderItem
│   │   │           │   │   └── OrderStatus.java  # Enum: PENDING, PROVISIONING, DELIVERED, FAILED, REFUNDED, REFUND_PENDING
│   │   │           │   │
│   │   │           │   ├── repository/           # JPA repositories (PRIVATE)
│   │   │           │   │   └── OrderRepository.java
│   │   │           │   │
│   │   │           │   ├── service/              # Orchestration services (PRIVATE)
│   │   │           │   │   └── OrderService.java # placeOrder() @Transactional
│   │   │           │   │
│   │   │           │   ├── event/                # Domain events published to outbox (PUBLIC)
│   │   │           │   │   ├── DataProvisionRequested.java # record — consumed by delivery
│   │   │           │   │   └── PurchaseRejected.java       # record
│   │   │           │   │
│   │   │           │   ├── dto/                  # Request/response shapes (PUBLIC)
│   │   │           │   │   ├── PurchaseRequest.java
│   │   │           │   │   └── PurchaseInitiatedResult.java
│   │   │           │   │
│   │   │           │   ├── api/                  # Module's public API surface (PUBLIC)
│   │   │           │   │   └── SalesApi.java     # Interface — what delivery tools may call
│   │   │           │   │
│   │   │           │   └── tools/                # Spring AI @Tool methods (PUBLIC)
│   │   │           │       └── PurchaseOrchestrationTools.java
│   │   │           │
│   │   │           │
│   │   │           ├── knowledge/                # ── MODULE: KNOWLEDGE ─────────────────
│   │   │           │   ├── package-info.java     # @ApplicationModule
│   │   │           │   │
│   │   │           │   ├── domain/               # Aggregate roots (PRIVATE)
│   │   │           │   │   ├── KnowledgeChunk.java     # <<AR>> @Entity — PGVector embedding
│   │   │           │   │   ├── NetworkStatusEntry.java # <<AR>> @Entity
│   │   │           │   │   ├── KnowledgeNamespace.java # Enum: PRICE_LIST, FAQ, USSD_CODES, NETWORK_GUIDES
│   │   │           │   │   └── NetworkAvailability.java# Enum: UP, DEGRADED, DOWN
│   │   │           │   │
│   │   │           │   ├── repository/           # JPA repositories (PRIVATE)
│   │   │           │   │   ├── KnowledgeChunkRepository.java
│   │   │           │   │   └── NetworkStatusEntryRepository.java
│   │   │           │   │
│   │   │           │   ├── service/              # Orchestration services (PRIVATE)
│   │   │           │   │   ├── KnowledgeService.java          # searchPlans · getNetworkStatus
│   │   │           │   │   └── DocumentIngestionService.java  # @EventListener(AppReadyEvent)
│   │   │           │   │
│   │   │           │   ├── event/                # (empty for now — knowledge fires no events)
│   │   │           │   │
│   │   │           │   ├── dto/                  # Response shapes (PUBLIC)
│   │   │           │   │   ├── NetworkStatusResult.java
│   │   │           │   │   └── PlanSearchResult.java
│   │   │           │   │
│   │   │           │   └── api/                  # Module's public API surface (PUBLIC)
│   │   │           │       └── KnowledgeApi.java # Interface — what delivery tools may call
│   │   │           │
│   │   │           │
│   │   │           ├── delivery/                 # ── MODULE: DELIVERY ──────────────────
│   │   │           │   ├── package-info.java     # @ApplicationModule(allowedDependencies = {"billing", "knowledge"})
│   │   │           │   │
│   │   │           │   ├── bot/                  # Telegram entry point (PRIVATE)
│   │   │           │   │   └── TelegramBotHandler.java  # Routes messages → AI or PIN prompt
│   │   │           │   │
│   │   │           │   ├── config/               # Spring AI wiring (PRIVATE)
│   │   │           │   │   └── ChatClientConfig.java    # ChatClient + ChatMemory + Tools bean
│   │   │           │   │
│   │   │           │   ├── client/               # Vendor HTTP client (PRIVATE)
│   │   │           │   │   └── VendorApiClient.java     # @Retryable(3x, 2s→4s→8s)
│   │   │           │   │
│   │   │           │   ├── consumer/             # Kafka consumers (PRIVATE)
│   │   │           │   │   └── DeliveryEventConsumer.java # sales.provision.requested
│   │   │           │   │
│   │   │           │   ├── service/              # Notification service (PRIVATE)
│   │   │           │   │   └── TelegramNotificationService.java
│   │   │           │   │
│   │   │           │   ├── event/                # Events this module produces (PUBLIC)
│   │   │           │   │   ├── DataDelivered.java
│   │   │           │   │   └── DataDeliveryFailed.java
│   │   │           │   │
│   │   │           │   ├── dto/                  # Internal shapes (PRIVATE)
│   │   │           │   │   └── VendorProvisionRequest.java
│   │   │           │   │
│   │   │           │   └── api/                  # (no public API — delivery is a consumer only)
│   │   │           │
│   │   │           │
│   │   │           └── admin/                    # ── MODULE: ADMIN ─────────────────────
│   │   │               ├── package-info.java     # @ApplicationModule
│   │   │               │
│   │   │               ├── metrics/              # Micrometer instrumentation (PRIVATE)
│   │   │               │   ├── SalesMetricsService.java      # counters + timers
│   │   │               │   └── KafkaPendingEventsGauge.java  # @Scheduled gauge
│   │   │               │
│   │   │               ├── health/               # Actuator health indicators (PRIVATE)
│   │   │               │   ├── VectorStoreHealthIndicator.java
│   │   │               │   └── KafkaHealthIndicator.java
│   │   │               │
│   │   │               ├── service/              # Alert service (PRIVATE)
│   │   │               │   └── DeadLetterAlertService.java   # @Scheduled every 60s
│   │   │               │
│   │   │               ├── controller/           # Admin bot command handler (PRIVATE)
│   │   │               │   └── AdminCommandHandler.java      # /reset_event · /stats
│   │   │               │
│   │   │               ├── event/                # (no events published by admin)
│   │   │               │
│   │   │               ├── dto/                  # Admin response shapes (PRIVATE)
│   │   │               │   └── EventPublicationSummary.java
│   │   │               │
│   │   │               └── api/                  # (no public API surface)
│   │   │
│   │   └── resources/
│   │       ├── application.yml                   # All config — datasource, kafka, redis, AI
│   │       ├── prompts/
│   │       │   └── system-prompt.txt             # Spring AI system prompt for ChatClient
│   │       ├── db/
│   │       │   └── migration/                    # Flyway versioned migrations
│   │       │       ├── V1__create_user_accounts.sql
│   │       │       ├── V2__create_wallets_and_transactions.sql
│   │       │       ├── V3__create_orders.sql
│   │       │       ├── V4__create_knowledge_tables.sql
│   │       │       ├── V5__create_event_publication.sql
│   │       │       └── V6__create_indexes.sql
│   │       └── data/
│   │           └── knowledge/
│   │               ├── prices/
│   │               │   ├── mtn_plans.txt
│   │               │   ├── airtel_plans.txt
│   │               │   ├── glo_plans.txt
│   │               │   └── 9mobile_plans.txt
│   │               └── faq/
│   │                   ├── ussd_codes.txt
│   │                   └── how_to_buy.txt
│   │
│   └── test/
│       └── java/
│           └── com/
│               └── databot/
│                   │
│                   ├── ModulithArchitectureTest.java     # ApplicationModules.verify() — runs on every PR
│                   │
│                   ├── identity/
│                   │   ├── IdentityModuleTest.java       # @ApplicationModuleTest
│                   │   ├── domain/
│                   │   │   └── UserAccountTest.java      # Unit — PIN logic state machine
│                   │   └── validation/
│                   │       └── NigerianPhoneValidatorTest.java
│                   │
│                   ├── billing/
│                   │   ├── BillingModuleTest.java        # @ApplicationModuleTest
│                   │   ├── domain/
│                   │   │   └── WalletTest.java           # Unit — debit/credit behaviour
│                   │   └── WalletConcurrencyTest.java    # Optimistic lock race condition
│                   │
│                   ├── sales/
│                   │   ├── SalesModuleTest.java          # @ApplicationModuleTest — outbox assertion
│                   │   └── domain/
│                   │       └── OrderTest.java            # Unit — state machine transitions
│                   │
│                   ├── knowledge/
│                   │   └── KnowledgeServiceIntegrationTest.java # Testcontainers PG + pgvector
│                   │
│                   └── delivery/
│                       └── DeliveryEventConsumerTest.java       # Mock vendor · DLQ assertion
```

---

## Prerequisites

Ensure the following are installed before continuing:

| Tool | Minimum Version |
|------|----------------|
| Java JDK | 21 |
| Maven | 3.9+ |
| Docker | 24+ |
| Docker Compose | 2.20+ |

You also need:
- An **OpenAI API key** with access to `gpt-4o` and `text-embedding-3-small`
- A **Telegram Bot token** from [@BotFather](https://t.me/BotFather)
- A **Data vendor API key** (Mtech, N-Sure, or equivalent)
- A **Providus/Wema virtual account webhook secret** for wallet top-ups

---

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/your-username/databot-ng.git
cd databot-ng
```

### 2. Copy and fill the environment file

```bash
cp .env.example .env
```

Open `.env` and fill in every value. See [Configuration](#configuration) for details.

### 3. Start all infrastructure services

```bash
docker-compose up -d
```

This starts PostgreSQL 16 (with pgvector), Kafka (KRaft), Redis 7, Prometheus, Grafana Tempo, Grafana, and the OpenTelemetry Collector.

Verify everything is healthy:

```bash
docker-compose ps
```

All services should show `healthy` or `running`. PostgreSQL takes ~10 seconds on first boot to run `CREATE EXTENSION IF NOT EXISTS vector`.

### 4. Run database migrations

Flyway runs automatically on application startup. If you want to run migrations independently:

```bash
mvn flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/databot \
  -Dflyway.user=databot -Dflyway.password=your_password
```

### 5. Build and run

```bash
mvn spring-boot:run
```

The application starts on port `8080`. Check health:

```bash
curl http://localhost:8080/actuator/health
```

Expected response when all dependencies are connected:

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "kafka": { "status": "UP" },
    "redis": { "status": "UP" },
    "vectorStore": { "status": "UP" }
  }
}
```

---

## Configuration

All configuration is driven by environment variables. Copy `.env.example` to `.env` and fill in:

```bash
# ── Telegram ──────────────────────────────────────────
TELEGRAM_BOT_TOKEN=          # Token from @BotFather
TELEGRAM_BOT_USERNAME=       # Bot username without @
TELEGRAM_ADMIN_CHAT_ID=      # Your personal Telegram chat ID (for admin alerts)

# ── OpenAI ────────────────────────────────────────────
OPENAI_API_KEY=              # sk-...

# ── Data Vendor ───────────────────────────────────────
VENDOR_API_BASE_URL=         # https://api.yourvendor.com
VENDOR_API_KEY=              # Vendor API key

# ── Payment Gateway ───────────────────────────────────
PAYMENT_WEBHOOK_SECRET=      # Used to validate HMAC-SHA256 on /webhook/payment

# ── PostgreSQL ────────────────────────────────────────
POSTGRES_PASSWORD=           # Choose a strong password

# ── Redis ─────────────────────────────────────────────
REDIS_PASSWORD=              # Optional — leave blank for local dev
```

> **Never commit `.env` to source control.** It is in `.gitignore` by default.

All Spring configuration lives in `src/main/resources/application.yml`. Environment variables map directly to Spring properties — no hardcoded secrets anywhere in the codebase.

---

## Database Migrations

Migrations are managed by Flyway and live in `src/main/resources/db/migration/`:

| File | Creates |
|------|---------|
| `V1__create_user_accounts.sql` | `user_accounts` table |
| `V2__create_wallets_and_transactions.sql` | `wallets`, `wallet_transactions`, `virtual_accounts` |
| `V3__create_orders.sql` | `orders` table |
| `V4__create_knowledge_tables.sql` | `knowledge_chunks` (vector), `network_status_entries` |
| `V5__create_event_publication.sql` | `event_publication` (Modulith outbox) |
| `V6__create_indexes.sql` | All performance indexes including `ivfflat` on embeddings |

> **Do not edit existing migration files.** If you need a schema change, create a new `V7__...sql` file.

---

## Running the Application

```bash
# Development
mvn spring-boot:run

# Production JAR
mvn clean package -DskipTests
java -jar target/databot-ng-1.0.0.jar

# With JVM tuning (recommended for production)
java -Xms512m -Xmx1g -XX:+UseG1GC \
     -jar target/databot-ng-1.0.0.jar
```

On first startup, `DocumentIngestionService` will embed all knowledge documents from `src/main/resources/data/knowledge/` into PGVector. This runs once — subsequent restarts skip ingestion if embeddings already exist.

---

## Running Tests

```bash
# All tests
mvn test

# Unit tests only (no Docker required)
mvn test -Dtest="*Test" -DexcludedGroups=integration

# Module boundary verification (catches architectural violations)
mvn test -Dtest=ModulithArchitectureTest

# Single module tests
mvn test -Dtest="IdentityModuleTest"
mvn test -Dtest="WalletTest"
mvn test -Dtest="OrderTest"
```

> Integration tests (`KnowledgeServiceIntegrationTest`, `DeliveryEventConsumerTest`) require Docker to be running — they spin up Testcontainers instances of PostgreSQL and pgvector automatically.

### Key test files

| Test | Type | What it verifies |
|------|------|-----------------|
| `ModulithArchitectureTest` | Architecture | No module imports another module's `domain`, `repository`, or `service` |
| `UserAccountTest` | Unit | PIN validation state machine — correct, wrong, lockout, unlock |
| `WalletTest` | Unit | Debit/credit, insufficient funds, optimistic lock conflict |
| `OrderTest` | Unit | State transitions — PENDING → PROVISIONING → DELIVERED/FAILED |
| `NigerianPhoneValidatorTest` | Unit | All 4 networks, all prefixes, mismatch, unknown prefix |
| `IdentityModuleTest` | Modulith | `validatePin()` publishes `UserSessionUnlocked` to outbox |
| `SalesModuleTest` | Modulith | `placeOrder()` debits wallet + saves Order + writes outbox atomically |
| `WalletConcurrencyTest` | Integration | Two threads debit same wallet — one succeeds, one retries |
| `KnowledgeServiceIntegrationTest` | Integration | Ingest document → similarity search → correct result returned |
| `DeliveryEventConsumerTest` | Integration | Happy path + all-retries-failed + refund triggered |

---

## Observability

All observability services start automatically with `docker-compose up -d`.

| Service | URL | Credentials |
|---------|-----|-------------|
| Grafana | http://localhost:3000 | admin / admin |
| Prometheus | http://localhost:9090 | — |
| Grafana Tempo | http://localhost:3200 | — |
| Spring Actuator | http://localhost:8080/actuator | — |

### Custom Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `sales.transactions.total` | Counter | Total purchases, tagged by `network` |
| `sales.transactions.failed` | Counter | Failed purchases, tagged by `failureReason` |
| `wallet.topups.total` | Counter | Wallet credits, tagged by `amountBucket` |
| `rag.queries.latency` | Timer (histogram) | RAG similarity search duration, p50/p95/p99 |
| `kafka.events.pending` | Gauge | Live count of undelivered outbox events |

### Health Indicators

| Indicator | DOWN condition |
|-----------|---------------|
| `VectorStoreHealthIndicator` | `knowledge_chunks` count = 0 (embeddings not loaded) |
| `KafkaHealthIndicator` | Cannot reach Kafka broker |
| Spring default `db` | PostgreSQL unreachable |
| Spring default `redis` | Redis unreachable |

### Distributed Tracing

Every request carries a `traceId` from Telegram message receipt through to Telegram delivery confirmation. Traces are exported via OTLP to Grafana Tempo. Search by `traceId` in Grafana to see the full span tree for any purchase.

---

## Kafka Topics

| Topic | Partitions | Retention | Producer | Consumer |
|-------|-----------|-----------|----------|----------|
| `identity.events` | 3 | 7 days | identity module (outbox) | billing `SessionSyncConsumer` |
| `sales.provision.requested` | 6 | 14 days | sales module (outbox) | delivery `DeliveryEventConsumer` |
| `sales.provision.delivered` | 6 | 14 days | delivery module | admin `SalesMetricsService` |
| `sales.provision.failed` | 3 | 30 days | delivery module | billing `DeliveryFailureCompensationConsumer` |
| `wallet.events` | 3 | 30 days | billing module (outbox) | billing `WalletCreditedConsumer` |
| `admin.alerts` | 1 | 7 days | admin module | — (forwarded to Telegram) |
| `delivery.dlq` | 1 | 90 days | delivery module | Manual ops review only |

> `delivery.dlq` is never automatically reprocessed. All DLQ events require manual investigation before being reset via the admin bot command `/reset_event {id}`.

---

## API — AI Tool Methods

The AI routes user messages by calling these `@Tool` methods in `PurchaseOrchestrationTools`. They are never called directly by users — only by the Spring AI `ChatClient`.

### Tool Execution Order (enforced by AI system prompt)

```
validatePhoneNetwork() → checkWalletBalance() → initiatePurchase()
```

| Tool | Purpose |
|------|---------|
| `validatePhoneNetwork(phone, network)` | Confirms phone prefix matches requested network |
| `checkWalletBalance(chatId)` | Returns current NGN balance and sufficiency check |
| `initiatePurchase(PurchaseRequest)` | Atomic: debit wallet + create order + publish outbox event |
| `checkNetworkStatus(network)` | Returns UP/DEGRADED/DOWN for MTN/Airtel/Glo/9mobile |
| `getVirtualAccount(chatId)` | Returns bank name, account number, account name for top-up |

---

## Nigerian Phone Prefix Map

`NigerianPhoneValidator` uses this map to detect network from the phone prefix. A purchase is rejected if the detected network does not match the requested network.

| Network | Prefixes |
|---------|---------|
| MTN | 0803, 0806, 0703, 0706, 0810, 0813, 0814, 0816, 0903, 0906 |
| Airtel | 0701, 0708, 0802, 0808, 0812, 0901, 0902, 0907 |
| Glo | 0705, 0805, 0807, 0811, 0815, 0905 |
| 9mobile | 0809, 0817, 0818, 0908, 0909 |

---

## Non-Functional Requirements

| Requirement | Target |
|------------|--------|
| Purchase end-to-end latency (p95) | < 60 seconds |
| PIN validation latency | < 500ms |
| RAG query latency (p95) | < 3 seconds |
| Redis session TTL | 15 minutes (sliding window) |
| Kafka event retry interval | 60 seconds |
| Kafka max retry attempts | 5 |
| Vendor API retry attempts | 3 (2s → 4s → 8s exponential backoff) |
| PIN lockout duration | 15 minutes after 3 failures |
| Vector embedding model | `text-embedding-3-small` (1536 dimensions) |
| RAG similarity search k | 5 results |
| Dead-letter retention | 90 days |
| Database connection pool | HikariCP min=5, max=20 |

---

## Contributing

1. Fork the repository and create a feature branch from `main`
2. Run `mvn test` — all tests including `ModulithArchitectureTest` must pass before opening a PR
3. Do not cross module boundaries — `ModulithArchitectureTest` will fail the CI build if you do
4. Do not create a `JpaRepository` for an entity or value object — only aggregate roots get repositories
5. All new cross-module communication must go through a domain event or the module's `api/` interface
6. Every new `@Transactional` method that publishes an event must have a corresponding `@ApplicationModuleTest`

---

## License

MIT — see [LICENSE](LICENSE) for details.