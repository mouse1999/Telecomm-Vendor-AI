# design.md — System Architecture Design Document
## DataBot NG: Event-Driven Telegram Data Vending Bot

**Version:** 1.0.0
**Date:** 2026

> **Diagram formats used:**
> - **PlantUML** — component diagrams, module boundaries, security boundaries (render at plantuml.com or IntelliJ PlantUML plugin)
> - **Mermaid** — sequence diagrams, flowcharts, state diagrams (render in GitHub, VS Code Mermaid extension, or mermaid.live)

---

## 1. Full System Architecture

```plantuml
@startuml System_Architecture
!theme cerulean-outline
skinparam backgroundColor #FAFAFA
skinparam defaultFontName Arial
skinparam linetype ortho
skinparam nodesep 50
skinparam ranksep 70

title DataBot NG — Full System Architecture

actor "Telegram User" as USER
cloud "Telegram API" as TELEGRAM
cloud "OpenAI API" as OPENAI
cloud "Vendor Data API\n(3rd Party)" as VENDOR
cloud "Bank Webhook\n(Providus / Wema)" as BANK

package "Spring Boot Application" {

  component "TelegramBotHandler" as BOT #LightBlue
  component "SessionGuard\n(Redis check)" as GUARD #LightYellow

  package "identity module" #LightGreen {
    component "IdentityService\n- validatePin()\n- BCrypt check\n- Redis write" as IDENTITY
    component "NigerianPhoneValidator" as VALIDATOR
  }

  package "knowledge module" #LightPink {
    component "KnowledgeService\n- VectorStore search\n- RAG queries" as KNOWLEDGE
    component "NetworkStatusService" as NETSTATUS
  }

  package "billing module" #MistyRose {
    component "BillingService\n- deductWallet()\n- refundWallet()\n- getBalance()" as BILLING
    component "SessionSyncConsumer" as SESSSYNC
    component "PaymentWebhookController" as WEBHOOK
  }

  package "delivery module" #LightSkyBlue {
    component "PurchaseOrchestrationTools\n(@Tool methods)" as TOOLS
    component "ChatClient\n(Spring AI)" as CHATCLIENT
    component "DeliveryEventConsumer\n(@Retryable 3x)" as DELIVERY
    component "TelegramNotificationService" as NOTIFIER
  }

  package "admin module" #Lavender {
    component "SalesMetricsService\n(Micrometer)" as METRICS
    component "DeadLetterAlertService" as DLALERT
    component "VectorStoreHealthIndicator" as HEALTH
  }

  database "Modulith Outbox\nevent_publication" as OUTBOX #Wheat
}

package "Apache Kafka (KRaft)" as KAFKA #LightYellow {
  queue "identity.events" as K_IDENTITY
  queue "sales.provision.requested" as K_PROVISION
  queue "sales.provision.delivered" as K_DELIVERED
  queue "sales.provision.failed" as K_FAILED
  queue "wallet.events" as K_WALLET
  queue "admin.alerts" as K_ALERTS
  queue "delivery.dlq" as K_DLQ
}

package "Persistence Layer" {
  database "PostgreSQL 16 + PGVector\nuser_accounts | wallets | wallet_transactions\nvirtual_accounts | orders\nnetwork_status_entries | knowledge_chunks\nevent_publication" as PG
  database "Redis 7\nsession:{chatId} TTL 15min\nbilling_session:{chatId} TTL 15min\npin_attempts:{chatId} TTL 15min" as REDIS
}

package "Observability Stack" {
  component "Prometheus" as PROM
  component "Grafana Tempo" as TEMPO
  component "Grafana Dashboards" as GRAFANA
  component "OTel Collector" as OTEL
}

' External entry
USER --> TELEGRAM
TELEGRAM --> BOT : long-poll / webhook
BANK --> WEBHOOK : POST /webhook/payment

' Session check
BOT --> GUARD
GUARD --> REDIS : GET session:{chatId}

' AI routing
BOT --> CHATCLIENT
CHATCLIENT --> OPENAI : chat completion
CHATCLIENT --> TOOLS : @Tool dispatch
CHATCLIENT --> KNOWLEDGE : RAG lookup

' Identity
TOOLS --> VALIDATOR
IDENTITY --> REDIS : SET session
IDENTITY --> PG : BCrypt verify
IDENTITY --> OUTBOX : ApplicationEventPublisher

' Billing
TOOLS --> BILLING : checkWalletBalance
WEBHOOK --> K_WALLET

' Outbox → Kafka
OUTBOX --> K_IDENTITY
OUTBOX --> K_PROVISION

' Kafka consumers
K_IDENTITY --> SESSSYNC
SESSSYNC --> REDIS : SET billing_session
K_PROVISION --> DELIVERY
DELIVERY --> VENDOR : @Retryable API call
DELIVERY --> K_DELIVERED
DELIVERY --> K_FAILED
DELIVERY --> K_DLQ
K_FAILED --> BILLING : refundWallet()
K_WALLET --> BILLING : credit wallet
K_DELIVERED --> NOTIFIER
NOTIFIER --> TELEGRAM : sendMessage()

' Admin / Observability
DLALERT --> K_ALERTS
METRICS --> PROM : /actuator/prometheus
PROM --> GRAFANA
OTEL --> TEMPO --> GRAFANA

@enduml
```

