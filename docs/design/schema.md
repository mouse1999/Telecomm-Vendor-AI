# schema.md — DDD Aggregate Design & Database Schema

## DataBot NG · Spring Data JPA · PostgreSQL 16 + PGVector

---

## Legend


| Symbol       | Meaning                                                                   |
| ------------ | ------------------------------------------------------------------------- |
| `<<AR>>`     | Aggregate Root — has its own`JpaRepository`, is the consistency boundary |
| `<<Entity>>` | Entity within an aggregate — no repository, accessed only via its root   |
| `<<VO>>`     | Value Object —`@Embeddable`, no `@Id`, identified by its values          |
| `<<Enum>>`   | Java enum mapped as`@Enumerated(EnumType.STRING)`                         |
| `PK`         | Primary Key                                                               |
| `FK`         | Foreign Key                                                               |
| `UK`         | Unique Key constraint                                                     |
| `IDX`        | Indexed column                                                            |

---

## Full Domain Model

```plantuml
@startuml schema
!theme cerulean-outline
skinparam backgroundColor #FAFAFA
skinparam defaultFontName Arial
skinparam linetype ortho
skinparam nodesep 60
skinparam ranksep 80
skinparam classAttributeIconSize 0

title DataBot NG — DDD Aggregate Design & Database Schema\n(Spring Data JPA · PostgreSQL 16 + PGVector)

' ═══════════════════════════════════════════════════
' BOUNDED CONTEXT: IDENTITY
' ═══════════════════════════════════════════════════

package "Bounded Context: Identity" #D5E8D4 {

  class "UserAccount <<AR>>" as UserAccount {
    .. PK ..
    + id : UUID
    .. Identity ..
    + chatId : Long <<UK, IDX>>
    + username : String
    .. Security ..
    + pinHash : String
    + pinAttemptCount : int
    + lockedUntil : Instant
    + accountStatus : AccountStatus
    .. Audit ..
    + createdAt : Instant
    + updatedAt : Instant
    .. Behaviour ..
    + validatePin(rawPin) : boolean
    + recordFailedAttempt() : void
    + unlock() : void
    + isLocked() : boolean
  }

  class "AccountStatus <<Enum>>" as AccountStatus {
    ACTIVE
    LOCKED
    SUSPENDED
  }

  class "PinPolicy <<VO>>" as PinPolicy {
    + maxAttempts : int = 3
    + lockDurationMinutes : int = 15
    + isExceeded(count) : boolean
  }

  UserAccount "1" *-- "1" PinPolicy : enforces
  UserAccount --> AccountStatus
}

' ═══════════════════════════════════════════════════
' BOUNDED CONTEXT: BILLING
' ═══════════════════════════════════════════════════

package "Bounded Context: Billing" #F8CECC {

  class "Wallet <<AR>>" as Wallet {
    .. PK ..
    + id : UUID
    .. Identity ..
    + ownerId : UUID <<FK→UserAccount, UK>>
    .. State ..
    + balance : Money
    + status : WalletStatus
    .. Optimistic Lock ..
    + version : Long
    .. Audit ..
    + updatedAt : Instant
    .. Behaviour ..
    + debit(amount) : WalletTransaction
    + credit(amount) : WalletTransaction
    + canAfford(amount) : boolean
  }

  class "WalletTransaction <<Entity>>" as WalletTxn {
    .. PK ..
    + id : UUID
    .. FK ..
    + walletId : UUID <<FK→Wallet, IDX>>
    .. Data ..
    + type : TransactionType
    + amount : Money
    + balanceBefore : Money
    + balanceAfter : Money
    + reference : String <<UK>>
    + description : String
    + createdAt : Instant
  }

  class "VirtualAccount <<Entity>>" as VirtualAccount {
    .. PK ..
    + id : UUID
    .. FK ..
    + walletId : UUID <<FK→Wallet, UK>>
    .. Bank Details ..
    + bankName : String
    + accountNumber : String <<UK>>
    + accountName : String
    + createdAt : Instant
  }

  class "Money <<VO>>" as Money {
    + amount : BigDecimal
    + currency : String = "NGN"
    + add(other) : Money
    + subtract(other) : Money
    + isGreaterThanOrEqual(other) : boolean
  }

  class "WalletStatus <<Enum>>" as WalletStatus {
    ACTIVE
    FROZEN
    CLOSED
  }

  class "TransactionType <<Enum>>" as TransactionType {
    CREDIT_TOPUP
    DEBIT_PURCHASE
    CREDIT_REFUND
  }

  Wallet "1" *-- "0..*" WalletTxn : @OneToMany\ncascade=ALL\norphanRemoval=true
  Wallet "1" *-- "0..1" VirtualAccount : @OneToMany\ncascade=ALL\norphanRemoval=true
  Wallet --> WalletStatus
  Wallet *-- Money : balance\n@Embedded
  WalletTxn *-- Money : amount\n@Embedded
  WalletTxn *-- Money : balanceBefore\nbalanceAfter
  WalletTxn --> TransactionType
}

' ═══════════════════════════════════════════════════
' BOUNDED CONTEXT: SALES
' ═══════════════════════════════════════════════════

package "Bounded Context: Sales" #DAE8FC {

  class "Order <<AR>>" as Order {
    .. PK ..
    + id : UUID
    .. Business Key ..
    + orderRef : String <<UK, IDX>>
    .. Owner (cross-context ref by ID only) ..
    + buyerChatId : Long <<IDX>>
    + walletTxnRef : String
    .. Line Item ..
    + item : OrderItem <<VO, @Embedded>>
    .. State Machine ..
    + status : OrderStatus
    + failureReason : String
    + vendorRef : String
    + txnRef : String
    .. Audit ..
    + createdAt : Instant <<IDX>>
    + updatedAt : Instant
    .. Behaviour ..
    + markDelivered(vendorRef, txnRef) : void
    + markFailed(reason) : void
    + requestRefund() : void
    + isRefundable() : boolean
  }

  class "OrderItem <<VO>>" as OrderItem {
    + network : Network
    + planId : String
    + dataSizeMB : int
    + recipientPhone : PhoneNumber <<VO>>
    + price : Money <<VO>>
  }

  class "PhoneNumber <<VO>>" as PhoneNumber {
    + value : String
    + prefix : String
    + detectedNetwork : Network
    + validate() : boolean
    + matchesNetwork(network) : boolean
  }

  class "OrderStatus <<Enum>>" as OrderStatus {
    PENDING
    PROVISIONING
    DELIVERED
    FAILED
    REFUNDED
    REFUND_PENDING
  }

  class "Network <<Enum>>" as Network {
    MTN
    AIRTEL
    GLO
    NINE_MOBILE
  }

  Order *-- "1" OrderItem : @Embedded
  OrderItem *-- PhoneNumber : @Embedded
  OrderItem *-- Money : @Embedded
  OrderItem --> Network
  Order --> OrderStatus
}

' ═══════════════════════════════════════════════════
' BOUNDED CONTEXT: KNOWLEDGE
' ═══════════════════════════════════════════════════

package "Bounded Context: Knowledge" #FFE6CC {

  class "NetworkStatusEntry <<AR>>" as NetworkStatus {
    .. PK ..
    + id : UUID
    .. Data ..
    + network : Network
    + status : NetworkAvailability
    + incidentNote : String
    + checkedAt : Instant <<IDX>>
    .. Behaviour ..
    + isRecent(withinMinutes) : boolean
    + isDegraded() : boolean
  }

  class "NetworkAvailability <<Enum>>" as NetworkAvailability {
    UP
    DEGRADED
    DOWN
  }

  class "KnowledgeChunk <<AR>>" as KnowledgeChunk {
    .. PK ..
    + id : UUID
    .. Content ..
    + content : Text
    + namespace : KnowledgeNamespace
    + sourceFile : String
    + chunkIndex : int
    .. Vector (PGVector) ..
    + embedding : vector(1536) <<IDX ivfflat>>
    + metadata : JsonB
    .. Audit ..
    + ingestedAt : Instant
  }

  class "KnowledgeNamespace <<Enum>>" as KnowledgeNamespace {
    PRICE_LIST
    FAQ
    USSD_CODES
    NETWORK_GUIDES
  }

  NetworkStatus --> NetworkAvailability
  NetworkStatus --> Network
  KnowledgeChunk --> KnowledgeNamespace
}

' ═══════════════════════════════════════════════════
' INFRASTRUCTURE: EVENT PUBLICATION (Modulith)
' ═══════════════════════════════════════════════════

package "Infrastructure: Spring Modulith" #E1D5E7 {

  class "event_publication <<Modulith Managed>>" as EventPublication {
    .. PK ..
    + id : UUID
    .. Routing ..
    + listenerId : String
    + eventType : String
    .. Payload ..
    + serializedEvent : Text
    .. Lifecycle ..
    + publicationDate : Instant <<IDX>>
    + completionDate : Instant
  }

  note bottom of EventPublication
    Managed entirely by Spring Modulith.
    Never write to this table manually.
    PENDING  = completionDate IS NULL
    DELIVERED = completionDate IS NOT NULL
  end note
}

' ═══════════════════════════════════════════════════
' CROSS-CONTEXT RELATIONSHIPS (ID references only)
' ═══════════════════════════════════════════════════

UserAccount "1" ..> "1" Wallet : ownerId = UserAccount.id\n(UUID reference — no @ManyToOne in code)
Wallet "1" ..> "0..*" Order : Order.walletTxnRef = WalletTransaction.reference\n(String reference — loose coupling)

note top of UserAccount
  CROSS-CONTEXT RULE:
  Billing holds ownerId : UUID
  pointing to UserAccount.id.
  There is NO @ManyToOne UserAccount
  inside Wallet. JPA navigation
  across bounded contexts is FORBIDDEN.
end note

@enduml
```

