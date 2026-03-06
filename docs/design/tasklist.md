# tasklist.md — Implementation Task List

## DataBot NG · DDD + Spring Data JPA · Phased Build Plan

**Last updated:** 2026
**Stack:** Java 21 · Spring Boot 3.4+ · Spring Modulith · Spring Data JPA · PostgreSQL 16 + PGVector · Kafka KRaft · Redis 7 · Spring AI

---

## How to Read This List


| Field         | Meaning                                             |
| ------------- | --------------------------------------------------- |
| **Files**     | Exact file path(s) to create or edit                |
| **Satisfies** | User story code from spec.md                        |
| **Blocker**   | Task IDs that must be ✅ before starting this task  |
| **DDD note**  | Aggregate root, entity, or value object being built |


> **Rule:** Never create a `JpaRepository` for an Entity or Value Object. Only Aggregate Roots get repositories. See `schema.md` for the full map.

---

## PHASE 0 — Infrastructure & Project Skeleton

**Goal:** All backing services running locally. Spring Boot starts, connects to all services, and passes health checks.

---

- [ ]  **T0.1** — Create `docker-compose.yml`

  - **Files:** `docker-compose.yml`
  - **Satisfies:** Foundation for all phases
  - **Blocker:** None — start here
  - **Services:** PostgreSQL 16 with `pgvector` extension, Kafka KRaft single-node, Redis 7, Grafana, Prometheus, Grafana Tempo, OpenTelemetry Collector
  - **Note:** Kafka must be reachable at `localhost:9092`. PostgreSQL must run `CREATE EXTENSION IF NOT EXISTS vector;` on init.
- [ ]  **T0.2** — Create `pom.xml` with full dependency set

  - **Files:** `pom.xml`
  - **Satisfies:** Foundation for all phases
  - **Blocker:** None
  - **Key dependencies:**
    - `spring-boot-starter-data-jpa` (Spring Data JPA — replaces JDBC)
    - `spring-modulith-starter-core`
    - `spring-modulith-events-kafka`
    - `spring-modulith-events-jpa` (JPA-backed outbox — not JDBC)
    - `spring-cloud-stream-binder-kafka`
    - `spring-boot-starter-actuator`
    - `micrometer-registry-prometheus`
    - `micrometer-tracing-bridge-otel`
    - `opentelemetry-exporter-otlp`
    - `spring-ai-openai-spring-boot-starter`
    - `spring-ai-pgvector-store-spring-boot-starter`
    - `spring-boot-starter-data-redis`
    - `spring-retry` + `spring-boot-starter-aop`
    - `telegrambots-spring-boot-starter`
    - `postgresql` JDBC driver
    - `lombok` (optional but recommended)
- [ ]  **T0.3** — Create base `application.yml`

  - **Files:** `src/main/resources/application.yml`
  - **Satisfies:** Foundation for all phases
  - **Blocker:** T0.1, T0.2
  - **Configure:**
    - `spring.datasource` — HikariCP, min=5, max=20
    - `spring.jpa.hibernate.ddl-auto=validate` (Flyway manages schema)
    - `spring.kafka.bootstrap-servers`
    - `spring.data.redis.host`
    - `spring.ai.openai.api-key: ${OPENAI_API_KEY}`
    - `spring.ai.vectorstore.pgvector.*`
    - `management.endpoints.web.exposure.include=health,prometheus,info`
    - `management.tracing.sampling.probability=1.0`
    - `spring.modulith.republish-outstanding-events-on-restart=true`
    - `spring.modulith.events.republication-interval=PT60S`
- [ ]  **T0.4** — Create Flyway migration scripts (replaces raw `init.sql`)

  - **Files:**
    - `src/main/resources/db/migration/V1__create_user_accounts.sql`
    - `src/main/resources/db/migration/V2__create_wallets_and_transactions.sql`
    - `src/main/resources/db/migration/V3__create_orders.sql`
    - `src/main/resources/db/migration/V4__create_knowledge_tables.sql`
    - `src/main/resources/db/migration/V5__create_event_publication.sql`
    - `src/main/resources/db/migration/V6__create_indexes.sql`
  - **Satisfies:** Foundation for all phases
  - **Blocker:** T0.1
  - **DDD note:** Table names now match DDD naming: `user_accounts`, `wallets`, `wallet_transactions`, `virtual_accounts`, `orders`, `network_status_entries`, `knowledge_chunks`
  - **Note:** Add `spring.flyway.enabled=true` to `application.yml`. `event_publication` table DDL is provided by Spring Modulith's autoconfiguration — include it in V5 anyway for explicitness.
- [ ]  **T0.5** — Create main application class and module package skeleton

  - **Files:**
    - `src/main/java/com/databot/DataBotApplication.java`
    - `src/main/java/com/databot/identity/package-info.java`
    - `src/main/java/com/databot/billing/package-info.java`
    - `src/main/java/com/databot/sales/package-info.java`
    - `src/main/java/com/databot/knowledge/package-info.java`
    - `src/main/java/com/databot/delivery/package-info.java`
    - `src/main/java/com/databot/admin/package-info.java`
  - **Satisfies:** Foundation for all phases
  - **Blocker:** T0.2
  - **Note:** Each `package-info.java` gets `@ApplicationModule` to enforce Modulith boundaries. Note the new `sales` module replaces the old `delivery` module's purchase logic.
