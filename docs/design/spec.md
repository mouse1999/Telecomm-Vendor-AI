# spec.md — Product & Technical Specification
## DataBot NG: Event-Driven Telegram Data Vending Bot

**Version:** 1.0.0
**Date:** 2026
**Status:** Pre-Implementation

---

## 1. Project Overview

DataBot NG is a high-scale, event-driven Telegram bot that allows Nigerian users to purchase mobile data bundles (MTN, Airtel, Glo, 9mobile) via natural language chat. Users fund a wallet, ask the AI about plans, and purchase data — all within Telegram.

### Core Principles
- The **AI is a Router only** — it parses intent and calls Java tools. It never touches payments or vendor APIs directly.
- **Zero-Trust Auth** — no action executes without a verified session.
- **Guaranteed Delivery** — every sale is backed by the Transactional Outbox pattern. A customer is never charged without delivery being attempted.
- **Observable by default** — every event carries a TraceID; every module exposes metrics.

### Goals
| # | Goal | Metric |
|---|------|--------|
| G1 | Users can buy data in under 60 seconds | p95 purchase latency < 60s |
| G2 | Zero lost transactions during Kafka outages | 0 unrecovered events in `event_publication` after 5 min |
| G3 | AI answers plan queries accurately from live price data | RAG recall > 90% on test set |
| G4 | System remains observable at all times | Grafana uptime 99.9% |

---

## 2. User Stories & Acceptance Criteria

---

### US.1 — PIN-Gated Access
**As a user**, I want my wallet protected by a 6-digit PIN so only I can authorize purchases.

**Acceptance Criteria:**
- [ ] Any `/buy`, `/balance`, or purchase-intent message triggers a PIN prompt if no active Redis session exists for that `chatId`
- [ ] PIN is validated against a BCrypt hash stored in `user_accounts.pin_hash` (PostgreSQL)
- [ ] On success: `UserSessionUnlocked` event is published via `ApplicationEventPublisher`
- [ ] On failure (wrong PIN): user receives "Incorrect PIN. X attempts remaining." Redis counter decremented
- [ ] After 3 failed attempts: account locked for 15 minutes, `AccountLocked` event published
- [ ] Redis session key `session:{chatId}` is set with 15-minute TTL on success
- [ ] No purchase tool method executes if `session:{chatId}` is absent in Redis

---

### US.2 — Identity Synchronization
**As a system**, once a PIN is verified, the Billing Module must know the session is active before processing payments.

**Acceptance Criteria:**
- [ ] Billing Module Kafka consumer listens on `identity.events` topic
- [ ] On receiving `UserSessionUnlocked`: Billing caches `billing_session:{chatId}` in Redis with 15-minute TTL
- [ ] No `deductWallet()` call proceeds if `billing_session:{chatId}` is missing
- [ ] Session TTL refreshes on each successful purchase (sliding window)

---

### US.3 — Data Plan Inquiry
**As a user**, I want to ask "What are the cheapest MTN 5GB plans?" and get accurate, current results.

**Acceptance Criteria:**
- [ ] ChatClient performs VectorStore similarity search with k=5 against embedded price list documents
- [ ] Results are returned as a Telegram `InlineKeyboardMarkup` with columns: Plan Name | Price (NGN) | Validity
- [ ] If VectorStore returns 0 results: AI responds "I couldn't find plans matching that query. Try: 'MTN 1GB plans'"
- [ ] Price list embeddings are loaded on `ApplicationReadyEvent` at startup
- [ ] RAG query latency recorded as `rag.queries.latency` histogram metric

---

### US.4 — Network Status Awareness
**As a user**, I want to ask "Is Airtel down?" and get a real-time status check.

**Acceptance Criteria:**
- [ ] ChatClient calls `@Tool checkNetworkStatus(String network)`
- [ ] Tool queries `network_status_entries` table for entries within the last 30 minutes
- [ ] Returns: `{ network, status: UP|DEGRADED|DOWN, lastChecked, incidentNote }`
- [ ] If status is DOWN or DEGRADED: AI warns user before they attempt a purchase
- [ ] Network status entries are inserted by an external monitoring job (out of scope for this spec)

