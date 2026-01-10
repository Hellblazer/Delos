# CHOAM Architecture Documentation

**Project**: CHOAM Security & Architecture Refactoring
**Last Updated**: 2026-01-09
**Status**: Initial - Will be updated during Phase 1

---

## Current Architecture (Pre-Refactoring)

### CHOAM God Object

```
CHOAM.java (1,796 lines)
|
+-- Block Storage (embedded)
|   +-- store(), retrieve(), getLatest()
|   +-- checkpoint(), recover()
|
+-- Consensus Interaction (embedded)
|   +-- propose(), handleBlock()
|   +-- onViewChange()
|
+-- Session Management (embedded)
|   +-- submit(), createSession()
|   +-- transactionComplete()
|
+-- View Management (embedded)
|   +-- onViewChange(), getView()
|   +-- membershipUpdate()
|
+-- Inner Classes (~400 lines)
    +-- Administration
    +-- Associate
    +-- Client
    +-- Formation
    +-- Synchronizer
    +-- Combiner
    +-- Trampoline
    +-- TransSubmission
```

### Current Dependencies

```
CHOAM
  |
  +-- Ethereal (direct coupling)
  |     +-- Consensus ordering
  |     +-- Block production
  |
  +-- Fireflies (direct coupling)
  |     +-- Membership service
  |     +-- View changes
  |
  +-- SQL-State (direct coupling)
        +-- State machine operations
        +-- JDBC access
```

### Problems with Current Architecture

1. **God Object**: CHOAM.java has too many responsibilities
2. **Tight Coupling**: Cannot test components in isolation
3. **No Interfaces**: Cannot swap implementations
4. **Mixed Concerns**: Storage, consensus, sessions all interleaved
5. **Hard to Understand**: 1,796 lines of complex code

---

## Target Architecture (Post-Refactoring)

### Component Diagram

```
                     +------------------+
                     |      CHOAM       |
                     |   (Coordinator)  |
                     |    ~500 lines    |
                     +--------+---------+
                              |
        +----------+----------+----------+----------+
        |          |          |          |          |
        v          v          v          v          v
+-------+--+ +-----+-----+ +--+-------+ +-+--------+ +---+------+
|BlockStore| |Consensus  | |Session   | |Checkpoint| |Admission |
|Interface | |Engine     | |Manager   | |Manager   | |Controller|
+----+-----+ +-----+-----+ +----+-----+ +----+-----+ +----+-----+
     |             |            |            |            |
     v             v            |            |            |
+----+-----+ +-----+-----+      |            |            |
|FileBlock | |Ethereal   |      |            |            |
|Store     | |Consensus  |      |            |            |
+----------+ +-----+-----+      |            |            |
                   |            |            |            |
                   v            v            v            v
              +----+------------+------------+------------+----+
              |                    External                    |
              |   Ethereal | Fireflies | SQL-State | Storage   |
              +------------------------------------------------+
```

### Component Responsibilities

| Component | Responsibility | Est. Lines |
|-----------|---------------|------------|
| CHOAM (Coordinator) | Orchestration, lifecycle | ~500 |
| BlockStore | Block storage abstraction | ~150 |
| ConsensusEngine | Consensus interaction | ~200 |
| BlockProcessor | Block validation, processing | ~300 |
| ViewManager | View changes, membership | ~250 |
| SessionManager | Transaction sessions | ~200 |
| CheckpointManager | Checkpoint operations | ~200 |
| AdmissionController | Rate limiting | ~150 |

### Interface Definitions

#### BlockStore Interface

```java
public interface BlockStore {
    /**
     * Store a block.
     * @param block The block to store
     * @throws IllegalArgumentException if block is invalid
     */
    void store(Block block);

    /**
     * Retrieve a block by its hash.
     * @param hash The block hash
     * @return Optional containing block if found
     */
    Optional<Block> retrieve(Digest hash);

    /**
     * Get the latest block.
     * @return Optional containing latest block if any
     */
    Optional<Block> getLatest();

    /**
     * Create a checkpoint at given height.
     * @param height The block height to checkpoint
     */
    void checkpoint(ULong height);

    /**
     * Check if store contains a block.
     * @param hash The block hash to check
     * @return true if block exists
     */
    boolean contains(Digest hash);

    /**
     * Get blocks after a given height.
     * @param height Starting height (exclusive)
     * @return Stream of blocks
     */
    Stream<Block> getBlocksAfter(ULong height);
}
```

#### ConsensusEngine Interface

```java
public interface ConsensusEngine {
    /**
     * Propose a transaction for consensus.
     * @param transaction The transaction to propose
     * @return Future completing when transaction is ordered
     */
    CompletableFuture<Void> propose(Transaction transaction);

    /**
     * Handle a block from consensus.
     * @param block The block to process
     */
    void handleBlock(Block block);

    /**
     * Handle a view change.
     * @param newView The new view
     */
    void onViewChange(View newView);

    /**
     * Check if this node is the current leader.
     * @return true if leader
     */
    boolean isLeader();

    /**
     * Get current consensus height.
     * @return Current height
     */
    ULong getCurrentHeight();
}
```

