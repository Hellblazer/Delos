# CHOAM God Object Decomposition Design

## Overview

This document describes the architectural refactoring to decompose the CHOAM.java God Object (1738 lines) into focused, maintainable components following Single Responsibility Principle.

**Current State**: Monolithic CHOAM class handling 10+ distinct responsibilities
**Target State**: 5-6 focused classes with clear boundaries and interfaces

---

## Problem Statement

### Current CHOAM Responsibilities

| Responsibility | Lines (approx) | Coupling |
|----------------|----------------|----------|
| Block production | ~200 | Ethereal, Committee |
| Block consumption | ~150 | Store, Execution |
| Transaction execution | ~100 | SqlStateMachine |
| Checkpoint management | ~150 | Store, Recovery |
| View rotation | ~200 | Committee, Fireflies |
| Synchronization | ~200 | Network, Store |
| Recovery | ~150 | Store, Network |
| Committee management | ~400 | 4 inner classes |
| RBC coordination | ~100 | ReliableBroadcaster |
| Transaction submission | ~100 | Session, Validation |

### Problems with Current Design

1. **Testing Complexity**: Cannot test block production without consensus
2. **Evolution Risk**: Changes to sync affect transaction handling
3. **Mental Load**: 1738 lines impossible to hold in working memory
4. **Circular Dependencies**: Committee <-> CHOAM tight coupling
5. **Concurrency Confusion**: Race conditions span multiple concerns

---

## Target Architecture

```
┌────────────────────────────────────────────────────────────┐
│                     CHOAM (Orchestrator)                    │
│  - Lifecycle management                                     │
│  - Component coordination                                   │
│  - FSM state transitions                                    │
│  - ~300 lines                                               │
└─────────────┬────────────────────────────────┬─────────────┘
              │                                │
    ┌─────────▼─────────┐          ┌──────────▼──────────┐
    │  BlockProducer    │          │ ConsensusCoordinator │
    │  - constructBlock │          │ - Ethereal integration│
    │  - signing        │          │ - Pre-block handling │
    │  - certification  │          │ - Committee FSM      │
    │  ~200 lines       │          │ ~300 lines          │
    └───────────────────┘          └──────────┬──────────┘
                                              │
              ┌───────────────────────────────┼───────────────────────────────┐
              │                               │                               │
    ┌─────────▼─────────┐          ┌─────────▼─────────┐          ┌─────────▼─────────┐
    │   ViewManager     │          │ TransactionRouter │          │SynchronizationProto│
    │ - View rotation   │          │ - Submission      │          │ - Bootstrap       │
    │ - Recovery coord  │          │ - Validation      │          │ - State sync      │
    │ - Pending views   │          │ - Rate limiting   │          │ - Recovery        │
    │ ~250 lines        │          │ ~150 lines        │          │ ~250 lines        │
    └───────────────────┘          └───────────────────┘          └───────────────────┘
```

---

## Interface Contracts

### Thread Safety Guarantees

All extracted components follow these thread safety contracts:

| Component | Thread Safety | Synchronization Strategy |
|-----------|---------------|-------------------------|
| CHOAM (Orchestrator) | Thread-safe | Single-threaded lifecycle, concurrent API |
| BlockProducer | Thread-safe | Stateless production, synchronized certification |
| ConsensusCoordinator | Thread-safe | FSM with atomic transitions, no external locks |
| ViewManager | Thread-safe | Copy-on-write for view state, CAS for updates |
| TransactionRouter | Thread-safe | Lock-free queues, atomic rate limiters |
| SynchronizationProtocol | Thread-safe | Single sync thread with async callbacks |

**Concurrency Rules**:
1. No component may hold locks while calling another component
2. Callbacks from components to CHOAM must be non-blocking
3. State queries return immutable snapshots
4. All public methods are re-entrant safe

### Exception Catalog

| Exception | Thrown By | Recovery Protocol |
|-----------|-----------|-------------------|
| `ConsensusException` | ConsensusCoordinator | Log + notify CHOAM, coordinator stops |
| `BlockProductionException` | BlockProducer | Retry with backoff, max 3 attempts |
| `ViewTransitionException` | ViewManager | Revert to previous view, log incident |
| `SynchronizationException` | SynchronizationProtocol | Enter degraded mode, circuit breaker |
| `TransactionRejectedException` | TransactionRouter | Return error to client, no retry |
| `StateCorruptionException` | Any | FATAL - stop node, require manual recovery |

