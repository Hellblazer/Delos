# Phase 2 - Phase 3: Architecture Refactoring

## Overview

**Status**: PENDING
**Priority**: P2-P3
**Target Duration**: Week 7-12
**Task Count**: 11
**Dependencies**: Requires Phase 0-2 completion

---

## Objectives

1. Decompose CHOAM God Object (1738 lines) into smaller focused classes
2. Add missing abstraction interfaces
3. Implement Domain Builder pattern
4. Add circuit breaker for synchronization
5. Fix miscellaneous architecture debt

---

## CHOAM Decomposition

The CHOAM class at 1738 lines violates Single Responsibility Principle. It should be decomposed into focused components.

### Current Responsibilities in CHOAM.java

1. Block production (`constructBlock()`)
2. Block consumption (`consume()`)
3. Transaction execution coordination
4. Checkpoint management
5. View rotation
6. Synchronization protocol
7. Recovery protocol
8. Committee management (4 inner classes)
9. RBC coordination
10. Transaction submission routing

### Target Architecture

```
CHOAM.java (~300 lines) - Orchestration only
├── BlockProducer.java - Block creation
├── ConsensusCoordinator.java - Protocol orchestration
├── ViewManager.java - View rotation and recovery
├── TransactionRouter.java - Submission handling
└── SynchronizationProtocol.java - Bootstrap and recovery
```

See `.pm/designs/CHOAM_DECOMPOSITION.md` for detailed design.

---

## Task 25: Extract BlockProducer from CHOAM

### Bead: 869.25

**Depends On**: 869.1-4 (CHOAM bug fixes)

**Scope**: Extract block production logic into dedicated class.

**Responsibilities**:
- `constructBlock()` and related methods
- Block assembly from transactions
- Block signing and certification
- Pre-block handling

**Interface**:
```java
public interface BlockProducer {
    Block produce(List<Transaction> transactions, ViewContext context);
    void publish(CertifiedBlock block);
    void setBatchSize(int size);
}
```

**Acceptance Criteria**:
- [ ] BlockProducer class created
- [ ] All block production logic moved
- [ ] CHOAM delegates to BlockProducer
- [ ] Tests pass unchanged
- [ ] No performance regression

---

## Task 26: Extract ConsensusCoordinator

### Bead: 869.26

**Depends On**: 869.25 (BlockProducer)

**Scope**: Extract protocol orchestration logic.

**Responsibilities**:
- Ethereal integration
- Pre-block coordination
- Committee transitions
- FSM state management

**Interface**:
```java
public interface ConsensusCoordinator {
    void start();
    void stop();
    void onPreBlock(PreBlock preBlock);
    void onViewChange(ViewChange change);
}
```

**Acceptance Criteria**:
- [ ] ConsensusCoordinator class created
- [ ] Orchestration logic moved
- [ ] Clean interface to Ethereal
- [ ] Tests pass

---

## Task 27: Extract ViewManager

### Bead: 869.27

**Depends On**: 869.26 (ConsensusCoordinator)

**Scope**: Extract view rotation and recovery logic.

**Responsibilities**:
- View rotation protocol
- View state management
- Recovery coordination
- Pending views handling

**Interface**:
```java
public interface ViewManager {
    void rotateView(ViewChange change);
    ViewContext getCurrentView();
    void recover(HashedCertifiedBlock from);
}
```

**Acceptance Criteria**:
- [ ] ViewManager class created
- [ ] View rotation logic moved
- [ ] Recovery coordinated
- [ ] Tests pass

---

## Task 28: Extract TransactionRouter

### Bead: 869.28

**Depends On**: 869.26 (ConsensusCoordinator)

**Scope**: Extract transaction submission handling.

**Responsibilities**:
- Transaction validation
- Routing to correct committee member
- Rate limiting (with 869.16)
- Result callback handling

**Interface**:
```java
public interface TransactionRouter {
    SubmitResult submit(Transaction tx, Digest from);
    void setValidator(TransactionValidator validator);
}
```

**Acceptance Criteria**:
- [ ] TransactionRouter class created
- [ ] Submission logic moved
- [ ] Validation integrated
- [ ] Tests pass

---

## Task 29: Extract SynchronizationProtocol

### Bead: 869.29

**Depends On**: 869.27 (ViewManager)

**Scope**: Extract bootstrap and recovery protocol.

**Responsibilities**:
- Initial synchronization
- Checkpoint recovery
- State bootstrapping
- Gossip-based sync

**Interface**:
```java
public interface SynchronizationProtocol {
    void synchronize(HashedCertifiedBlock anchor);
    void bootstrap(Checkpoint checkpoint);
    boolean isSynchronized();
}
```

**Acceptance Criteria**:
- [ ] SynchronizationProtocol class created
- [ ] Bootstrap logic moved
- [ ] Recovery logic moved
- [ ] Tests pass

---

## Task 30: Add Circuit Breaker for Synchronization

### Bead: 869.30

**Depends On**: 869.29 (SynchronizationProtocol)

**Problem**: `awaitSynchronization()` has no circuit breaker - infinite retry under network partition.

**Fix**:
```java
public class SynchronizationProtocol {
    private final int maxAttempts = 10;
    private final AtomicInteger attempts = new AtomicInteger();

    public void synchronize() {
        int attempt = attempts.incrementAndGet();
        if (attempt > maxAttempts) {
            log.error("Max sync attempts exceeded, entering degraded mode");
            enterDegradedMode();
            return;
        }

        Duration backoff = Duration.ofMillis(100 * (1L << Math.min(attempt, 10)));
        scheduler.schedule(this::doSync, backoff);
    }
}
```

**Acceptance Criteria**:
- [ ] Circuit breaker implemented
- [ ] Exponential backoff working
- [ ] Degraded mode defined
- [ ] Max attempts configurable
- [ ] Test verifies circuit opens