#### AdmissionController Interface

```java
public interface AdmissionController {
    /**
     * Attempt to admit a transaction.
     * @param tx The transaction to admit
     * @return Admission result
     */
    AdmissionResult admit(Transaction tx);

    /**
     * Release a transaction (completed or rejected).
     * @param tx The transaction to release
     */
    void release(Transaction tx);

    /**
     * Get admission statistics.
     * @return Current stats
     */
    AdmissionStats getStats();
}

public enum AdmissionResult {
    ADMITTED,
    RATE_LIMITED,
    QUEUE_FULL,
    REJECTED
}
```

### Dependency Injection Pattern

```java
public class CHOAM {
    private final BlockStore blockStore;
    private final ConsensusEngine consensusEngine;
    private final BlockProcessor blockProcessor;
    private final ViewManager viewManager;
    private final SessionManager sessionManager;
    private final CheckpointManager checkpointManager;
    private final AdmissionController admissionController;

    public CHOAM(
        BlockStore blockStore,
        ConsensusEngine consensusEngine,
        BlockProcessor blockProcessor,
        ViewManager viewManager,
        SessionManager sessionManager,
        CheckpointManager checkpointManager,
        AdmissionController admissionController,
        Parameters params
    ) {
        this.blockStore = blockStore;
        this.consensusEngine = consensusEngine;
        this.blockProcessor = blockProcessor;
        this.viewManager = viewManager;
        this.sessionManager = sessionManager;
        this.checkpointManager = checkpointManager;
        this.admissionController = admissionController;
    }

    // CHOAM now only coordinates, doesn't implement
}
```

### Factory Pattern

```java
public class CHOAMFactory {
    public static CHOAM create(Parameters params, Context context) {
        // Create implementations
        BlockStore blockStore = new FileBlockStore(params.getStoragePath());
        ConsensusEngine consensusEngine = new EtherealConsensusEngine(
            context.getEthereal(),
            blockStore
        );
        ViewManager viewManager = new ViewManager(
            context.getFireflies(),
            consensusEngine
        );
        SessionManager sessionManager = new SessionManager(params);
        CheckpointManager checkpointManager = new CheckpointManager(
            blockStore,
            params
        );
        AdmissionController admissionController = new TokenBucketAdmissionController(
            params.getRateLimitConfig()
        );
        BlockProcessor blockProcessor = new BlockProcessor(
            blockStore,
            context.getSqlState()
        );

        // Wire together
        return new CHOAM(
            blockStore,
            consensusEngine,
            blockProcessor,
            viewManager,
            sessionManager,
            checkpointManager,
            admissionController,
            params
        );
    }
}
```

---

## Data Flow

### Transaction Submission Flow

```
Client
  |
  v
CHOAM.submit(tx)
  |
  v
AdmissionController.admit(tx)
  |
  +-- RATE_LIMITED --> Return error
  |
  v
SessionManager.createSession(tx)
  |
  v
ConsensusEngine.propose(tx)
  |
  v
[Ethereal consensus]
  |
  v
ConsensusEngine.handleBlock(block)
  |
  v
BlockProcessor.process(block)
  |
  v
BlockStore.store(block)
  |
  v
SessionManager.complete(tx)
```

### View Change Flow

```
Fireflies.onViewChange
  |
  v
ViewManager.handleViewChange(view)
  |
  v
ConsensusEngine.onViewChange(view)
  |
  v
CHOAM.reconfigure()
```

### Checkpoint Flow

```
BlockProcessor.onHeight(h)
  |
  +-- h % checkpointInterval == 0
  |
  v
CheckpointManager.checkpoint(h)
  |
  v
BlockStore.checkpoint(h)
```

---

## Testing Architecture

### Unit Test Structure

```
choam/src/test/java/
  +-- BlockStoreTest.java
  +-- ConsensusEngineTest.java
  +-- BlockProcessorTest.java
  +-- ViewManagerTest.java
  +-- SessionManagerTest.java
  +-- CheckpointManagerTest.java
  +-- AdmissionControllerTest.java
  +-- CHOAMIntegrationTest.java
  +-- ByzantineTest.java
  +-- DeterminismTest.java
```

### Mock Strategy

```java
// Unit tests use mocks
@Test
void shouldProcessBlock() {
    BlockStore mockStore = mock(BlockStore.class);
    ConsensusEngine mockEngine = mock(ConsensusEngine.class);

    CHOAM choam = new CHOAM(mockStore, mockEngine, ...);

    // Test coordination without real implementations
}
```

### Integration Test Strategy

```java
// Integration tests use real implementations
@Test
void shouldAchieveConsensus() {
    List<CHOAM> nodes = CHOAMFactory.createCluster(4);

    // Test with real Ethereal, Fireflies, etc.
}
```

---

## Evolution Notes

This document will be updated as Phase 1 progresses:

- [ ] Add actual interface definitions after P1-1
- [ ] Add component diagrams after P1-2
- [ ] Update data flows after P1-3
- [ ] Add performance characteristics after P2-4

---

**Architecture Established**: 2026-01-09
**Next Update**: After Phase 1 interface extraction