- [ ]  **T0.6** — Create shared Value Object `Money`

  - **Files:** `src/main/java/com/databot/shared/Money.java`
  - **Satisfies:** Billing, Sales aggregates
  - **Blocker:** T0.5
  - **DDD note:** `@Embeddable` — `BigDecimal amount` + `String currency = "NGN"`. Immutable. Contains `add()`, `subtract()`, `isGreaterThanOrEqual()`. Lives in a `shared` package visible to all modules.
- [ ]  **T0.7** — Create shared `Network` enum

  - **Files:** `src/main/java/com/databot/shared/Network.java`
  - **Satisfies:** Sales, Knowledge, Identity (phone validation)
  - **Blocker:** T0.5
  - **Values:** `MTN`, `AIRTEL`, `GLO`, `NINE_MOBILE`
- [ ]  **T0.8** — Verify infrastructure boots cleanly

  - **Action:** `docker-compose up -d` → `mvn spring-boot:run` → check `/actuator/health` returns UP
  - **Blocker:** T0.1 – T0.6

---

## PHASE 1 — Identity Module

**Goal:** `UserAccount` aggregate fully functional. PIN validates. Session written to Redis. `UserSessionUnlocked` event stored in outbox and delivered to Kafka.

---

- [ ]  **T1.1** — Create `AccountStatus` enum

  - **Files:** `src/main/java/com/databot/identity/domain/AccountStatus.java`
  - **Satisfies:** US.1
  - **Blocker:** T0.5
  - **Values:** `ACTIVE`, `LOCKED`, `SUSPENDED`
- [ ]  **T1.2** — Create `PinPolicy` value object

  - **Files:** `src/main/java/com/databot/identity/domain/PinPolicy.java`
  - **Satisfies:** US.1
  - **Blocker:** T0.5
  - **DDD note:** `@Embeddable`. Fields: `maxAttempts = 3`, `lockDurationMinutes = 15`. Method: `isExceeded(int count)`. No `@Id`.
- [ ]  **T1.3** — Create `UserAccount` aggregate root entity

  - **Files:** `src/main/java/com/databot/identity/domain/UserAccount.java`
  - **Satisfies:** US.1
  - **Blocker:** T0.4, T0.6, T1.1, T1.2
  - **DDD note:** `@Entity @Table(name="user_accounts")`. Fields match V1 migration. `@Embedded PinPolicy pinPolicy`. `@Version Long version`. Behaviour methods: `validatePin()`, `recordFailedAttempt()`, `unlock()`, `isLocked()`. BCrypt check lives here, not in the service.
- [ ]  **T1.4** — Create `UserAccountRepository`

  - **Files:** `src/main/java/com/databot/identity/repository/UserAccountRepository.java`
  - **Satisfies:** US.1
  - **Blocker:** T1.3
  - **DDD note:** `JpaRepository<UserAccount, UUID>`. Add: `Optional<UserAccount> findByChatId(Long chatId)`.
- [ ]  **T1.5** — Create domain events `UserSessionUnlocked` and `AccountLocked`

  - **Files:**
    - `src/main/java/com/databot/identity/event/UserSessionUnlocked.java`
    - `src/main/java/com/databot/identity/event/AccountLocked.java`
  - **Satisfies:** US.1, US.2
  - **Blocker:** T0.5
  - **Note:** Java `record` types. Include `chatId`, `timestamp`, `traceId`. `UserSessionUnlocked` also includes `sessionToken`.
- [ ]  **T1.6** — Create `IdentityService`

  - **Files:** `src/main/java/com/databot/identity/service/IdentityService.java`
  - **Satisfies:** US.1
  - **Blocker:** T1.3, T1.4, T1.5
  - **Key methods:**
    - `validatePin(Long chatId, String rawPin)` — `@Transactional`: load `UserAccount`, call `account.validatePin()`, write Redis session, `applicationEventPublisher.publishEvent(UserSessionUnlocked)`
    - `registerUser(Long chatId, String rawPin)` — create new `UserAccount` with BCrypt hash
  - **DDD note:** Service orchestrates — it does NOT contain business logic. All PIN logic is on `UserAccount`.
- [ ]  **T1.7** — Create `SessionGuard`

  - **Files:** `src/main/java/com/databot/identity/security/SessionGuard.java`
  - **Satisfies:** US.1
  - **Blocker:** T0.3 (Redis config), T1.5
  - **Key method:** `assertSessionActive(Long chatId)` — `GET session:{chatId}` from Redis, throws `SessionExpiredException` if absent.
- [ ]  **T1.8** — Create `NigerianPhoneValidator`

  - **Files:** `src/main/java/com/databot/identity/validation/NigerianPhoneValidator.java`
  - **Satisfies:** US.7
  - **Blocker:** T0.7
  - **Note:** `@Component`. `Map<String, Network>` prefix map. Method: `validate(String phone, Network requested)` returns `PhoneValidationResult`. Throws `PurchaseRejectedException(NETWORK_MISMATCH)` on mismatch.
