# Phase 1: Architecture Refactoring - Dependency Analysis

**Phase**: 1 - Architecture Refactoring
**Last Updated**: 2026-01-09

---

## CHOAM.java Decomposition Dependencies

### Current Structure Analysis

CHOAM.java (1,796 lines) contains:

```
CHOAM.java
|
+-- Fields (state)
|   +-- Parameters (config)
|   +-- Ethereal reference (consensus)
|   +-- Fireflies reference (membership)
|   +-- Block storage (in-memory/file)
|   +-- Session state
|   +-- Checkpoint state
|   +-- View state
|
+-- Methods (behavior)
|   +-- submit() - transaction submission
|   +-- process() - block processing
|   +-- checkpoint() - checkpoint management
|   +-- viewChange() - view transitions
|   +-- sync() - state synchronization
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

### Extraction Order Rationale

The extraction order is designed to minimize refactoring risk:

1. **BlockStore** (P1-1) - Pure storage, no business logic dependencies
2. **ConsensusEngine** (P1-2) - Abstracts Ethereal, depends on BlockStore
3. **Components** (P1-3) - Business logic, depends on both
4. **AdmissionControl** (P1-4) - Cross-cutting, added after refactoring stable

---

## Component Dependency Map

### BlockStore Dependencies

```
BlockStore (new)
+-- Reads: Block data
+-- Writes: Block data, checkpoints
+-- External: File system or storage backend
|
+-- No dependencies on other CHOAM components
+-- Used by: CHOAM, BlockProcessor, CheckpointManager
```

**Interface Dependencies**:
- Digest (from cryptography)
- Block (from CHOAM domain)
- ULong (from utils)

### ConsensusEngine Dependencies

```
ConsensusEngine (new)
+-- BlockStore (for block persistence)
+-- External: Ethereal
|
+-- Used by: CHOAM, BlockProcessor
```

**Interface Dependencies**:
- Transaction (from CHOAM domain)
- Block (from CHOAM domain)
- View (from Fireflies)

### BlockProcessor Dependencies

```
BlockProcessor (extracted from CHOAM)
+-- BlockStore (storage)
+-- ConsensusEngine (consensus)
+-- External: SQL-State (state machine)
|
+-- Used by: CHOAM (coordinator)
```

### ViewManager Dependencies

```
ViewManager (extracted from CHOAM)
+-- External: Fireflies (membership)
+-- ConsensusEngine (consensus state)
|
+-- Used by: CHOAM (coordinator), BlockProcessor
```

### SessionManager Dependencies

```
SessionManager (extracted from CHOAM)
+-- ConsensusEngine (for proposal)
|
+-- Used by: CHOAM (coordinator), AdmissionController
```

### CheckpointManager Dependencies

```
CheckpointManager (extracted from CHOAM)
+-- BlockStore (checkpoint storage)
|
+-- Used by: CHOAM (coordinator), BlockProcessor
```

---

## Inner Class Dependencies

### Administration

```
Administration (inner -> extract)
+-- CHOAM state (weak reference)
+-- ConsensusEngine
+-- ViewManager
|
Decision: Extract - has own lifecycle, can be tested independently
```

### Associate

```
Associate (inner -> keep)
+-- CHOAM state (direct access)
+-- Multiple CHOAM methods
|
Decision: Keep inner - tightly coupled, no benefit to extraction
```

### Client

```
Client (inner -> extract)
+-- Parameters
+-- ConsensusEngine (for state queries)
|
Decision: Extract - could be reused by other components
```

### Formation

```
Formation (inner -> extract)
+-- ViewManager
+-- ConsensusEngine
+-- External: Fireflies
|
Decision: Extract - complex enough to warrant isolation
```

### Synchronizer

```
Synchronizer (inner -> extract)
+-- BlockStore
+-- ConsensusEngine
+-- External: Network
|
Decision: Extract - independent synchronization concerns
```

### Combiner

```
Combiner (inner -> keep)
+-- CHOAM state (direct)
+-- Simple combination logic
|
Decision: Keep inner - simple, state-coupled
```

### Trampoline

```
Trampoline (inner -> keep)
+-- CHOAM state (for callbacks)
+-- Control flow management only
|
Decision: Keep inner - control flow only, no independent testing
```

### TransSubmission

```
TransSubmission (inner -> keep)
+-- Simple DTO
+-- No behavior
|
Decision: Keep inner - simple data holder
```

---

## Dependency Injection Strategy

### Constructor Injection Pattern

```java
public class CHOAM {
    private final BlockStore blockStore;
    private final ConsensusEngine consensusEngine;
    private final ViewManager viewManager;
    private final SessionManager sessionManager;
    private final CheckpointManager checkpointManager;
    private final AdmissionController admissionController;