**Exception Handling Rules**:
1. Transient failures (network, timeout): Retry with exponential backoff
2. Permanent failures (invalid state, corruption): Propagate to CHOAM for shutdown
3. Client errors (invalid transaction): Return immediately, no retry

### Lifecycle State Machine

All components follow this lifecycle:

```
                    +-----------+
                    |  CREATED  |
                    +-----+-----+
                          |
                          | start()
                          v
                    +-----------+
           +------->| STARTING  |
           |        +-----+-----+
           |              |
           |              | [initialization complete]
           |              v
           |        +-----------+
           |        |  RUNNING  |<----+
           |        +-----+-----+     |
           |              |           |
           |              | stop()    | recover()
           |              v           |
           |        +-----------+     |
           |        | STOPPING  |-----+
           |        +-----+-----+
           |              |
           |              | [cleanup complete]
           |              v
           |        +-----------+
           +--------+  STOPPED  |
             restart()+----------+
```

**State Transition Rules**:
1. `start()` only valid from CREATED or STOPPED
2. `stop()` valid from any state except STOPPED
3. `stop()` during STARTING cancels startup
4. `recover()` from STOPPING returns to RUNNING if successful
5. State queries always return current state atomically

**Idempotency Requirements**:
- `start()`: Idempotent if already RUNNING
- `stop()`: Idempotent if already STOPPED
- All other methods: NOT idempotent unless explicitly documented

---

## Component Specifications

### 1. CHOAM (Orchestrator)

**Purpose**: Coordinate all components, manage lifecycle, expose public API

**Responsibilities**:
- Start/stop lifecycle
- Component wiring and injection
- Public API delegation
- FSM context management
- Metrics aggregation

**Interface**:
```java
public class CHOAM {
    // Construction
    public CHOAM(Parameters params, BlockProducer producer,
                 ConsensusCoordinator coordinator, ViewManager viewManager,
                 TransactionRouter router, SynchronizationProtocol sync);

    // Lifecycle
    public void start();
    public void stop();

    // Public API (delegated)
    public Session newSession(Signer signer);
    public SubmitResult submit(Transaction tx, Digest from);
    public ViewContext getCurrentView();

    // Event handling
    void onBlockProduced(CertifiedBlock block);
    void onViewChange(ViewChange change);
}
```

**Dependencies**:
- All extracted components (injected)
- Parameters (configuration)
- Metrics (observation)

---

### 2. BlockProducer

**Purpose**: Handle all block production logic

**Responsibilities**:
- Construct blocks from transactions
- Sign blocks with committee keys
- Manage block certification
- Handle pre-block to block conversion

**Interface**:
```java
public interface BlockProducer {
    // Block production
    Block produce(List<Transaction> transactions, BlockContext context);
    CertifiedBlock certify(Block block, List<Certification> certs);

    // Publication
    void publish(CertifiedBlock block);

    // Configuration
    void setBatchSize(int size);
    void setBlockTimeout(Duration timeout);
}

public class DefaultBlockProducer implements BlockProducer {
    private final Signer signer;
    private final BlockStore store;
    private final Metrics metrics;

    // Implementation...
}
```

**Contract Details**:

| Method | Thread Safety | Exceptions | Idempotency |
|--------|---------------|------------|-------------|
| `produce()` | Concurrent safe | `BlockProductionException` on signing failure | NOT idempotent (new block each call) |
| `certify()` | Synchronized | `BlockProductionException` if threshold not met | Idempotent for same block |
| `publish()` | Concurrent safe | None (fire-and-forget) | Idempotent |

**Preconditions**:
- `produce()`: transactions non-empty, context valid
- `certify()`: block not null, certs.size() >= threshold
- `publish()`: block must be certified

**Postconditions**:
- `produce()`: returns signed block with valid hash
- `certify()`: returns block with certification proof
- `publish()`: block submitted to ReliableBroadcaster