- [ ]  **T1.9** — Configure Modulith JPA Event Publication

  - **Files:** `src/main/resources/application.yml` (edit), `src/main/resources/db/migration/V5__create_event_publication.sql`
  - **Satisfies:** US.9
  - **Blocker:** T0.3, T0.4
  - **Note:** Use `spring-modulith-events-jpa` (not JDBC). Flyway migration must create `event_publication` table. Republisher interval: 60s.
- [ ]  **T1.10** — Write unit tests for `UserAccount` aggregate behaviour

  - **Files:** `src/test/java/com/databot/identity/domain/UserAccountTest.java`
  - **Satisfies:** US.1
  - **Blocker:** T1.3
  - **Cover:** correct PIN passes, wrong PIN increments counter, 3rd failure locks account, `unlock()` resets state, `isLocked()` respects `lockedUntil`.
- [ ]  **T1.11** — Write unit tests for `NigerianPhoneValidator`

  - **Files:** `src/test/java/com/databot/identity/validation/NigerianPhoneValidatorTest.java`
  - **Satisfies:** US.7
  - **Blocker:** T1.8
  - **Cover:** all prefix mappings for all 4 networks, correct match, mismatch, unknown prefix.
- [ ]  **T1.12** — Write `@ApplicationModule` Spring Modulith integration test for identity

  - **Files:** `src/test/java/com/databot/identity/IdentityModuleTest.java`
  - **Satisfies:** US.1
  - **Blocker:** T1.6
  - **Note:** Use `@ApplicationModuleTest`. Verify `validatePin()` publishes `UserSessionUnlocked` to outbox. Use `PublishedEventsAssert`.

---

## PHASE 2 — Knowledge Module

**Goal:** Price list and FAQ documents are embedded into PGVector. AI can query them. Network status lookups work.

---

- [ ]  **T2.1** — Create `KnowledgeNamespace` enum

  - **Files:** `src/main/java/com/databot/knowledge/domain/KnowledgeNamespace.java`
  - **Satisfies:** US.3, US.5
  - **Blocker:** T0.5
  - **Values:** `PRICE_LIST`, `FAQ`, `USSD_CODES`, `NETWORK_GUIDES`
- [ ]  **T2.2** — Create `KnowledgeChunk` aggregate root

  - **Files:** `src/main/java/com/databot/knowledge/domain/KnowledgeChunk.java`
  - **Satisfies:** US.3, US.5
  - **Blocker:** T0.4, T2.1
  - **DDD note:** `@Entity @Table(name="knowledge_chunks")`. Fields: `id UUID`, `content TEXT`, `namespace`, `sourceFile`, `chunkIndex`, `embedding float[]`, `metadata Map<String,Object>` mapped as `@JdbcTypeCode(SqlTypes.JSON)`, `ingestedAt Instant`.
  - **Note:** Spring AI's `PgVectorStore` manages the embedding column. Map it with `@Column(columnDefinition="vector(1536)")`.
- [ ]  **T2.3** — Create `KnowledgeChunkRepository`

  - **Files:** `src/main/java/com/databot/knowledge/repository/KnowledgeChunkRepository.java`
  - **Satisfies:** US.3
  - **Blocker:** T2.2
  - **Add:** `long countByNamespace(KnowledgeNamespace namespace)` — used by health indicator.
- [ ]  **T2.4** — Create `NetworkAvailability` enum

  - **Files:** `src/main/java/com/databot/knowledge/domain/NetworkAvailability.java`
  - **Satisfies:** US.4
  - **Blocker:** T0.5
  - **Values:** `UP`, `DEGRADED`, `DOWN`
- [ ]  **T2.5** — Create `NetworkStatusEntry` aggregate root

  - **Files:** `src/main/java/com/databot/knowledge/domain/NetworkStatusEntry.java`
  - **Satisfies:** US.4
  - **Blocker:** T0.4, T0.7, T2.4
  - **DDD note:** `@Entity @Table(name="network_status_entries")`. Behaviour methods: `isRecent(int withinMinutes)`, `isDegraded()`.
- [ ]  **T2.6** — Create `NetworkStatusEntryRepository`

  - **Files:** `src/main/java/com/databot/knowledge/repository/NetworkStatusEntryRepository.java`
  - **Satisfies:** US.4
  - **Blocker:** T2.5
  - **Add:** `List<NetworkStatusEntry> findByNetworkAndCheckedAtAfterOrderByCheckedAtDesc(Network network, Instant since)`
- [ ]  **T2.7** — Create knowledge data seed files

  - **Files:**
    - `src/main/resources/data/knowledge/prices/mtn_plans.txt`
    - `src/main/resources/data/knowledge/prices/airtel_plans.txt`
    - `src/main/resources/data/knowledge/prices/glo_plans.txt`
    - `src/main/resources/data/knowledge/prices/9mobile_plans.txt`
    - `src/main/resources/data/knowledge/faq/ussd_codes.txt`
    - `src/main/resources/data/knowledge/faq/how_to_buy.txt`
  - **Satisfies:** US.3, US.5
  - **Blocker:** None — create with placeholder content now, fill with real prices before launch
- [ ]  **T2.8** — Create `DocumentIngestionService`

  - **Files:** `src/main/java/com/databot/knowledge/service/DocumentIngestionService.java`
  - **Satisfies:** US.3, US.5
  - **Blocker:** T2.3, T2.7
  - **Key method:** `ingestAll()` annotated `@EventListener(ApplicationReadyEvent.class)`. Reads files from classpath, splits with `TokenTextSplitter(512, 50)`, calls `VectorStore.add()`. Sets `metadata.namespace` per folder. Skips ingestion if count > 0 (idempotent restart).