    public CHOAM(
        BlockStore blockStore,
        ConsensusEngine consensusEngine,
        ViewManager viewManager,
        SessionManager sessionManager,
        CheckpointManager checkpointManager,
        AdmissionController admissionController,
        Parameters params
    ) {
        this.blockStore = blockStore;
        this.consensusEngine = consensusEngine;
        this.viewManager = viewManager;
        this.sessionManager = sessionManager;
        this.checkpointManager = checkpointManager;
        this.admissionController = admissionController;
    }
}
```

### Factory Pattern for Creation

```java
public class CHOAMFactory {
    public static CHOAM create(Parameters params) {
        BlockStore blockStore = new FileBlockStore(params);
        ConsensusEngine consensusEngine = new EtherealConsensusEngine(params, blockStore);
        ViewManager viewManager = new ViewManager(params, consensusEngine);
        SessionManager sessionManager = new SessionManager(params, consensusEngine);
        CheckpointManager checkpointManager = new CheckpointManager(params, blockStore);
        AdmissionController admissionController = new TokenBucketAdmissionController(params);

        return new CHOAM(
            blockStore,
            consensusEngine,
            viewManager,
            sessionManager,
            checkpointManager,
            admissionController,
            params
        );
    }
}
```

### Testing with Mocks

```java
@Test
void shouldProcessBlockWithMockedDependencies() {
    // Arrange
    BlockStore mockBlockStore = mock(BlockStore.class);
    ConsensusEngine mockConsensus = mock(ConsensusEngine.class);
    // ... other mocks

    CHOAM choam = new CHOAM(
        mockBlockStore,
        mockConsensus,
        mockViewManager,
        mockSessionManager,
        mockCheckpointManager,
        mockAdmissionController,
        params
    );

    // Act & Assert
    // Can test CHOAM coordination without real dependencies
}
```

---

## External Dependencies

### Ethereal (Consensus)

```
Ethereal
+-- Used by: ConsensusEngine
+-- Interface: Ethereal API for consensus
+-- Changes: Wrapped by ConsensusEngine interface
```

### Fireflies (Membership)

```
Fireflies
+-- Used by: ViewManager, Formation
+-- Interface: Membership, gossip overlay
+-- Changes: Used through ViewManager abstraction
```

### SQL-State (State Machine)

```
SQL-State
+-- Used by: BlockProcessor
+-- Interface: JDBC state operations
+-- Changes: No wrapping needed
```

---

## Circular Dependency Prevention

### Potential Cycles to Avoid

```
Bad: CHOAM -> BlockProcessor -> CHOAM
Fix: BlockProcessor uses events/callbacks, not CHOAM reference

Bad: ViewManager -> ConsensusEngine -> ViewManager
Fix: ViewManager observes ConsensusEngine, doesn't inject

Bad: SessionManager -> CHOAM -> SessionManager
Fix: SessionManager uses callbacks for completion
```

### Event-Based Decoupling

```java
// Instead of direct reference
class BlockProcessor {
    private final CHOAM choam; // BAD: circular potential

    void onBlockComplete(Block block) {
        choam.notifyBlockComplete(block); // tight coupling
    }
}

// Use event/callback pattern
class BlockProcessor {
    private final BlockCompletionListener listener; // GOOD: decoupled

    void onBlockComplete(Block block) {
        listener.onBlockComplete(block);
    }
}

interface BlockCompletionListener {
    void onBlockComplete(Block block);
}
```

---

## Migration Path

### Phase 1 Migration Steps

1. **Create interfaces** (no code changes to CHOAM)
   - BlockStore interface
   - ConsensusEngine interface

2. **Implement adapters** (wraps existing code)
   - FileBlockStore wraps existing storage
   - EtherealConsensusEngine wraps Ethereal calls

3. **Inject into CHOAM** (minimal changes)
   - Add constructor parameters
   - Wire through factory

4. **Extract components** (incremental)
   - One component at a time
   - Tests after each extraction

5. **Clean up CHOAM** (final refactor)
   - Remove extracted code
   - Keep coordination logic only

### Rollback Points

Each step has a rollback point:
- After interface creation: Delete interfaces
- After adapters: Remove adapters, restore direct calls
- After injection: Restore original constructor
- After each extraction: Revert single component
- After cleanup: Full git revert to pre-Phase 1

---

## Dependency Verification

### Build-Time Verification

```bash
# After each extraction, verify build
./mvnw clean compile -pl choam

# Check for cycles using dependency analysis
./mvnw dependency:analyze -pl choam
```

### Runtime Verification

```java
@Test
void shouldHaveNoCyclicDependencies() {
    // Use ArchUnit or similar to verify no cycles
    ArchRuleDefinition.noClasses()
        .should().dependOnClassesThat()
        .resideInPackage("..choam..")
        .andShould().beDependedOnByClassesThat()
        .resideInPackage("..choam..")
        .check(classes);
}
```

---

**Dependencies Analyzed**: 2026-01-09
**Review Cadence**: Before each extraction