---

### US.5 — FAQ & USSD Help
**As a user**, I want to ask "How do I check my MTN balance?" and get the correct USSD code.

**Acceptance Criteria:**
- [ ] ChatClient performs VectorStore search against FAQ/documentation embeddings (separate namespace from price list)
- [ ] Returns USSD codes, step-by-step instructions, and tips
- [ ] If no result found: escalates to a human support Telegram group (sends forward message)

---

### US.6 — Direct Purchase Intent
**As a user**, I want to say "Send 2GB MTN to 08012345678" and have it execute end-to-end.

**Acceptance Criteria:**
- [ ] AI extracts structured payload: `{ network, dataSizeMB, phoneNumber, planId, priceNGN, chatId }`
- [ ] AI calls tools in strict order: `validatePhoneNetwork` → `checkWalletBalance` → `initiatePurchase`
- [ ] If any validation fails: purchase stops, user gets specific error message
- [ ] `initiatePurchase` is `@Transactional`: wallet deducted + `DataProvisionRequested` event written atomically
- [ ] `DataProvisionRequested` event stored in `event_publication` BEFORE Kafka delivery
- [ ] User receives: "⏳ Processing your 2GB MTN for 08012345678..."

---

### US.7 — Number-to-Network Validation
**As a system**, I must reject purchases where the phone prefix doesn't match the requested network.

**Acceptance Criteria:**
- [ ] `NigerianPhoneValidator` correctly maps all prefixes (see prefix table in Section 5)
- [ ] Mismatch returns `PurchaseRejected { reason: "NETWORK_MISMATCH", phoneNumber, detectedNetwork, requestedNetwork }`
- [ ] User message: "⚠️ 08023456789 is an Airtel number. Did you mean Airtel 2GB?"
- [ ] Invalid/unknown prefix returns `PurchaseRejected { reason: "UNKNOWN_PREFIX" }`

---

### US.8 — Wallet Top-up Instructions
**As a user**, I want to ask "How do I fund my wallet?" and get my unique virtual account.

**Acceptance Criteria:**
- [ ] AI calls `@Tool getVirtualAccount(chatId)` → returns `{ bankName, accountNumber, accountName }`
- [ ] AI explains: transfer to account → auto-credited via webhook → Telegram confirmation
- [ ] Virtual account is pre-assigned at user registration and stored in `virtual_accounts` table
- [ ] If user has no virtual account: system creates one via bank API and stores it

---

### US.9 — Transaction Guarantee (Outbox Pattern)
**As a system**, no sale should be lost even if Kafka is unavailable at the moment of purchase.

**Acceptance Criteria:**
- [ ] `event_publication` table persists all unpublished events
- [ ] Spring Modulith republisher runs every 60 seconds
- [ ] Events with `status = STARTED` are retried on Kafka recovery
- [ ] After 5 failed retries: event status set to `FAILED`, admin Telegram alert sent via direct HTTP (not Kafka)
- [ ] `kafka.events.pending` Micrometer gauge reflects current `STARTED` count in real-time

---

### US.10 — Real-time Delivery Notification
**As a user**, I want an instant Telegram message confirming my data was delivered.

**Acceptance Criteria:**
- [ ] Delivery Module consumes `DataProvisionRequested` from Kafka
- [ ] Vendor API call is annotated `@Retryable(maxAttempts=3, backoff: 2s→4s→8s)`
- [ ] On success: `DataDelivered { chatId, network, dataSizeMB, phoneNumber, txnRef, timestamp }` published
- [ ] Telegram message sent: "✅ Your 2GB MTN data has been delivered to 08012345678. Ref: TXN-XXXXX"
- [ ] On all retries exhausted: `DataDeliveryFailed` published → wallet refunded → failure message sent → event logged to dead-letter Kafka topic `delivery.dlq`