- [ ]  **T2.9** — Create `KnowledgeService`

  - **Files:** `src/main/java/com/databot/knowledge/service/KnowledgeService.java`
  - **Satisfies:** US.3, US.5
  - **Blocker:** T2.3, T2.6, T2.8
  - **Key methods:**
    - `searchPlans(String query, KnowledgeNamespace namespace)` — `VectorStore.similaritySearch(k=5, filter)` wrapped in Micrometer timer
    - `getNetworkStatus(Network network)` — queries last 30 minutes from `NetworkStatusEntryRepository`
- [ ]  **T2.10** — Write integration test for RAG ingestion and query

  - **Files:** `src/test/java/com/databot/knowledge/KnowledgeServiceIntegrationTest.java`
  - **Satisfies:** US.3
  - **Blocker:** T2.9
  - **Cover:** ingest 1 document → similarity search → result contains expected content. Use `@SpringBootTest` with Testcontainers PostgreSQL + pgvector.

---

## PHASE 3 — Billing Module

**Goal:** `Wallet` aggregate handles debit and credit atomically. `VirtualAccount` is retrievable. Session sync from Kafka works.

---

- [ ]  **T3.1** — Create `WalletStatus` and `TransactionType` enums

  - **Files:**
    - `src/main/java/com/databot/billing/domain/WalletStatus.java`
    - `src/main/java/com/databot/billing/domain/TransactionType.java`
  - **Satisfies:** US.6, US.8
  - **Blocker:** T0.5
- [ ]  **T3.2** — Create `WalletTransaction` entity

  - **Files:** `src/main/java/com/databot/billing/domain/WalletTransaction.java`
  - **Satisfies:** US.6
  - **Blocker:** T0.6, T3.1
  - **DDD note:** `@Entity @Table(name="wallet_transactions")`. No `@Id` setter visible outside package. Three `@Embedded Money` fields using `@AttributeOverrides` to map to distinct columns: `amount_value/currency`, `balance_before_amount/currency`, `balance_after_amount/currency`. Created only by `Wallet.debit()` and `Wallet.credit()` — never instantiated directly.
- [ ]  **T3.3** — Create `VirtualAccount` entity

  - **Files:** `src/main/java/com/databot/billing/domain/VirtualAccount.java`
  - **Satisfies:** US.8
  - **Blocker:** T0.4, T3.1
  - **DDD note:** `@Entity @Table(name="virtual_accounts")`. No repository. Accessed only through `Wallet.getVirtualAccount()`.
- [ ]  **T3.4** — Create `Wallet` aggregate root

  - **Files:** `src/main/java/com/databot/billing/domain/Wallet.java`
  - **Satisfies:** US.6, US.8
  - **Blocker:** T0.4, T0.6, T3.2, T3.3
  - **DDD note:** `@Entity @Table(name="wallets")`. `@Version Long version`. `@Embedded Money balance` with `@AttributeOverrides`. `@OneToMany(cascade=ALL, orphanRemoval=true) List<WalletTransaction> transactions`. `@OneToOne(cascade=ALL, orphanRemoval=true) VirtualAccount virtualAccount`.
  - **Behaviour methods:**
    - `debit(Money amount)` — checks `canAfford()`, creates `WalletTransaction(DEBIT_PURCHASE)`, updates balance. Throws `InsufficientFundsException`.
    - `credit(Money amount, TransactionType type)` — creates transaction, updates balance
    - `canAfford(Money amount)` — pure boolean check
- [ ]  **T3.5** — Create `WalletRepository`

  - **Files:** `src/main/java/com/databot/billing/repository/WalletRepository.java`
  - **Satisfies:** US.6
  - **Blocker:** T3.4
  - **DDD note:** `JpaRepository<Wallet, UUID>`. Add: `Optional<Wallet> findByOwnerId(UUID ownerId)`. No repository for `WalletTransaction` or `VirtualAccount`.
- [ ]  **T3.6** — Create `WalletCredited` and `WalletDebited` domain events

  - **Files:**
    - `src/main/java/com/databot/billing/event/WalletCredited.java`
    - `src/main/java/com/databot/billing/event/WalletDebited.java`
  - **Satisfies:** US.8
  - **Blocker:** T0.5
- [ ]  **T3.7** — Create `BillingService`

  - **Files:** `src/main/java/com/databot/billing/service/BillingService.java`
  - **Satisfies:** US.6, US.8
  - **Blocker:** T3.5, T3.6
  - **Key methods:**
    - `debit(Long chatId, Money amount, String orderRef)` — `@Transactional`: load wallet by chatId→ownerId, call `wallet.debit()`, save, return transaction reference
    - `refund(Long chatId, Money amount, String orderRef)` — `@Transactional`: load wallet, call `wallet.credit(CREDIT_REFUND)`
    - `getBalance(Long chatId)` — read-only
    - `getVirtualAccount(Long chatId)` — load wallet, return virtual account