---

## 2. Module Boundaries, Aggregate Roots & JPA Rules

```plantuml
@startuml Module_Boundaries
!theme cerulean-outline
skinparam backgroundColor #FAFAFA
skinparam defaultFontName Arial
skinparam packageStyle rectangle
skinparam nodesep 50
skinparam classAttributeIconSize 0

title Spring Modulith + DDD — Module Boundaries & Aggregate Roots

package "identity module" #D5E8D4 {
  class "UserAccount <<AR>>" as UA {
    id : UUID
    chatId : Long
    pinHash : String
    accountStatus : AccountStatus
    --
    validatePin()
    recordFailedAttempt()
    unlock()
  }
  class "UserAccountRepository" as UAR
  class "IdentityService" as IS
  class "SessionGuard" as SG
  class "NigerianPhoneValidator" as NPV
  UAR ..> UA : manages
  IS --> UAR
  IS --> SG
}

package "billing module" #F8CECC {
  class "Wallet <<AR>>" as W {
    id : UUID
    ownerId : UUID
    balance : Money <<VO>>
    version : Long
    --
    debit(amount)
    credit(amount)
    canAfford(amount)
  }
  class "WalletTransaction <<Entity>>" as WT {
    id : UUID
    walletId : UUID
    type : TransactionType
    amount : Money <<VO>>
    reference : String
  }
  class "VirtualAccount <<Entity>>" as VA {
    id : UUID
    walletId : UUID
    accountNumber : String
  }
  class "WalletRepository" as WR
  class "BillingService" as BS
  class "SessionSyncConsumer" as SSC
  W "1" *-- "0..*" WT : @OneToMany cascade=ALL
  W "1" *-- "0..1" VA : @OneToMany cascade=ALL
  WR ..> W : manages
  note bottom of WR : NO repository for\nWalletTransaction\nor VirtualAccount.\nAccess only via Wallet.
  BS --> WR
  SSC --> BS
}

package "sales module" #DAE8FC {
  class "Order <<AR>>" as O {
    id : UUID
    orderRef : String
    buyerChatId : Long
    item : OrderItem <<VO>>
    status : OrderStatus
    --
    markDelivered()
    markFailed()
    requestRefund()
  }
  class "OrderRepository" as OR
  class "PurchaseOrchestrationTools" as POT
  class "DeliveryEventConsumer" as DEC
  OR ..> O : manages
  POT --> OR
  DEC --> OR
}

package "knowledge module" #FFE6CC {
  class "KnowledgeChunk <<AR>>" as KC {
    id : UUID
    content : Text
    namespace : KnowledgeNamespace
    embedding : vector(1536)
  }
  class "NetworkStatusEntry <<AR>>" as NSE {
    id : UUID
    network : Network
    status : NetworkAvailability
    checkedAt : Instant
  }
  class "KnowledgeChunkRepository" as KCR
  class "NetworkStatusEntryRepository" as NSER
  class "KnowledgeService" as KS
  KCR ..> KC : manages
  NSER ..> NSE : manages
  KS --> KCR
  KS --> NSER
}

package "admin module" #E1D5E7 {
  class "SalesMetricsService" as SMS
  class "VectorStoreHealthIndicator" as VSHI
  class "AdminEventResetController" as AERC
}

database "PostgreSQL 16 + PGVector" as PG
database "Redis 7" as REDIS
queue "Apache Kafka" as KAFKA

UAR --> PG : user_accounts
WR --> PG : wallets, wallet_transactions\nvirtual_accounts
OR --> PG : orders
KCR --> PG : knowledge_chunks (vector)
NSER --> PG : network_status_entries
IS --> REDIS : session:{chatId}
SSC --> REDIS : billing_session:{chatId}
SSC --> KAFKA : identity.events (consume)
DEC --> KAFKA : sales.provision.* (consume/produce)
POT --> BS : @Tool (same JVM — allowed)
POT --> KS : RAG @Tool (same JVM — allowed)
SMS --> PG : event_publication COUNT

note as RULES
  JPA RULES
  ─────────────────────────────────────────────────
  1. Only Aggregate Roots have JpaRepository
  2. Value Objects use @Embeddable / @Embedded
  3. Entities within aggregate: @OneToMany(cascade=ALL, orphanRemoval=true)
  4. Cross-context: UUID reference field only — NO @ManyToOne across modules
  5. All ARs: @Version Long version (optimistic locking)
  6. All ARs: UUID PK with @GeneratedValue strategy UUID
  ─────────────────────────────────────────────────
  MODULITH RULES
  ─────────────────────────────────────────────────
  7. Modules communicate via Kafka or ApplicationEventPublisher
  8. @Autowiring across module boundaries: only delivery→billing and delivery→knowledge
  9. All event-publishing methods are @Transactional
end note

@enduml
```

