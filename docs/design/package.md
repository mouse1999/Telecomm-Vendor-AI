# project-structure.md — Project Structure
## DataBot NG · Spring Modulith · DDD · Java 21

> **Convention:** Every top-level package under `com.databot` is a Spring Modulith module.
> Each module owns its domain completely — no other module may import from its `domain`, `repository`, or `service` sub-packages directly.
> Cross-module communication happens only via published events or explicit `@ApplicationModule(allowedDependencies = {...})`.

---

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

## Module Visibility Rules

| Sub-package | Visibility | Rule |
|-------------|-----------|------|
| `domain/` | **PRIVATE** | Never import another module's domain class. Cross-context = UUID field only. |
| `repository/` | **PRIVATE** | Never inject another module's repository. |
| `service/` | **PRIVATE** | Never `@Autowire` another module's service directly (except delivery→billing and delivery→knowledge via their `api/` interface). |
| `event/` | **PUBLIC** | Events are the only safe cross-module data contract. Always `record` types. |
| `dto/` | **PUBLIC** | Request/response shapes may be imported by the module that calls this one. |
| `api/` | **PUBLIC** | Thin interface that wraps the service. The only class another module may `@Autowire`. |
| `tools/` | **PUBLIC** | Spring AI `@Tool` beans — registered in `ChatClientConfig` in delivery module. |
| `consumer/` | **PRIVATE** | Kafka listeners are internal — no module calls them directly. |
| `controller/` | **PRIVATE** | REST/bot handlers are entry points only. |

---

## Spring Modulith Enforcement

The `ModulithArchitectureTest.java` at the root of the test tree runs:

```java
@Test
void verifyModularity() {
    ApplicationModules.of(DataBotApplication.class).verify();
}
```

This test **fails the build automatically** if any module imports a class from another module's `domain`, `repository`, or `service` package. Run it on every pull request. It is the architectural guardrail that keeps all six bounded contexts clean as the codebase grows.