---

### US.11 — Admin Grafana Dashboard
**As an admin**, I want real-time visibility into sales, failures, and system health.

**Acceptance Criteria:**
- [ ] `sales.transactions.total` counter, tagged by `network`
- [ ] `sales.transactions.failed` counter, tagged by `failureReason`
- [ ] `wallet.topups.total` counter, tagged by `amountBucket` (0-500, 500-2000, 2000+)
- [ ] `rag.queries.latency` histogram with p50, p95, p99 buckets
- [ ] `kafka.events.pending` gauge from live DB count
- [ ] `VectorStoreHealthIndicator` reports `DOWN` if embedding count = 0
- [ ] Actuator `/health` reports `DOWN` if PostgreSQL or Kafka is unreachable

---

## 3. System Events — Full Payload Schemas

### `UserSessionUnlocked`
```json
{
  "eventType": "UserSessionUnlocked",
  "chatId": 123456789,
  "userId": "usr_abc123",
  "sessionToken": "tok_xyz789",
  "timestamp": "2026-01-15T10:30:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

### `AccountLocked`
```json
{
  "eventType": "AccountLocked",
  "chatId": 123456789,
  "reason": "MAX_PIN_ATTEMPTS_EXCEEDED",
  "lockedUntil": "2026-01-15T10:45:00Z",
  "traceId": "..."
}
```

### `DataProvisionRequested`
```json
{
  "eventType": "DataProvisionRequested",
  "purchaseId": "purch_abc123",
  "chatId": 123456789,
  "network": "MTN",
  "dataSizeMB": 2048,
  "phoneNumber": "08012345678",
  "planId": "mtn-2gb-30day",
  "priceNGN": 1500.00,
  "walletTxnId": "wtxn_xyz456",
  "timestamp": "2026-01-15T10:31:00Z",
  "traceId": "..."
}
```

### `DataDelivered`
```json
{
  "eventType": "DataDelivered",
  "purchaseId": "purch_abc123",
  "chatId": 123456789,
  "network": "MTN",
  "dataSizeMB": 2048,
  "phoneNumber": "08012345678",
  "txnRef": "TXN-20260115-00123",
  "vendorRef": "VENDOR-REF-789",
  "timestamp": "2026-01-15T10:31:15Z",
  "traceId": "..."
}
```

### `DataDeliveryFailed`
```json
{
  "eventType": "DataDeliveryFailed",
  "purchaseId": "purch_abc123",
  "chatId": 123456789,
  "network": "MTN",
  "dataSizeMB": 2048,
  "phoneNumber": "08012345678",
  "failureReason": "VENDOR_TIMEOUT",
  "retryCount": 3,
  "refundIssued": true,
  "timestamp": "2026-01-15T10:31:40Z",
  "traceId": "..."
}
```

### `WalletCredited`
```json
{
  "eventType": "WalletCredited",
  "chatId": 123456789,
  "amountNGN": 5000.00,
  "newBalanceNGN": 6500.00,
  "paymentRef": "PAY-REF-456",
  "bankName": "Providus",
  "timestamp": "2026-01-15T10:00:00Z",
  "traceId": "..."
}
```

### `PurchaseRejected`
```json
{
  "eventType": "PurchaseRejected",
  "chatId": 123456789,
  "reason": "NETWORK_MISMATCH | INSUFFICIENT_FUNDS | UNKNOWN_PREFIX | SESSION_EXPIRED",
  "details": {
    "phoneNumber": "08023456789",
    "requestedNetwork": "MTN",
    "detectedNetwork": "Airtel"
  },
  "timestamp": "2026-01-15T10:31:00Z",
  "traceId": "..."
}
```

---

## 4. Kafka Topics

| Topic | Partitions | Retention | Producer Module | Consumer Module(s) |
|-------|-----------|-----------|-----------------|-------------------|
| `identity.events` | 3 | 7 days | identity | billing |
| `sales.provision.requested` | 6 | 14 days | billing | delivery |
| `sales.provision.delivered` | 6 | 14 days | delivery | billing, admin |
| `sales.provision.failed` | 3 | 30 days | delivery | billing, admin |
| `wallet.events` | 3 | 30 days | billing (webhook receiver) | billing |
| `admin.alerts` | 1 | 7 days | any module | admin (Telegram alert sender) |
| `delivery.dlq` | 1 | 90 days | delivery | manual ops review |

**Kafka Config:**
- Mode: KRaft (no ZooKeeper)
- Replication factor: 1 (dev), 3 (prod)
- `auto.offset.reset`: `earliest`
- `enable.auto.commit`: `false` (manual ack only)

---

## 5. Domain-Driven Design — Aggregate Design

> The full visual schema with JPA relationships is in **`schema.puml`** (open with IntelliJ PlantUML plugin or plantuml.com).

### Design Rules
- Every **Aggregate Root** (`<<AR>>`) has its own `JpaRepository`. No repository exists for plain Entities or Value Objects.
- **Value Objects** (`<<VO>>`) are embedded via JPA `@Embeddable` / `@Embedded`. They have no `@Id`.
- **Entities within an aggregate** are mapped with `@OneToMany(cascade = ALL, orphanRemoval = true)` owned by the root.
- **Cross-context references** are by ID only (a `UUID` field, never a `@ManyToOne` across package boundaries).
- All Aggregate Roots use `UUID` primary keys generated by the database (`gen_random_uuid()`).
- All mutable Aggregate Roots carry `@Version Long version` for optimistic locking.

---

### Bounded Context: Identity

**Aggregate Root: `UserAccount`** → table `user_accounts`

| Column | Java Field | Type | Constraints |
|--------|-----------|------|-------------|
| `id` | `id` | `UUID` | PK, gen_random_uuid() |
| `chat_id` | `chatId` | `BIGINT` | UK, NOT NULL, IDX |
| `username` | `username` | `VARCHAR(100)` | NULLABLE |
| `pin_hash` | `pinHash` | `VARCHAR(255)` | NOT NULL |
| `pin_attempt_count` | `pinAttemptCount` | `SMALLINT` | DEFAULT 0 |
| `locked_until` | `lockedUntil` | `TIMESTAMPTZ` | NULLABLE |
| `account_status` | `accountStatus` | `VARCHAR(20)` | NOT NULL DEFAULT 'ACTIVE' |
| `created_at` | `createdAt` | `TIMESTAMPTZ` | DEFAULT NOW() |
| `updated_at` | `updatedAt` | `TIMESTAMPTZ` | DEFAULT NOW() |

**Indexes:** `idx_user_accounts_chat_id` ON `chat_id`

**Value Object: `PinPolicy`** → embedded in `UserAccount` (no separate table)
- `max_attempts INT DEFAULT 3`
- `lock_duration_minutes INT DEFAULT 15`

**Behaviours on `UserAccount`:**
- `validatePin(rawPin)` — BCrypt check, delegates to `PinPolicy`
- `recordFailedAttempt()` — increments count, locks if policy exceeded
- `unlock()` — resets count, clears `locked_until`
- `isLocked()` — checks `lockedUntil` against `Instant.now()`

---

### Bounded Context: Billing

**Aggregate Root: `Wallet`** → table `wallets`

| Column | Java Field | Type | Constraints |
|--------|-----------|------|-------------|
| `id` | `id` | `UUID` | PK |
| `owner_id` | `ownerId` | `UUID` | UK, NOT NULL (ref to `user_accounts.id`) |
| `balance_amount` | `balance.amount` | `NUMERIC(12,2)` | NOT NULL DEFAULT 0, CHECK >= 0 |
| `balance_currency` | `balance.currency` | `VARCHAR(3)` | NOT NULL DEFAULT 'NGN' |
| `wallet_status` | `status` | `VARCHAR(20)` | NOT NULL DEFAULT 'ACTIVE' |
| `version` | `version` | `BIGINT` | NOT NULL DEFAULT 0 |
| `updated_at` | `updatedAt` | `TIMESTAMPTZ` | DEFAULT NOW() |

**Value Object: `Money`** → `@Embeddable`, mapped to `balance_amount` + `balance_currency` columns

**Entity: `WalletTransaction`** → table `wallet_transactions` (owned by `Wallet`)

| Column | Java Field | Type | Constraints |
|--------|-----------|------|-------------|
| `id` | `id` | `UUID` | PK |
| `wallet_id` | `walletId` | `UUID` | FK → wallets.id, IDX |
| `type` | `type` | `VARCHAR(30)` | NOT NULL |
| `amount_value` | `amount.amount` | `NUMERIC(12,2)` | NOT NULL |
| `amount_currency` | `amount.currency` | `VARCHAR(3)` | NOT NULL |
| `balance_before` | `balanceBefore.amount` | `NUMERIC(12,2)` | NOT NULL |
| `balance_after` | `balanceAfter.amount` | `NUMERIC(12,2)` | NOT NULL |
| `reference` | `reference` | `VARCHAR(100)` | UK, NOT NULL |
| `description` | `description` | `VARCHAR(255)` | NULLABLE |
| `created_at` | `createdAt` | `TIMESTAMPTZ` | DEFAULT NOW() |

**Indexes:** `idx_wallet_txn_wallet_id`, `idx_wallet_txn_reference`

**Entity: `VirtualAccount`** → table `virtual_accounts` (owned by `Wallet`)

| Column | Java Field | Type | Constraints |
|--------|-----------|------|-------------|
| `id` | `id` | `UUID` | PK |
| `wallet_id` | `walletId` | `UUID` | FK → wallets.id, UK |
| `bank_name` | `bankName` | `VARCHAR(50)` | NOT NULL |
| `account_number` | `accountNumber` | `VARCHAR(20)` | UK, NOT NULL |
| `account_name` | `accountName` | `VARCHAR(100)` | NOT NULL |
| `created_at` | `createdAt` | `TIMESTAMPTZ` | DEFAULT NOW() |

**Behaviours on `Wallet`:**
- `debit(amount)` — creates `WalletTransaction(DEBIT_PURCHASE)`, decrements balance
- `credit(amount)` — creates `WalletTransaction(CREDIT_TOPUP or CREDIT_REFUND)`
- `canAfford(amount)` — pure check, no side effects

---

### Bounded Context: Sales

**Aggregate Root: `Order`** → table `orders`

| Column | Java Field | Type | Constraints |
|--------|-----------|------|-------------|
| `id` | `id` | `UUID` | PK |
| `order_ref` | `orderRef` | `VARCHAR(50)` | UK, NOT NULL, IDX |
| `buyer_chat_id` | `buyerChatId` | `BIGINT` | NOT NULL, IDX |
| `wallet_txn_ref` | `walletTxnRef` | `VARCHAR(100)` | NULLABLE |
| `network` | `item.network` | `VARCHAR(20)` | NOT NULL |
| `plan_id` | `item.planId` | `VARCHAR(100)` | NOT NULL |
| `data_size_mb` | `item.dataSizeMB` | `INT` | NOT NULL |
| `recipient_phone` | `item.recipientPhone.value` | `VARCHAR(15)` | NOT NULL |
| `recipient_prefix` | `item.recipientPhone.prefix` | `VARCHAR(5)` | NOT NULL |
| `price_amount` | `item.price.amount` | `NUMERIC(10,2)` | NOT NULL |
| `price_currency` | `item.price.currency` | `VARCHAR(3)` | NOT NULL DEFAULT 'NGN' |
| `status` | `status` | `VARCHAR(30)` | NOT NULL DEFAULT 'PENDING', IDX |
| `failure_reason` | `failureReason` | `VARCHAR(255)` | NULLABLE |
| `vendor_ref` | `vendorRef` | `VARCHAR(100)` | NULLABLE |
| `txn_ref` | `txnRef` | `VARCHAR(100)` | NULLABLE |
| `created_at` | `createdAt` | `TIMESTAMPTZ` | DEFAULT NOW(), IDX |
| `updated_at` | `updatedAt` | `TIMESTAMPTZ` | DEFAULT NOW() |

**Indexes:** `idx_orders_order_ref`, `idx_orders_buyer_chat_id`, `idx_orders_status`, `idx_orders_created_at`

**Value Objects embedded in `Order`:**
- `OrderItem` — `@Embeddable`, maps to columns `network`, `plan_id`, `data_size_mb`, `price_*`
- `PhoneNumber` — `@Embeddable` nested inside `OrderItem`, maps to `recipient_phone`, `recipient_prefix`
- `Money` — reused `@Embeddable`, maps to `price_amount` + `price_currency`

**Behaviours on `Order`:**
- `markDelivered(vendorRef, txnRef)` — transitions PROVISIONING → DELIVERED
- `markFailed(reason)` — transitions PROVISIONING → FAILED
- `requestRefund()` — transitions FAILED → REFUND_PENDING
- `isRefundable()` — true only when status is FAILED

---

### Bounded Context: Knowledge

**Aggregate Root: `NetworkStatusEntry`** → table `network_status_entries`

| Column | Java Field | Type | Constraints |
|--------|-----------|------|-------------|
| `id` | `id` | `UUID` | PK |
| `network` | `network` | `VARCHAR(20)` | NOT NULL, IDX |
| `status` | `status` | `VARCHAR(20)` | NOT NULL |
| `incident_note` | `incidentNote` | `TEXT` | NULLABLE |
| `checked_at` | `checkedAt` | `TIMESTAMPTZ` | DEFAULT NOW(), IDX |

**Indexes:** `idx_network_status_network_checked_at` ON `(network, checked_at DESC)`

**Aggregate Root: `KnowledgeChunk`** → table `knowledge_chunks`

| Column | Java Field | Type | Constraints |
|--------|-----------|------|-------------|
| `id` | `id` | `UUID` | PK |
| `content` | `content` | `TEXT` | NOT NULL |
| `namespace` | `namespace` | `VARCHAR(30)` | NOT NULL, IDX |
| `source_file` | `sourceFile` | `VARCHAR(255)` | NOT NULL |
| `chunk_index` | `chunkIndex` | `INT` | NOT NULL |
| `embedding` | `embedding` | `vector(1536)` | IDX (ivfflat, lists=100) |
| `metadata` | `metadata` | `JSONB` | NOT NULL |
| `ingested_at` | `ingestedAt` | `TIMESTAMPTZ` | DEFAULT NOW() |

---

### Infrastructure: Spring Modulith

**`event_publication`** — managed entirely by Spring Modulith, never write manually.

| Column | Type | Notes |
|--------|------|-------|
| `id` | `UUID` | PK |
| `listener_id` | `VARCHAR(512)` | Target Kafka binding |
| `event_type` | `VARCHAR(512)` | Fully qualified Java class name |
| `serialized_event` | `TEXT` | JSON of the event |
| `publication_date` | `TIMESTAMPTZ` | Indexed |
| `completion_date` | `TIMESTAMPTZ` | NULL = pending, NOT NULL = delivered |

---

### JPA Repository Map

| Repository | Aggregate Root | Module |
|-----------|---------------|--------|
| `UserAccountRepository` | `UserAccount` | identity |
| `WalletRepository` | `Wallet` | billing |
| `OrderRepository` | `Order` | sales |
| `NetworkStatusEntryRepository` | `NetworkStatusEntry` | knowledge |
| `KnowledgeChunkRepository` | `KnowledgeChunk` | knowledge |

> `WalletTransaction` and `VirtualAccount` have **no repository** — they are accessed exclusively via `Wallet` (their aggregate root). `OrderItem` and `PhoneNumber` have **no repository** — they are embedded value objects.

---

### Nigerian Phone Prefix Map
| Prefix | Network |
|--------|---------|
| 0803, 0806, 0703, 0706, 0813, 0816, 0903, 0906, 0810, 0814 | MTN |
| 0802, 0808, 0708, 0812, 0701, 0902, 0901, 0907 | Airtel |
| 0805, 0807, 0705, 0815, 0905, 0811 | Glo |
| 0809, 0817, 0818, 0908, 0909 | 9mobile |

---

## 6. API Contracts — AI @Tool Methods

All tools are in `com.databot.sales.tools.PurchaseOrchestrationTools`

### `validatePhoneNetwork`
```java
@Tool(description = "Validates that a Nigerian phone number belongs to the specified network. " +
                    "Call this FIRST before any purchase. Returns VALID or throws PurchaseRejected.")