---

## 3. Transactional Outbox Flow

### Happy Path

```mermaid
sequenceDiagram
    autonumber
    actor User as Telegram User
    participant Bot as TelegramBotHandler
    participant Tools as initiatePurchase()
    participant DB as PostgreSQL
    participant Outbox as event_publication
    participant Kafka as Kafka Broker
    participant Delivery as DeliveryEventConsumer
    participant Vendor as Vendor Data API
    participant Notify as TelegramNotificationService

    User->>Bot: "Send 2GB MTN to 08012345678"
    Bot->>Tools: initiatePurchase(request)

    rect rgb(209, 231, 221)
        Note over Tools,Outbox: @Transactional BEGIN
        Tools->>DB: INSERT orders (status=PENDING)
        Tools->>DB: UPDATE wallets SET balance=balance-1500<br/>WHERE version=current_version (optimistic lock)
        Tools->>Outbox: INSERT event_publication<br/>status=STARTED, payload=DataProvisionRequested
        Note over Tools,Outbox: @Transactional COMMIT ✓
    end

    Tools-->>User: "⏳ Processing your 2GB MTN for 08012345678..."

    Note over Outbox,Kafka: Async — Modulith republisher fires after commit
    Outbox->>Kafka: publish → sales.provision.requested<br/>headers: {traceparent, chatId}
    Outbox->>DB: UPDATE event_publication SET completion_date=NOW()

    Kafka->>Delivery: consume DataProvisionRequested
    rect rgb(209, 231, 221)
        Note over Delivery,Vendor: @Retryable(maxAttempts=3)
        Delivery->>Vendor: POST /provision {network, phone, planId}
        Vendor-->>Delivery: 200 OK {ref: "VENDOR-789"}
    end

    Delivery->>DB: UPDATE orders SET status=DELIVERED, txn_ref=TXN-001
    Delivery->>Kafka: publish DataDelivered → sales.provision.delivered

    Kafka->>Notify: consume DataDelivered
    Notify->>User: ✅ "Your 2GB MTN data delivered to 08012345678. Ref: TXN-001"
```

### Failure Path — Kafka Down

```mermaid
sequenceDiagram
    autonumber
    participant Outbox as event_publication<br/>(Modulith Outbox)
    participant Kafka as Kafka Broker
    participant DB as PostgreSQL
    participant Admin as Admin Telegram Group
    participant Ops as Ops Team

    Note over Outbox: @Transactional committed ✓<br/>status = STARTED ← safe state preserved

    loop Every 60s — Modulith Republisher
        Outbox->>Kafka: attempt publish to sales.provision.requested
        Kafka-->>Outbox: ❌ Connection refused (broker down)
        Note over Outbox: retry_count++
    end

    Note over Outbox: retry_count reaches 5
    Outbox->>DB: UPDATE event_publication SET status = FAILED
    Outbox->>Admin: Direct Telegram HTTP POST (NOT via Kafka)<br/>"⚠️ Event purch_abc FAILED after 5 retries"

    Admin->>Ops: Alert received
    Note over Ops: Investigates root cause<br/>Restores Kafka connectivity

    Ops->>DB: POST /admin/events/{id}/reset<br/>UPDATE status=STARTED, completion_date=NULL

    Note over Outbox,Kafka: Kafka is healthy again
    Outbox->>Kafka: publish ✅ (republisher picks up STARTED events)
    Outbox->>DB: UPDATE status = COMPLETED
    Note over Outbox: Normal flow resumes → delivery continues
```