---

## JPA Mapping Cheat Sheet

### Aggregate Roots → Tables


| Aggregate Root       | Table                    | Module                               |
| -------------------- | ------------------------ | ------------------------------------ |
| `UserAccount`        | `user_accounts`          | identity                             |
| `Wallet`             | `wallets`                | billing                              |
| `WalletTransaction`  | `wallet_transactions`    | billing (no repo — owned by Wallet) |
| `VirtualAccount`     | `virtual_accounts`       | billing (no repo — owned by Wallet) |
| `Order`              | `orders`                 | sales                                |
| `NetworkStatusEntry` | `network_status_entries` | knowledge                            |
| `KnowledgeChunk`     | `knowledge_chunks`       | knowledge                            |

### Value Objects → Embedded Columns


| Value Object      | Embedded Into              | Columns Generated                                                                  |
| ----------------- | -------------------------- | ---------------------------------------------------------------------------------- |
| `PinPolicy`       | `user_accounts`            | `max_attempts`, `lock_duration_minutes`                                            |
| `Money` (balance) | `wallets`                  | `balance_amount`, `balance_currency`                                               |
| `Money` (amount)  | `wallet_transactions`      | `amount_value`, `amount_currency`, `balance_before_amount`, `balance_after_amount` |
| `OrderItem`       | `orders`                   | `network`, `plan_id`, `data_size_mb`, `price_amount`, `price_currency`             |
| `PhoneNumber`     | `orders` (via `OrderItem`) | `recipient_phone`, `recipient_prefix`, `recipient_detected_network`                |