- [ ]  **T3.8** — Create `SessionSyncConsumer`

  - **Files:** `src/main/java/com/databot/billing/consumer/SessionSyncConsumer.java`
  - **Satisfies:** US.2
  - **Blocker:** T1.5, T3.7
  - **Note:** Kafka consumer on `identity.events`. On `UserSessionUnlocked`: `SET billing_session:{chatId}` in Redis TTL 900s.
- [ ]  **T3.9** — Create `PaymentWebhookController`

  - **Files:** `src/main/java/com/databot/billing/controller/PaymentWebhookController.java`
  - **Satisfies:** US.8
  - **Blocker:** T3.7
  - **Note:** `POST /webhook/payment`. Validates HMAC-SHA256 header. Calls `billingService.credit()`. Publishes `WalletCredited` event.
- [ ]  **T3.10** — Write unit tests for `Wallet` aggregate behaviour

  - **Files:** `src/test/java/com/databot/billing/domain/WalletTest.java`
  - **Satisfies:** US.6
  - **Blocker:** T3.4
  - **Cover:** successful debit, `InsufficientFundsException` on overdraft, credit adds balance, optimistic lock conflict simulation, `WalletTransaction` is created with correct before/after balance snapshot.
- [ ]  **T3.11** — Write `@ApplicationModuleTest` for billing session sync

  - **Files:** `src/test/java/com/databot/billing/BillingModuleTest.java`
  - **Satisfies:** US.2
  - **Blocker:** T3.8
  - **Cover:** Simulate `UserSessionUnlocked` published → verify `billing_session:{chatId}` written in Redis.

---

## PHASE 4 — Sales Module

**Goal:** `Order` aggregate is created atomically with wallet debit. `DataProvisionRequested` event stored in outbox. Full purchase intent flow works end-to-end from Telegram message.

---

- [ ]  **T4.1** — Create `OrderStatus` enum

  - **Files:** `src/main/java/com/databot/sales/domain/OrderStatus.java`
  - **Satisfies:** US.6
  - **Blocker:** T0.5
  - **Values:** `PENDING`, `PROVISIONING`, `DELIVERED`, `FAILED`, `REFUNDED`, `REFUND_PENDING`
- [ ]  **T4.2** — Create `PhoneNumber` value object

  - **Files:** `src/main/java/com/databot/sales/domain/PhoneNumber.java`
  - **Satisfies:** US.6, US.7
  - **Blocker:** T0.7
  - **DDD note:** `@Embeddable`. Fields: `value String`, `prefix String`, `detectedNetwork Network`. Methods: `validate()`, `matchesNetwork(Network)`. Immutable — no setters.
- [ ]  **T4.3** — Create `OrderItem` value object

  - **Files:** `src/main/java/com/databot/sales/domain/OrderItem.java`
  - **Satisfies:** US.6
  - **Blocker:** T0.6, T0.7, T4.2
  - **DDD note:** `@Embeddable`. Fields: `Network network`, `String planId`, `int dataSizeMB`, `PhoneNumber recipientPhone`, `Money price`. All `@Embedded` with `@AttributeOverrides` for `Money` and `PhoneNumber`. Immutable.
- [ ]  **T4.4** — Create `Order` aggregate root

  - **Files:** `src/main/java/com/databot/sales/domain/Order.java`
  - **Satisfies:** US.6
  - **Blocker:** T0.4, T4.1, T4.3
  - **DDD note:** `@Entity @Table(name="orders")`. `@Version Long version`. `@Embedded OrderItem item`. `@Enumerated(EnumType.STRING) OrderStatus status`.
  - **Behaviour methods:**
    - `markProvisioning()` — PENDING → PROVISIONING
    - `markDelivered(String vendorRef, String txnRef)` — PROVISIONING → DELIVERED
    - `markFailed(String reason)` — PROVISIONING → FAILED
    - `requestRefund()` — FAILED → REFUND_PENDING
    - `isRefundable()` — pure boolean
- [ ]  **T4.5** — Create `OrderRepository`

  - **Files:** `src/main/java/com/databot/sales/repository/OrderRepository.java`
  - **Satisfies:** US.6
  - **Blocker:** T4.4
  - **Add:** `Optional<Order> findByOrderRef(String orderRef)`, `List<Order> findByBuyerChatIdOrderByCreatedAtDesc(Long chatId, Pageable pageable)`
- [ ]  **T4.6** — Create domain events `DataProvisionRequested`, `PurchaseRejected`

  - **Files:**
    - `src/main/java/com/databot/sales/event/DataProvisionRequested.java`
    - `src/main/java/com/databot/sales/event/PurchaseRejected.java`
  - **Satisfies:** US.6, US.7, US.9
  - **Blocker:** T0.5
- [ ]  **T4.7** — Create `OrderService`

  - **Files:** `src/main/java/com/databot/sales/service/OrderService.java`
  - **Satisfies:** US.6
  - **Blocker:** T3.7, T4.5, T4.6
  - **Key method:** `placeOrder(PurchaseRequest request)` — `@Transactional`:
    1. Assert session active via `SessionGuard`
    2. Call `NigerianPhoneValidator.validate()`
    3. Load wallet, verify `canAfford()`
    4. `billingService.debit()` — returns txn reference
    5. Create `Order` aggregate, set `walletTxnRef`
    6. `orderRepository.save(order)`
    7. `applicationEventPublisher.publishEvent(DataProvisionRequested)` → stored in outbox
    8. Return order ref to caller