### Failure Path — Vendor API Fails (Refund Flow)

```mermaid
sequenceDiagram
    autonumber
    participant Kafka as Kafka Broker
    participant Delivery as DeliveryEventConsumer
    participant Vendor as Vendor Data API
    participant DLQ as delivery.dlq
    participant Billing as BillingService
    participant DB as PostgreSQL
    participant User as Telegram User

    Kafka->>Delivery: consume DataProvisionRequested

    rect rgb(248, 215, 218)
        Delivery->>Vendor: attempt 1 — POST /provision
        Vendor-->>Delivery: ❌ 504 timeout (backoff 2s)
        Delivery->>Vendor: attempt 2 — POST /provision
        Vendor-->>Delivery: ❌ 504 timeout (backoff 4s)
        Delivery->>Vendor: attempt 3 — POST /provision
        Vendor-->>Delivery: ❌ 504 timeout (backoff 8s)
        Note over Delivery: @Retryable exhausted — all 3 attempts failed
    end

    Delivery->>Kafka: publish DataDeliveryFailed → sales.provision.failed
    Delivery->>DLQ: write to delivery.dlq (manual ops review)

    rect rgb(255, 243, 205)
        Note over Billing: Compensation Transaction
        Kafka->>Billing: consume DataDeliveryFailed
        Billing->>DB: UPDATE wallets SET balance=balance+1500 @Transactional
        Billing->>DB: UPDATE orders SET status=REFUNDED
    end

    Delivery->>User: ❌ "Your 2GB MTN purchase failed.<br/>₦1500 has been refunded to your wallet."
```

---

## 4. RAG Pipeline Flow

```mermaid
flowchart TD
    subgraph INGEST["📥 Ingestion — ApplicationReadyEvent at startup"]
        A["📄 PDF / TXT files\n/data/knowledge/prices/\n/data/knowledge/faq/"]
        B["DocumentIngestionService\nTokenTextSplitter\n512 tokens · 50 overlap"]
        C["EmbeddingModel\nOpenAI text-embedding-3-small\nfloat[1536] vector per chunk"]
        D[("PGVector — knowledge_chunks\nmetadata: namespace, sourceFile, chunkIndex")]
        A --> B --> C --> D
    end

    subgraph HEALTH["🏥 Startup Health Check"]
        D --> E{"VectorStoreHealthIndicator\nSELECT COUNT(*) > 0?"}
        E -- Yes --> F["Actuator: UP ✅"]
        E -- No --> G["Actuator: DOWN ❌\nAdmin alert fires"]
    end

    subgraph QUERY["🔍 Query — per user message"]
        H["User message:\n'What are the cheapest MTN 5GB plans?'"]
        I["ChatClient\n+ ChatMemory chatId\nlast 10 messages"]
        J["Embed query → float[1536]\nOpenAI embedding call"]
        K["VectorStore.similaritySearch\nk=5 · cosine similarity\nfilter: namespace='prices'"]
        L["Top 5 matching chunks\nreturned as context"]
        M["ChatClient builds final prompt:\nSystem + Context chunks + User question"]
        N["OpenAI API response\ngpt-4o-mini / gpt-4o"]
        O["Telegram InlineKeyboard\nPlan Name | Price ₦ | Validity"]
        H --> I --> J --> K --> D
        D --> L --> M --> N --> O --> H
    end

    subgraph METRICS["📊 Observability"]
        J --> P["Micrometer Timer START"]
        N --> Q["Micrometer Timer STOP\nrag.queries.latency histogram\np50 · p95 · p99"]
    end

    style INGEST fill:#e8f5e9,stroke:#4caf50
    style HEALTH fill:#fff9c4,stroke:#f9a825
    style QUERY fill:#e3f2fd,stroke:#1976d2
    style METRICS fill:#f3e5f5,stroke:#7b1fa2
```