---

## Task 31: Replace SQLException with OracleException

### Bead: 869.31

**Problem**: Oracle interface exposes `SQLException`, leaking implementation details.

**Impact**:
- Cannot implement Oracle with non-SQL backend
- All callers must handle SQLException
- Testability reduced

**Fix**:
```java
public interface Oracle {
    boolean check(Assertion assertion) throws OracleException;

    class OracleException extends Exception {
        public OracleException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
```

**Acceptance Criteria**:
- [ ] OracleException defined
- [ ] All methods updated
- [ ] SQLException wrapped internally
- [ ] Callers updated
- [ ] Tests updated

---

## Task 32: Add StateExecutor Abstraction

### Bead: 869.32

**Depends On**: 869.25 (BlockProducer)

**Problem**: CHOAM directly depends on SqlStateMachine details.

**Fix**:
```java
public interface StateExecutor {
    void execute(int index, Digest hash, Transaction tx, CompletableFuture<?> onComplete);
    void beginBlock(ULong height, Digest hash);
    void endBlock(ULong height, Digest hash);
}

public interface CheckpointProvider {
    File apply(ULong height);
}

public interface StateRestorer {
    void accept(HashedCertifiedBlock block, CheckpointState checkpoint);
}
```

**Impact**:
- Enables non-SQL state backends
- Better testability
- Cleaner separation

**Acceptance Criteria**:
- [ ] StateExecutor interface defined
- [ ] CheckpointProvider interface defined
- [ ] StateRestorer interface defined
- [ ] SqlStateMachine implements interfaces
- [ ] CHOAM uses interfaces

---

## Task 33: Add Domain Builder Pattern

### Bead: 869.33

**Problem**: Domain constructor has complex initialization with no validation.

**Fix**:
```java
public class Domain {
    public static class Builder {
        public Builder withMember(ControlledIdentifierMember member) { ... }
        public Builder withCheckpointDir(Path dir) { ... }
        public Builder withParams(Parameters.Builder params) { ... }
        public Builder validate() throws ConfigurationException { ... }
        public Domain build() { ... }
    }

    public static Builder builder() {
        return new Builder();
    }

    private Domain(Builder builder) {
        // Private constructor
    }
}
```

**Acceptance Criteria**:
- [ ] Builder class created
- [ ] Validation in validate()
- [ ] Private constructor
- [ ] Usage examples
- [ ] Tests use builder

---

## Task 34: Abstract X509 from Member Interface

### Bead: 869.34

**Problem**: Member interface exposes X509 certificate parsing.

**Impact**:
- Cannot mock Member without Bouncy Castle
- Certificate concerns mixed with identity
- Interface segregation violated

**Fix**:
```java
public interface Member extends Comparable<Member> {
    Digest getId();
}

public interface VerifiableMember extends Member {
    boolean verify(JohnHancock signature, InputStream message);
}

public class CertificateMemberFactory {
    public static Member from(X509Certificate cert) { ... }
}
```

**Acceptance Criteria**:
- [ ] Member interface simplified
- [ ] VerifiableMember separate
- [ ] Factory for certificates
- [ ] Tests updated
- [ ] No BC in Member interface

---

## Task 35: Fix Reflection Method Ordering Determinism

### Bead: 869.35

**Problem**: Reflection method ordering not deterministic across JVMs.

**Impact**:
- Non-deterministic behavior
- State machine divergence risk
- Hard to reproduce bugs

**Fix**:
1. Identify reflection usage patterns
2. Sort methods by name before use
3. Use stable ordering for all reflection
4. Add determinism tests

**Acceptance Criteria**:
- [ ] Reflection usages audited
- [ ] Stable ordering implemented
- [ ] Determinism test added
- [ ] No JVM-dependent behavior

---

## Definition of Done

### Per Task

- [ ] Design reviewed
- [ ] Implementation complete
- [ ] Tests updated
- [ ] No regressions
- [ ] Code reviewed
- [ ] Bead closed

### Phase Complete

- [ ] All 11 tasks complete
- [ ] CHOAM under 400 lines
- [ ] All abstractions in place
- [ ] Full test suite passes
- [ ] Architecture review sign-off

---

## Estimated Effort

| Task | Design | Implementation | Test | Review | Total |
|------|--------|----------------|------|--------|-------|
| 869.25 BlockProducer | 4h | 8h | 4h | 2h | 18h |
| 869.26 ConsensusCoord | 4h | 8h | 4h | 2h | 18h |
| 869.27 ViewManager | 3h | 6h | 3h | 2h | 14h |
| 869.28 TxRouter | 2h | 4h | 2h | 1h | 9h |
| 869.29 SyncProtocol | 3h | 6h | 3h | 2h | 14h |
| 869.30 Circuit breaker | 1h | 3h | 2h | 1h | 7h |
| 869.31 OracleException | 1h | 4h | 2h | 1h | 8h |
| 869.32 StateExecutor | 2h | 4h | 2h | 1h | 9h |
| 869.33 Domain Builder | 1h | 3h | 2h | 1h | 7h |
| 869.34 Member abstract | 2h | 4h | 2h | 1h | 9h |
| 869.35 Reflection order | 2h | 3h | 3h | 1h | 9h |
| **Total** | **25h** | **53h** | **29h** | **15h** | **122h** |

---

## Critical Path

```
869.1-4 → 869.25 → 869.26 → 869.27 → 869.29 → 869.30
         ↘ 869.32
         ↘ 869.28
```

Sequential CHOAM refactor: ~80h
Parallel independent tasks: ~42h

**Total with parallelism**: ~5-6 weeks

---

*Last Updated: 2025-12-31*