- [ ]  **T4.8** — Create `PurchaseOrchestrationTools` (Spring AI `@Tool` methods)

  - **Files:** `src/main/java/com/databot/sales/tools/PurchaseOrchestrationTools.java`
  - **Satisfies:** US.6, US.7, US.8
  - **Blocker:** T4.7, T2.9, T3.7
  - **Tools:**
    - `validatePhoneNetwork(String phone, String network)`
    - `checkWalletBalance(Long chatId)`
    - `initiatePurchase(PurchaseRequest request)` → delegates to `OrderService.placeOrder()`
    - `checkNetworkStatus(String network)` → delegates to `KnowledgeService`
    - `getVirtualAccount(Long chatId)` → delegates to `BillingService`
- [ ]  **T4.9** — Create Spring AI `ChatClient` configuration

  - **Files:** `src/main/java/com/databot/delivery/config/ChatClientConfig.java`
  - **Files:** `src/main/resources/prompts/system-prompt.txt`
  - **Satisfies:** US.3, US.6
  - **Blocker:** T4.8
  - **Note:** `ChatMemory` keyed by `chatId` (last 10 messages). Tools registered as beans. System prompt clearly defines AI-as-Router role.
- [ ]  **T4.10** — Create `TelegramBotHandler`

  - **Files:** `src/main/java/com/databot/delivery/bot/TelegramBotHandler.java`
  - **Satisfies:** US.1 (PIN prompt), US.6
  - **Blocker:** T4.9, T1.7
  - **Note:** On message received: check `SessionGuard` → if `SessionExpiredException` → prompt PIN → route to `ChatClient` → send response back to Telegram.
- [ ]  **T4.11** — Write unit tests for `Order` aggregate state machine

  - **Files:** `src/test/java/com/databot/sales/domain/OrderTest.java`
  - **Satisfies:** US.6
  - **Blocker:** T4.4
  - **Cover:** valid state transitions, invalid transition throws exception, `isRefundable()` only true when FAILED, `markDelivered()` sets both refs.
- [ ]  **T4.12** — Write `@ApplicationModuleTest` for full purchase outbox flow

  - **Files:** `src/test/java/com/databot/sales/SalesModuleTest.java`
  - **Satisfies:** US.6, US.9
  - **Blocker:** T4.7
  - **Cover:** `placeOrder()` → wallet debited → `Order` saved as PENDING → `DataProvisionRequested` in outbox. Use `PublishedEventsAssert` to verify event payload.

---

## PHASE 5 — Delivery Module

**Goal:** Vendor API called with retry. Telegram confirmation sent on success. Wallet refunded on failure.

---

- [ ]  **T5.1** — Create `DataDelivered` and `DataDeliveryFailed` domain events

  - **Files:**
    - `src/main/java/com/databot/delivery/event/DataDelivered.java`
    - `src/main/java/com/databot/delivery/event/DataDeliveryFailed.java`
  - **Satisfies:** US.10
  - **Blocker:** T0.5
- [ ]  **T5.2** — Create `VendorApiClient`

  - **Files:** `src/main/java/com/databot/delivery/client/VendorApiClient.java`
  - **Satisfies:** US.10
  - **Blocker:** T0.2 (`spring-retry` dep)
  - **Note:** `@Retryable(maxAttempts=3, backoff=@Backoff(delay=2000, multiplier=2.0, maxDelay=8000))`. Uses `RestClient`. `@Recover` method handles exhausted retries. Add `// TODO: CONFIGURE — VENDOR_API_BASE_URL and VENDOR_API_KEY`.
- [ ]  **T5.3** — Create `DeliveryEventConsumer`

  - **Files:** `src/main/java/com/databot/delivery/consumer/DeliveryEventConsumer.java`
  - **Satisfies:** US.10
  - **Blocker:** T4.6, T5.1, T5.2, T4.5
  - **Flow:**
    1. Consume `DataProvisionRequested` from `sales.provision.requested`
    2. Load `Order` by `orderRef`, call `order.markProvisioning()`, save
    3. Call `vendorApiClient.sendData()`
    4. On success: `order.markDelivered()`, publish `DataDelivered` via `ApplicationEventPublisher`
    5. On `@Recover`: `order.markFailed()`, publish `DataDeliveryFailed`, send to `delivery.dlq`
- [ ]  **T5.4** — Create `DeliveryFailureCompensationConsumer`

  - **Files:** `src/main/java/com/databot/billing/consumer/DeliveryFailureCompensationConsumer.java`
  - **Satisfies:** US.10
  - **Blocker:** T5.1, T3.7
  - **Note:** Kafka consumer on `sales.provision.failed`. On `DataDeliveryFailed`: call `billingService.refund()`. Update order status to `REFUNDED`. Lives in the `billing` module — billing owns refund logic.
- [ ]  **T5.5** — Create `TelegramNotificationService`

  - **Files:** `src/main/java/com/databot/delivery/service/TelegramNotificationService.java`
  - **Satisfies:** US.10
  - **Blocker:** T5.1
  - **Note:** Consumes `DataDelivered` → success message. Consumes `DataDeliveryFailed` → failure + refund notice. Both messages sent via Telegram Bot API.