---

## 5. PIN Authentication Flow & Redis Session Lifecycle

```mermaid
stateDiagram-v2
    direction TB

    [*] --> CheckSession : User sends purchase or balance command

    CheckSession : SessionGuard\nGET session:{chatId} from Redis

    CheckSession --> SessionActive : Key EXISTS ✅
    CheckSession --> PromptPIN : Key MISSING or expired

    SessionActive : Session valid\nProceed to requested action
    SessionActive --> [*]

    PromptPIN : Telegram message sent:\n"Please enter your 6-digit PIN:"
    PromptPIN --> ValidatePin : User replies with PIN

    ValidatePin : IdentityService.validatePin(chatId, rawPin)\nBCrypt.matches(rawPin, user_accounts.pin_hash)

    ValidatePin --> PinCorrect : matches = true
    ValidatePin --> PinWrong : matches = false

    PinCorrect : PIN verified
    PinCorrect --> WriteSession : RESET pin_attempt_count = 0\nSET session:{chatId} = UUID token\nEXPIRE 900s
    WriteSession --> PublishEvent : Publish UserSessionUnlocked\nvia ApplicationEventPublisher\n→ Modulith Outbox → Kafka
    PublishEvent --> BillingSync : Billing consumes UserSessionUnlocked\nSET billing_session:{chatId}\nEXPIRE 900s
    BillingSync --> SessionActive

    PinWrong : Wrong PIN entered
    PinWrong --> IncrAttempts : INCR pin_attempts:{chatId}\nEXPIRE 900s

    IncrAttempts --> Under3 : attempts < 3
    IncrAttempts --> Reached3 : attempts >= 3

    Under3 : Telegram: "Wrong PIN. X attempts remaining."
    Under3 --> PromptPIN

    Reached3 : Publish AccountLocked event\nUPDATE user_accounts SET locked_until = NOW() + 15min
    Reached3 --> Locked

    Locked : Telegram: "Account locked for 15 minutes.\nPlease try again later."
    Locked --> [*]
```

---

## 6. Purchase Flow — Complete Event Chain

```mermaid
sequenceDiagram
    autonumber
    actor User as Telegram User
    participant Bot as TelegramBotHandler
    participant Guard as SessionGuard
    participant Redis as Redis
    participant AI as Spring AI ChatClient
    participant T1 as validatePhoneNetwork()
    participant T2 as checkWalletBalance()
    participant T3 as initiatePurchase()
    participant DB as PostgreSQL
    participant Outbox as Modulith Outbox
    participant Kafka as Apache Kafka
    participant Del as DeliveryEventConsumer
    participant Vendor as Vendor API
    participant Notify as TelegramNotificationService

    User->>Bot: "Send 2GB MTN to 08012345678"
    Bot->>Guard: assertSessionActive(chatId=123)
    Guard->>Redis: GET session:123
    Redis-->>Guard: ✅ token present
    Guard-->>Bot: session OK

    Bot->>AI: message + ChatMemory(chatId=123)
    Note over AI: OpenAI resolves intent →<br/>network=MTN · size=2048MB<br/>phone=08012345678

    AI->>T1: validatePhoneNetwork("08012345678", "MTN")
    Note over T1: 0801 prefix → MTN ✓
    T1-->>AI: {valid: true, network: "MTN"}

    AI->>T2: checkWalletBalance(chatId=123)
    T2->>Redis: GET billing_session:123 ✅
    T2->>DB: SELECT balance FROM wallets
    DB-->>T2: 3500.00
    T2-->>AI: {sufficient: true, balance: 3500.00, required: 1500.00}

    AI->>T3: initiatePurchase(PurchaseRequest)
    rect rgb(209, 231, 221)
        Note over T3,Outbox: @Transactional
        T3->>DB: INSERT orders (id=purch_abc, status=PENDING)
        T3->>DB: UPDATE wallets SET balance=2000, version=version+1
        T3->>Outbox: ApplicationEventPublisher.publish(DataProvisionRequested)<br/>→ INSERT event_publication (status=STARTED)
        Note over T3,Outbox: COMMIT ✓
    end
    T3-->>User: "⏳ Processing your 2GB MTN for 08012345678..."

    Note over Outbox,Kafka: Async after commit — Modulith republisher
    Outbox->>Kafka: sales.provision.requested<br/>header: {traceparent, chatId=123}
    Outbox->>DB: UPDATE event_publication SET completion_date=NOW()

    Kafka->>Del: consume DataProvisionRequested
    Del->>Vendor: POST /provision (attempt 1 of 3)
    Vendor-->>Del: 200 OK {vendorRef: "VENDOR-789"}

    Del->>DB: UPDATE orders SET status=DELIVERED, txn_ref=TXN-001
    Del->>Kafka: publish DataDelivered → sales.provision.delivered

    Kafka->>Notify: consume DataDelivered
    Notify->>User: ✅ "Your 2GB MTN data has been delivered<br/>to 08012345678. Ref: TXN-001"
```