public PhoneValidationResult validatePhoneNetwork(
    @ToolParam(description = "11-digit Nigerian phone number e.g. 08012345678") String phoneNumber,
    @ToolParam(description = "Network name: MTN, Airtel, Glo, or 9mobile") String network
)
// Returns: { valid: true, network: "MTN", phoneNumber: "08012345678" }
// Throws:  PurchaseRejectedException with reason NETWORK_MISMATCH or UNKNOWN_PREFIX
```

### `checkWalletBalance`
```java
@Tool(description = "Returns the current NGN wallet balance for the authenticated user. " +
                    "Call this SECOND to verify sufficient funds before purchase.")
public WalletBalanceResult checkWalletBalance(
    @ToolParam(description = "Telegram chat ID of the authenticated user") Long chatId
)
// Returns: { balanceNGN: 3500.00, sufficient: true, chatId: 123456789 }
```

### `initiatePurchase`
```java
@Tool(description = "Initiates a data purchase. Only call this AFTER validatePhoneNetwork " +
                    "and checkWalletBalance both succeed. This deducts the wallet and queues delivery.")
public PurchaseInitiatedResult initiatePurchase(
    @ToolParam(description = "Validated purchase payload as JSON") PurchaseRequest request
)
// Returns: { purchaseId: "purch_abc123", status: "QUEUED", message: "Processing..." }
```

### `checkNetworkStatus`
```java
@Tool(description = "Checks current operational status of a Nigerian mobile network. " +
                    "Use when user asks if a network is down or experiencing issues.")