- [ ]  **T5.6** — Write integration tests for delivery consumer

  - **Files:** `src/test/java/com/databot/delivery/DeliveryEventConsumerTest.java`
  - **Satisfies:** US.10
  - **Blocker:** T5.3
  - **Cover:** happy path (vendor returns success), all retries fail (verify `DataDeliveryFailed` published, DLQ entry written), `Order` state transitions correct in both paths.

---

## PHASE 6 — Observability

**Goal:** Grafana dashboard live. All custom Micrometer metrics emitting. Health indicators accurate. Full distributed traces visible in Tempo.

---

- [ ]  **T6.1** — Create `SalesMetricsService`

  - **Files:** `src/main/java/com/databot/admin/metrics/SalesMetricsService.java`
  - **Satisfies:** US.11
  - **Blocker:** Phase 5 complete
  - **Registers:**
    - `Counter` — `sales.transactions.total` tagged `network`
    - `Counter` — `sales.transactions.failed` tagged `failureReason`
    - `Counter` — `wallet.topups.total` tagged `amountBucket`
    - `Timer` — `rag.queries.latency` (histogram)
  - **Note:** Call `SalesMetricsService.recordSale()` from `DeliveryEventConsumer` on success. Call `recordFailure()` on `DataDeliveryFailed`.
- [ ]  **T6.2** — Create `KafkaPendingEventsGauge`

  - **Files:** `src/main/java/com/databot/admin/metrics/KafkaPendingEventsGauge.java`
  - **Satisfies:** US.11, US.9
  - **Blocker:** T1.9
  - **Note:** `@Scheduled(fixedRate=10000)`. Queries `SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL` via `JdbcTemplate` (raw SQL — not through a JPA repo). Registers result as a Micrometer `Gauge` named `kafka.events.pending`.
- [ ]  **T6.3** — Create `VectorStoreHealthIndicator`

  - **Files:** `src/main/java/com/databot/admin/health/VectorStoreHealthIndicator.java`
  - **Satisfies:** US.11
  - **Blocker:** T2.3
  - **Note:** Implements `HealthIndicator`. Calls `knowledgeChunkRepository.countByNamespace(PRICE_LIST)`. Returns `Health.down()` with detail `"embeddings": 0` if count is zero.
- [ ]  **T6.4** — Create `KafkaHealthIndicator`

  - **Files:** `src/main/java/com/databot/admin/health/KafkaHealthIndicator.java`
  - **Satisfies:** US.11
  - **Blocker:** T0.1
  - **Note:** Implements `HealthIndicator`. Uses `KafkaAdmin` to list topics. Returns `Health.down()` if connection times out.
- [ ]  **T6.5** — Configure Prometheus scrape and Grafana provisioning

  - **Files:**
    - `observability/prometheus.yml`
    - `observability/grafana/datasources/datasources.yml`
    - `observability/grafana/dashboards/databot-main.json`
    - `docker-compose.yml` (edit — mount volumes)
  - **Satisfies:** US.11
  - **Blocker:** T6.1
  - **Dashboard panels:** Sales by Network (bar), Failed Transactions (table), Wallet Top-ups (stat), RAG Latency p95 (gauge), Pending Kafka Events (stat), Health Overview (status panel)
- [ ]  **T6.6** — Configure OpenTelemetry Collector pipeline

  - **Files:** `observability/otel-collector-config.yml`
  - **Satisfies:** US.11
  - **Blocker:** T0.1
  - **Note:** OTLP receiver on `:4317`. Exports traces to Tempo. Inject `traceparent` in all Kafka message headers using `OtelKafkaProducerInterceptor`.
- [ ]  **T6.7** — Add Micrometer timer around RAG queries

  - **Files:** `src/main/java/com/databot/knowledge/service/KnowledgeService.java` (edit)
  - **Satisfies:** US.11
  - **Blocker:** T2.9, T6.1
  - **Note:** Wrap `VectorStore.similaritySearch()` in `Timer.Sample`. Record on `SalesMetricsService.recordRagLatency()`.

---

## PHASE 7 — Hardening & Production Readiness

**Goal:** Dead-letter alerts fire. Idempotency guards prevent double-charges. Architecture tests enforce module boundaries. Load tested.

---

- [ ]  **T7.1** — Create `DeadLetterAlertService`

  - **Files:** `src/main/java/com/databot/admin/service/DeadLetterAlertService.java`
  - **Satisfies:** US.9
  - **Blocker:** Phase 5 complete
  - **Note:** `@Scheduled(fixedRate=60000)`. Queries `event_publication WHERE completion_date IS NULL AND publication_date < NOW() - INTERVAL '5 minutes'`. On count > 0: sends direct HTTP POST to admin Telegram group (NOT via Kafka). Uses `RestTemplate` or `RestClient`.
- [ ]  **T7.2** — Create `AdminEventResetController`

  - **Files:** `src/main/java/com/databot/admin/controller/AdminEventResetController.java`
  - **Satisfies:** US.9
  - **Blocker:** T7.1, T7.3
  - **Note:** Telegram bot command `/reset_event {id}`. Calls `adminGuard.assertAdmin(chatId)` first. Resets `completion_date = NULL` so Modulith republisher retries it. No HTTP auth — admin is identified by `chatId` only.