---

## 7. OpenTelemetry Trace Context Propagation

```mermaid
flowchart LR
    subgraph APP["🏗 Spring Boot App — Trace starts here"]
        direction TB
        S1["Span 1\ntelegram.message.received\n{chatId, messageType}"]
        S2["Span 2\nai.chat.completion\n{model, promptTokens, chatId}"]
        S3["Span 3\ntool.validatePhoneNetwork"]
        S4["Span 4\ntool.checkWalletBalance"]
        S5["Span 5 — @Transactional\ndb.transaction.purchase"]
        S5a["Span 5a\nJDBC: INSERT orders"]
        S5b["Span 5b\nJDBC: UPDATE wallets"]
        S6["Span 6\nmodulith.event.publish\n→ event_publication INSERT"]
        S7["Span 7\nkafka.produce\nsales.provision.requested"]
        S1-->S2-->S3-->S4-->S5
        S5-->S5a & S5b
        S5-->S6-->S7
    end

    subgraph HDR["📨 Kafka Message Header"]
        H["traceparent:\n00-{traceId}-{spanId}-01\n\nSame traceId propagated\nacross thread boundary ↓"]
    end

    subgraph DEL["🚚 Delivery Module — Same trace, new thread"]
        direction TB
        S8["Span 8\nkafka.consume\nDeliveryEventConsumer"]
        S9["Span 9\nhttp.client.request\nVendor API"]
        S10["Span 10\nkafka.produce\nsales.provision.delivered"]
        S11["Span 11\ntelegram.send.message\n{chatId}"]
        S8-->S9-->S10-->S11
    end

    subgraph OBS["👁 Observability — Full trace visible end-to-end"]
        OTEL["OTel Collector\nOTLP receiver :4317"]
        TEMPO["Grafana Tempo"]
        UI["Grafana UI\nTrace: message → confirmation"]
        OTEL-->TEMPO-->UI
    end

    S7 -->|"Kafka message\n+ traceparent header"| HDR
    HDR -->|"OTel context extractor\nresumes same traceId"| S8
    APP -->|"OTLP gRPC"| OTEL
    DEL -->|"OTLP gRPC"| OTEL

    style APP fill:#e3f2fd,stroke:#1976d2
    style DEL fill:#e8f5e9,stroke:#388e3c
    style HDR fill:#fff9c4,stroke:#f9a825
    style OBS fill:#f3e5f5,stroke:#7b1fa2
```

---

## 8. Security Boundaries