public NetworkStatusResult checkNetworkStatus(
    @ToolParam(description = "Network name: MTN, Airtel, Glo, or 9mobile") String network
)
// Returns: { network: "MTN", status: "UP", lastChecked: "...", incidentNote: null }
```

### `getVirtualAccount`
```java
@Tool(description = "Returns the user's unique virtual bank account for wallet funding. " +
                    "Use when user asks how to top up or fund their wallet.")
public VirtualAccountResult getVirtualAccount(
    @ToolParam(description = "Telegram chat ID of the user") Long chatId
)
// Returns: { bankName: "Providus Bank", accountNumber: "1234567890", accountName: "DataBot/UserName" }
```

---

## 7. Non-Functional Requirements

| Requirement | Target |
|------------|--------|
| Purchase end-to-end latency (p95) | < 60 seconds |
| PIN validation latency | < 500ms |
| RAG query latency (p95) | < 3 seconds |
| Redis session TTL | 15 minutes (sliding) |
| Kafka event retry interval | 60 seconds |
| Kafka max retry count | 5 attempts |
| Vendor API retry attempts | 3 (backoff: 2s, 4s, 8s) |
| PIN lockout duration | 15 minutes after 3 failures |
| Wallet optimistic lock | Version-based, re-read on conflict |
| Vector embedding model | text-embedding-3-small (1536 dims) |
| RAG similarity search k | 5 results |
| Actuator health check interval | 10 seconds |
| Dead-letter retention | 90 days |
| Database connection pool | HikariCP, min=5, max=20 |