- [ ]  **T7.3** — Create `AdminGuard`

  - **Files:** `src/main/java/com/databot/identity/security/AdminGuard.java`
  - **Satisfies:** Admin command protection (replaces Spring Security entirely)
  - **Blocker:** T0.3
  - **Note:** `@Component`. Reads `${telegram.admin.chat-id}` from env. Single method: `assertAdmin(Long chatId)` — throws `UnauthorisedAccessException` if `chatId` does not match. Called as first line in any admin bot command handler. No HTTP, no JWT, no filter chains needed — Telegram already authenticates the user.
- [ ]  **T7.4** — Add idempotency guard on `OrderService.placeOrder()`

  - **Files:** `src/main/java/com/databot/sales/service/OrderService.java` (edit)
  - **Satisfies:** US.6 (double-purchase prevention)
  - **Blocker:** T4.7
  - **Note:** Before creating `Order`, check `orderRepository.findByOrderRef(orderRef)`. If present and status ≠ FAILED/REFUNDED → throw `DuplicateOrderException`. Prevents re-deduction if AI calls `initiatePurchase` twice.
- [ ]  **T7.5** — Add wallet concurrency test (optimistic lock retry)

  - **Files:** `src/test/java/com/databot/billing/WalletConcurrencyTest.java`
  - **Satisfies:** US.6
  - **Blocker:** T3.10
  - **Cover:** Two threads debit same wallet simultaneously → one succeeds, one catches `ObjectOptimisticLockingFailureException` and retries.
- [ ]  **T7.6** — Write Spring Modulith architecture verification test

  - **Files:** `src/test/java/com/databot/ModulithArchitectureTest.java`
  - **Satisfies:** All modules (prevents future boundary violations)
  - **Blocker:** Phase 5 complete
  - **Note:** `ApplicationModules.of(DataBotApplication.class).verify()` — this test fails the build automatically if any module directly depends on another module's internal classes. Run on every PR.
- [ ]  **T7.7** — Write load test script

  - **Files:** `load-tests/purchase-flow.js` (k6) or `load-tests/PurchaseSimulation.scala` (Gatling)
  - **Satisfies:** Non-functional — p95 purchase latency < 60s target
  - **Blocker:** All phases complete
  - **Scenarios:** 100 virtual users, 10-minute ramp-up, purchase flow end-to-end. Measure: p50, p95, p99 latency, error rate, Kafka consumer lag.
- [ ]  **T7.8** — Create `.env.example` and `README.md`

  - **Files:** `.env.example`, `README.md`
  - **Satisfies:** Operational readiness
  - **Blocker:** All phases complete
  - **Required env vars:**
    ```
    OPENAI_API_KEY=
    TELEGRAM_BOT_TOKEN=
    TELEGRAM_BOT_USERNAME=
    TELEGRAM_ADMIN_CHAT_ID=
    VENDOR_API_KEY=
    VENDOR_API_BASE_URL=
    PAYMENT_WEBHOOK_SECRET=
    ADMIN_TELEGRAM_CHAT_ID=
    POSTGRES_PASSWORD=
    REDIS_PASSWORD=
    ```

---

## Quick Reference Tables

### User Story → Task Mapping


| User Story                 | Key Tasks                     |
| -------------------------- | ----------------------------- |
| US.1 — PIN-Gated Access   | T1.3, T1.6, T1.7, T1.8, T4.10 |
| US.2 — Identity Sync      | T1.5, T3.8                    |
| US.3 — Plan Inquiry (RAG) | T2.8, T2.9, T4.8              |
| US.4 — Network Status     | T2.5, T2.6, T2.9, T4.8        |
| US.5 — FAQ / USSD         | T2.7, T2.8, T2.9              |
| US.6 — Direct Purchase    | T4.4 – T4.10                 |
| US.7 — Phone Validation   | T1.8, T4.2, T4.8              |
| US.8 — Wallet Top-up      | T3.3, T3.9, T4.8              |
| US.9 — Outbox Guarantee   | T1.9, T7.1, T7.2              |
| US.10 — Delivery + Notify | T5.1 – T5.5                  |
| US.11 — Grafana Dashboard | T6.1 – T6.7                  |

---

### Aggregate Root → Phase Created


| Aggregate Root       | Phase   | Task |
| -------------------- | ------- | ---- |
| `UserAccount`        | Phase 1 | T1.3 |
| `Wallet`             | Phase 3 | T3.4 |
| `Order`              | Phase 4 | T4.4 |
| `NetworkStatusEntry` | Phase 2 | T2.5 |
| `KnowledgeChunk`     | Phase 2 | T2.2 |

---

### DDD Checklist Before Asking Claude CLI to Generate Any Entity

- [ ]  Is this an Aggregate Root, Entity, or Value Object? (check `schema.md`)
- [ ]  If AR → does it have a `JpaRepository`? It must.
- [ ]  If Entity → is it accessed only through its AR? Never give it a repo.
- [ ]  If VO → is it `@Embeddable` with no `@Id`? It must be immutable.
- [ ]  Does any field reference another module's AR as a Java object? Change it to a UUID field.
- [ ]  Is `@Version Long version` present on all mutable ARs? It must be.
- [ ]  Are all enums stored with `@Enumerated(EnumType.STRING)`? Never ordinal.