```plantuml
@startuml Security_Boundaries
!theme cerulean-outline
skinparam backgroundColor #FAFAFA
skinparam defaultFontName Arial
skinparam packageStyle rectangle

title Security Boundaries — Access Control per Module

package "identity module" #D5E8D4 {
  note as N1
    ✅ ALLOWED
    Read: user_accounts.pin_hash
    Write: Redis session:{chatId}
    Publish: UserSessionUnlocked (via Outbox)

    ❌ FORBIDDEN
    Read/write: wallets table
    Call: Kafka directly (KafkaTemplate)
    Call: Vendor API
    Return: raw PIN or hash in any event
  end note
}

package "billing module" #F8CECC {
  note as N2
    ✅ ALLOWED
    Read/Write: wallets (optimistic lock)
    Read: virtual_accounts
    Consume: identity.events (Kafka)
    Produce: WalletCredited (Kafka)
    Write: Redis billing_session:{chatId}

    ❌ FORBIDDEN
    Read: user_accounts.pin_hash
    Call: identity module @Service beans
    Call: Vendor API
  end note
}

package "knowledge module" #FFE6CC {
  note as N3
    ✅ ALLOWED
    Read: knowledge_chunks (PGVector)
    Read: network_status_entries
    Write: knowledge_chunks on ingest

    ❌ FORBIDDEN
    Read/Write: wallets, user_accounts
    Produce: Kafka events
    Call: external APIs
  end note
}

package "delivery module" #DAE8FC {
  note as N4
    ✅ ALLOWED
    Call: Vendor API via @Retryable HTTP client
    Consume: sales.provision.* (Kafka)
    Produce: DataDelivered / DataDeliveryFailed
    Send: Telegram messages
    Call: BillingService @Tool methods (same JVM)
    Call: KnowledgeService (RAG, same JVM)

    ❌ FORBIDDEN
    Modify: wallets directly (must use BillingService)
    Skip: SessionGuard check on @Tool methods
    Read: user_accounts.pin_hash
  end note
}

package "admin module" #E1D5E7 {
  note as N5
    ✅ ALLOWED
    Read: metrics, event_publication COUNT
    Send: admin Telegram alerts (direct HTTP)
    Execute: /reset_event command (AdminGuard chatId check)

    ❌ FORBIDDEN
    Write: any user or wallet table
    Auto-reprocess: DLQ events (manual only)
    Access: PIN or session data
  end note
}

note bottom
  GLOBAL SECURITY RULES
  ─────────────────────────────────────────────────────────────────
  1. All @Tool methods call SessionGuard.assertSessionActive(chatId) as FIRST line
  2. PIN hash — BCrypt cost ≥ 12, never logged, never in event payloads
  3. Redis session token — UUID v4 only, never the raw PIN
  4. Admin commands — AdminGuard.assertAdmin(chatId) check, no HTTP auth needed
  5. Webhook endpoints — HMAC-SHA256 signature validated BEFORE any DB read or write
  6. event_publication table — written ONLY by Spring Modulith internals
  7. delivery.dlq — read-only for ops, no automated reprocessing
  8. TraceID propagated in all Kafka headers — full audit trail
  9. No Spring Security dependency — Telegram handles transport auth
end note

@enduml
```

---

## 9. Kafka Topic Map

```mermaid
flowchart LR
    subgraph PROD["📤 Producers"]
        P1["identity module\nvia ApplicationEventPublisher\n+ Modulith Outbox"]
        P2["billing module\nvia ApplicationEventPublisher\n+ Modulith Outbox"]
        P3["delivery module\nDeliveryEventConsumer"]
        P4["billing module\nPaymentWebhookController"]
        P5["admin module\nDeadLetterAlertService"]
    end

    subgraph TOPICS["⚡ Apache Kafka (KRaft mode)"]
        T1["identity.events\n3 partitions · 7d retention"]
        T2["sales.provision.requested\n6 partitions · 14d retention"]
        T3["sales.provision.delivered\n6 partitions · 14d retention"]
        T4["sales.provision.failed\n3 partitions · 30d retention"]
        T5["wallet.events\n3 partitions · 30d retention"]
        T6["admin.alerts\n1 partition · 7d retention"]
        T7["delivery.dlq\n1 partition · 90d retention"]
    end

    subgraph CONS["📥 Consumers"]
        C1["billing module\nSessionSyncConsumer\n→ Redis billing_session"]
        C2["delivery module\nDeliveryEventConsumer\n→ Vendor API"]
        C3["billing + admin\nDeliverySuccessConsumer\n→ metrics update"]
        C4["billing module\nDeliveryFailureCompensation\n→ refundWallet()"]
        C5["billing module\nWalletCreditedConsumer\n→ credit balance"]
        C6["admin module\nAlertForwarder\n→ Telegram admin group"]
        C7["Manual Ops Review\n(no auto-reprocess)"]
    end

    P1 --> T1 --> C1
    P2 --> T2 --> C2
    P3 --> T3 --> C3
    P3 --> T4 --> C4
    P4 --> T5 --> C5
    P5 --> T6 --> C6
    P3 --> T7 --> C7

    style PROD fill:#e3f2fd,stroke:#1976d2
    style TOPICS fill:#fff9c4,stroke:#f9a825
    style CONS fill:#e8f5e9,stroke:#388e3c
```