**Dependencies**:
- Signer (cryptographic)
- BlockStore (persistence)
- Metrics (observation)

---

### 3. ConsensusCoordinator

**Purpose**: Orchestrate Ethereal consensus integration

**Responsibilities**:
- Manage Ethereal lifecycle
- Handle pre-block processing
- Coordinate committee transitions
- Manage consensus FSM states

**Interface**:
```java
public interface ConsensusCoordinator {
    // Lifecycle
    void start(Committee committee);
    void stop();

    // Pre-block handling
    void onPreBlock(PreBlock preBlock);
    void onPreBlockEmpty();

    // Committee management
    void transitionTo(Committee newCommittee);
    Committee getCurrentCommittee();

    // State queries
    boolean isActive();
    ConsensusState getState();
}
```

**Contract Details**:

| Method | Thread Safety | Exceptions | Idempotency |
|--------|---------------|------------|-------------|
| `start()` | Synchronized | `IllegalStateException` if already running | Idempotent |
| `stop()` | Synchronized | None | Idempotent |
| `onPreBlock()` | Single-caller (from Ethereal) | `ConsensusException` | NOT idempotent |
| `onPreBlockEmpty()` | Single-caller | None | Idempotent |
| `transitionTo()` | Synchronized | `ViewTransitionException` | NOT idempotent |
| `getCurrentCommittee()` | Concurrent safe | None | N/A (query) |

**Critical Invariants**:
1. Only one committee active at a time
2. Pre-blocks processed in order from Ethereal
3. Committee transitions are atomic
4. State visible to queries is always consistent

**Failure Semantics**:
- `onPreBlock()` failure: Log error, notify CHOAM, Ethereal will retry
- `transitionTo()` failure: Revert to previous committee, log incident

**Dependencies**:
- Ethereal (consensus engine)
- BlockProducer (block creation)
- Committee (current role)

---

### 4. ViewManager

**Purpose**: Handle view rotation and recovery coordination

**Responsibilities**:
- Manage view state machine
- Coordinate view rotations
- Handle recovery from checkpoints
- Manage pending views

**Interface**:
```java
public interface ViewManager {
    // View lifecycle
    void installView(ViewContext newView);
    void rotateView(ViewChange change);
    ViewContext getCurrentView();

    // Recovery
    void recover(HashedCertifiedBlock from, CheckpointState state);
    boolean needsRecovery();

    // Pending views
    void addPendingView(Digest viewId, ViewContext pending);
    ViewContext getPendingView(Digest viewId);
}
```

**Contract Details**:

| Method | Thread Safety | Exceptions | Idempotency |
|--------|---------------|------------|-------------|
| `installView()` | Synchronized | `ViewTransitionException` | NOT idempotent |
| `rotateView()` | Synchronized | `ViewTransitionException` | NOT idempotent |
| `getCurrentView()` | Concurrent safe (returns snapshot) | None | N/A (query) |
| `recover()` | Single-caller | `SynchronizationException` | NOT idempotent |
| `needsRecovery()` | Concurrent safe | None | N/A (query) |

**View State Invariants**:
1. Only one current view at any time
2. View transitions are serialized
3. Pending views do not affect current view
4. Recovery completes before view changes

**Recovery Contract**:
- `recover()` blocks until complete or fails
- On failure, node remains in degraded state
- Circuit breaker limits recovery attempts

**Dependencies**:
- ViewStore (persistence)
- SynchronizationProtocol (recovery)
- ConsensusCoordinator (transitions)

---

### 5. TransactionRouter

**Purpose**: Handle transaction submission and validation

**Responsibilities**:
- Validate incoming transactions
- Route to appropriate handler
- Manage submission rate limiting
- Handle result callbacks

**Interface**:
```java
public interface TransactionRouter {
    // Submission
    SubmitResult submit(Transaction tx, Digest from);
    CompletableFuture<SubmitResult> submitAsync(Transaction tx, Digest from);

    // Validation
    void setValidator(TransactionValidator validator);
    void setRateLimiter(RateLimiter limiter);

    // Batch handling
    List<Transaction> drainBatch(int maxSize);
}
```

**Contract Details**:

| Method | Thread Safety | Exceptions | Idempotency |
|--------|---------------|------------|-------------|
| `submit()` | Concurrent safe | `TransactionRejectedException` | NOT idempotent |
| `submitAsync()` | Concurrent safe | Returns failed future | NOT idempotent |
| `setValidator()` | Synchronized | None | Idempotent |
| `setRateLimiter()` | Synchronized | None | Idempotent |
| `drainBatch()` | Synchronized | None | NOT idempotent |

**Submission Guarantees**:
1. Validation happens synchronously before queuing
2. Rate limiting applied before validation
3. Rejected transactions return immediately (no queuing)
4. Accepted transactions may still fail at consensus

**Rate Limiting Contract** (integrates with 869.18/Delos-qon):
- Per-client limits applied first
- Global limits applied second
- Rejection returns `RATE_LIMITED` status
- Metrics track rejection counts

**Dependencies**:
- TransactionValidator (validation logic)
- RateLimiter (DoS protection)
- ConsensusCoordinator (submission target)

---

### 6. SynchronizationProtocol

**Purpose**: Handle bootstrap and state synchronization

**Responsibilities**:
- Initial synchronization from network
- Checkpoint-based recovery
- State bootstrapping
- Gossip-based sync protocol

**Interface**:
```java
public interface SynchronizationProtocol {
    // Synchronization
    void synchronize(HashedCertifiedBlock anchor);
    void bootstrap(Checkpoint checkpoint);

    // State queries
    boolean isSynchronized();
    SyncProgress getProgress();

    // Circuit breaker
    void setMaxAttempts(int max);
    void setBackoffStrategy(BackoffStrategy strategy);
    void enterDegradedMode();
}
```

**Contract Details**:

| Method | Thread Safety | Exceptions | Idempotency |
|--------|---------------|------------|-------------|
| `synchronize()` | Single-caller | `SynchronizationException` | NOT idempotent |
| `bootstrap()` | Single-caller | `SynchronizationException` | NOT idempotent |
| `isSynchronized()` | Concurrent safe | None | N/A (query) |
| `getProgress()` | Concurrent safe | None | N/A (query) |
| `enterDegradedMode()` | Synchronized | None | Idempotent |

**Circuit Breaker Contract** (addresses 869.30):
```java
public class SyncCircuitBreaker {
    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile CircuitState state = CircuitState.CLOSED;

    public boolean allowRequest() {
        return state != CircuitState.OPEN;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        state = CircuitState.CLOSED;
    }

    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= DEFAULT_MAX_ATTEMPTS) {
            state = CircuitState.OPEN;
            scheduleReset();
        }
    }
}
```

**Synchronization Invariants**:
1. Only one sync operation active at a time
2. Circuit breaker prevents infinite retry loops
3. Degraded mode allows partial operation
4. Progress reporting is always accurate

**Dependencies**:
- Network (gossip)
- BlockStore (persistence)
- ViewManager (recovery target)

---

## Extraction Order

The extraction must follow a specific order to maintain working code at each step:

```
Step 1: Extract BlockProducer
        - Lowest coupling
        - Clear boundaries
        - Tests: Block production tests

Step 2: Extract TransactionRouter
        - Depends on BlockProducer indirectly
        - Clear submission interface
        - Tests: Submission tests

Step 3: Extract ConsensusCoordinator
        - Central orchestration
        - Uses BlockProducer
        - Tests: Consensus integration tests

Step 4: Extract ViewManager
        - Uses ConsensusCoordinator
        - Recovery coordination
        - Tests: View rotation tests

Step 5: Extract SynchronizationProtocol
        - Uses ViewManager
        - Network interaction
        - Tests: Sync tests

Step 6: Add Circuit Breaker
        - Final enhancement
        - Requires SynchronizationProtocol
        - Tests: Failure scenario tests
```

---

## Interface Boundaries

### Internal Communication

Components communicate through well-defined interfaces:

```
CHOAM
  │
  ├──▶ BlockProducer.produce()
  │          │
  │          └──▶ publish() ──▶ ReliableBroadcaster
  │
  ├──▶ ConsensusCoordinator.onPreBlock()
  │          │
  │          └──▶ BlockProducer.produce()
  │
  ├──▶ ViewManager.rotateView()
  │          │
  │          └──▶ ConsensusCoordinator.transitionTo()
  │
  ├──▶ TransactionRouter.submit()
  │          │
  │          └──▶ ConsensusCoordinator validation
  │
  └──▶ SynchronizationProtocol.synchronize()
             │
             └──▶ ViewManager.recover()
```

### Event Flow

```
External Event                Component                    Action
─────────────────────────────────────────────────────────────────
Ethereal PreBlock    ──▶  ConsensusCoordinator  ──▶  BlockProducer.produce()
Transaction Submit   ──▶  TransactionRouter     ──▶  Queue/Validate
View Change Signal   ──▶  CHOAM                 ──▶  ViewManager.rotateView()
Sync Required        ──▶  CHOAM                 ──▶  SynchronizationProtocol.sync()
Recovery Complete    ──▶  SynchronizationProto  ──▶  ViewManager.recover()
```

---

## Testing Strategy

### Unit Tests (Per Component)

| Component | Test Focus |
|-----------|------------|
| BlockProducer | Block assembly, signing, certification |
| ConsensusCoordinator | Pre-block handling, FSM transitions |
| ViewManager | View rotation, recovery coordination |
| TransactionRouter | Validation, rate limiting, routing |
| SynchronizationProtocol | Sync progress, circuit breaker |
| CHOAM | Integration, lifecycle |

### Integration Tests

1. **Block Flow**: Transaction -> Router -> Producer -> Publish
2. **View Rotation**: Signal -> ViewManager -> Coordinator -> New Committee
3. **Recovery**: Sync -> Bootstrap -> ViewManager -> Coordinator
4. **Full Consensus**: Multi-node with all components

### Regression Tests

All existing CHOAM tests must pass without modification:
- `ChoamTest.java`
- `SynchronizationTest.java`
- `ViewRotationTest.java`
- `ConsensusIntegrationTest.java`

---

## Migration Plan

### Phase 1: Interface Definition (1 day)
- Define all interfaces
- No implementation changes
- Code review

### Phase 2: BlockProducer Extraction (2 days)
- Extract implementation
- Add delegation in CHOAM
- Verify tests pass

### Phase 3: TransactionRouter Extraction (1 day)
- Extract implementation
- Add delegation
- Verify tests

### Phase 4: ConsensusCoordinator Extraction (2 days)
- Extract implementation
- Update BlockProducer integration
- Verify tests

### Phase 5: ViewManager Extraction (2 days)
- Extract implementation
- Update coordinator integration
- Verify tests

### Phase 6: SynchronizationProtocol Extraction (2 days)
- Extract implementation
- Add circuit breaker
- Verify tests

### Phase 7: Cleanup (1 day)
- Remove dead code from CHOAM
- Final test verification
- Documentation update

---

## Risk Mitigation

### Risk: Breaking Change in API

**Mitigation**: Keep public CHOAM API unchanged. All refactoring internal.

### Risk: Performance Regression

**Mitigation**: Benchmark before/after. No additional object allocation in hot paths.

### Risk: Deadlock Introduction

**Mitigation**: Clear locking hierarchy. No cross-component locks.

### Risk: Test Flakiness

**Mitigation**: Run full test suite at each extraction step. No merge until green.

---

## Success Criteria

1. **CHOAM.java < 400 lines**
2. **Each component < 300 lines**
3. **All existing tests pass**
4. **No performance regression > 5%**
5. **Clear component boundaries**
6. **Improved testability** (mock individual components)
7. **Documentation complete**

---

## References

- CHOAM source: `/choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java`
- Architectural critique: `critique::architecture::delos-comprehensive-2025-12-31`
- Paper reference: `paper::bft-to-blockchain`

---

## Related Documents

- `.pm/ROLLBACK_STRATEGY.md` - Rollback criteria and procedures
- `.pm/REMEDIATION_PLAN_PHASE2.md` - Master remediation plan
- `critique::architecture::delos-comprehensive-2025-12-31` - Source critique

---

*Last Updated: 2025-12-31*
*Author: Strategic Planner Agent*
*Revision: Added interface contracts per Delos-a4s plan hardening*