### Repository Map


| Repository                     | Aggregate Root       | Allowed Callers                                       |
| ------------------------------ | -------------------- | ----------------------------------------------------- |
| `UserAccountRepository`        | `UserAccount`        | `IdentityService` only                                |
| `WalletRepository`             | `Wallet`             | `BillingService` only                                 |
| `OrderRepository`              | `Order`              | `PurchaseOrchestrationTools`, `DeliveryEventConsumer` |
| `NetworkStatusEntryRepository` | `NetworkStatusEntry` | `NetworkStatusService` only                           |
| `KnowledgeChunkRepository`     | `KnowledgeChunk`     | `DocumentIngestionService`, `KnowledgeService`        |


> `WalletTransaction` and `VirtualAccount` have **no repository**. They are accessed only by loading the parent `Wallet` aggregate. `OrderItem` and `PhoneNumber` have **no repository** — they are value objects embedded directly into the `orders` table.

---

## DDD Rules Enforced by This Design

1. **Aggregate boundary = transaction boundary.** One `@Transactional` method touches one aggregate root.
2. **No cross-aggregate direct object references.** `Order` holds `buyerChatId : Long`, not `UserAccount user`.
3. **Value objects are immutable.** `Money`, `PhoneNumber`, `OrderItem` have no setters — they are replaced, never mutated.
4. **Behaviour lives on the aggregate, not in the service.** `wallet.debit(amount)` not `billingService.deductFromWallet(id, amount)`.
5. **Enums are stored as strings.** All enums use `@Enumerated(EnumType.STRING)` — never ordinal.
6. **Optimistic locking on all mutable roots.** `@Version Long version` on `Wallet` and `Order`.
7. **UUIDs as PKs.** Generated by PostgreSQL `gen_random_uuid()`, mapped with `@GeneratedValue(strategy = GenerationType.UUID)`.
