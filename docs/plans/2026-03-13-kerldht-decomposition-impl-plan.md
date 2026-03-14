# KerlDHT Decomposition Implementation Plan

**Bead**: Delos-izm.3.1 (KerlDHT Decomposition)
**RDR**: RDR-001 (accepted), Phase 3, Item 3.1
**Design**: docs/plans/2026-03-13-kerldht-decomposition-design.md
**Date**: 2026-03-13
**Estimated effort**: ~4 days

## Executive Summary

Decompose KerlDHT.java (2,379 lines) into focused, single-responsibility classes,
reducing it to an orchestrator under 1,500 lines. This work proceeds in two phases:
Phase A resolves a critical security gap (unvalidated reconciliation events) and
Phase B performs four incremental extraction steps. All work maintains KerlDHT's
public API unchanged and runs the full thoth test suite after each step.

## Bead Hierarchy

```
Delos-izm (epic, P0) — RDR-001: Technical Debt & Security Gap Remediation
└── Delos-izm.3 (feature, P2) — Phase 3: Structural Improvements
    └── Delos-izm.3.1 (task, P2) — KerlDHT Decomposition
        ├── Delos-7gj (task, P2) — 3.1a Phase A: Reconciliation Event Validation
        ├── Delos-0x5 (task, P2) — 3.1b Phase B Step 1: Extract DhtMetricsCollector
        ├── Delos-ard (task, P2) — 3.1c Phase B Step 2: Extract DhtReconciliationService
        ├── Delos-alw (task, P2) — 3.1d Phase B Step 3: Consolidate validation into pipeline
        └── Delos-62g (task, P2) — 3.1e Phase B Step 4: Slim KerlDHT to orchestrator
```

## Dependency Graph

```
Delos-7gj (Phase A)
    │
    ▼
Delos-0x5 (B.Step1: Metrics)
    │
    ▼
Delos-ard (B.Step2: Reconciliation)
    │
    ▼
Delos-alw (B.Step3: Validation)
    │
    ▼
Delos-62g (B.Step4: Slim KerlDHT)
```

All tasks are strictly sequential. No parallelization opportunities exist because
all tasks modify KerlDHT.java.

## Critical Path

All 5 tasks are on the critical path (linear chain). Each task blocks the next.
Total estimated duration: ~4 days.

---

## Phase A: Reconciliation Event Validation (Security Fix)

**Bead**: Delos-7gj
**Estimated effort**: ~1 day
**Risk level**: Medium

### Problem

`Reconcile.update()` (KerlDHT.java:2039-2049) receives events from a single peer
with zero validation. A Byzantine peer can inject arbitrary KERI events into the
DHT via reconciliation. This is the Phase 5 TODO at line 2045-2047.

| Path           | Consensus     | Pre-validation | Post-validation | Risk     |
|----------------|---------------|----------------|-----------------|----------|
| Write (append) | BFT quorum    | Structural     | Async pipeline  | Low      |
| Read (get*)    | BFT quorum    | --             | Freshness+sig   | Low      |
| Reconciliation | Single peer   | None           | None            | CRITICAL |

### TDD Sequence

1. **Write failing test**: `DhtReconciliationValidationTest.java`
   - Test: Byzantine peer invalid events are rejected
   - Test: Valid events accepted (two-pass inception-first ordering)
   - Test: Circuit breaker fail-open behavior during infrastructure issues
   - Test: Byzantine signals recorded for rejected events
   - Test: Mixed batch with valid and invalid events

2. **Implement coordinate extraction utility** in `DhtValidationPipeline`
   - Static method: `extractCoordinates(KeyEventWithAttachments)` -> `EventCoordinates`
   - Handle oneof: InceptionEvent, RotationEvent, InteractionEvent
   - Reference: `ProtobufEventFactory.digestOf(KeyEventWithAttachments, algo)` at
     `stereotomy/src/main/java/.../ProtobufEventFactory.java:87`
   - Proto mapping:
     - InceptionEvent -> `inception.specification.header.{sequenceNumber, ilk}`, `inception.identifier`
     - RotationEvent -> `rotation.specification.header.{identifier, sequenceNumber, ilk}`
     - InteractionEvent -> `interaction.specification.header.{identifier, sequenceNumber, ilk}`

3. **Implement `validateReconciliationBatch()`** in `DhtValidationPipeline`
   - Pass 1: Sort events by sequence number; process inception/establishment events first
   - Pass 2: Validate dependent events against now-available establishment events via Ani
   - Per event: verify signature, check self-addressing identifier digest
   - Return: List of validated events (filter out invalid)

4. **Wire into `Reconcile.update()`** (KerlDHT.java:2039-2049) as pre-insertion gate
   - Only validated events proceed to `kerlSpace.update()`
   - Replace TODO comment with validation call

5. **Record Byzantine signals** for each rejected event
   - `byzantineProvider.recordValidationFailure(peerId, "reconciliation_invalid_event")`
   - Reuse existing pipeline circuit breaker (fail-open)

### Files

| File | Action | Lines affected |
|------|--------|---------------|
| `thoth/src/main/java/.../DhtValidationPipeline.java` | Add methods | +50-80 lines |
| `thoth/src/main/java/.../KerlDHT.java` | Wire validation | Lines 2039-2049 |
| `thoth/src/test/java/.../DhtReconciliationValidationTest.java` | NEW | ~150 lines |

### Success Criteria

- [ ] `validateReconciliationBatch()` exists in `DhtValidationPipeline`
- [ ] Coordinate extraction handles all 3 event types (inception/rotation/interaction)
- [ ] Two-pass ordering (inception first) implemented
- [ ] `Reconcile.update()` calls validation before `kerlSpace.update()`
- [ ] Byzantine signals recorded for rejected events
- [ ] Circuit breaker fail-open preserved
- [ ] `DhtReconciliationValidationTest` passes
- [ ] Full thoth test suite passes: `./mvnw test -pl thoth`

### Test Command

```bash
./mvnw test -pl thoth -Dtest=DhtReconciliationValidationTest  # New test
./mvnw test -pl thoth                                          # Full suite
```

---

## Phase B Step 1: Extract DhtMetricsCollector

**Bead**: Delos-0x5
**Depends on**: Delos-7gj (Phase A)
**Estimated effort**: ~0.5 day
**Risk level**: Low

### What to Extract

5 methods from KerlDHT (Cluster H — metrics and health monitoring):

| Method | KerlDHT line | Description |
|--------|-------------|-------------|
| `isHealthy()` | 276 | SLA compliance check |
| `getHealthSnapshot()` | 285 | Current health metrics |
| `startPoolMonitoring()` | 1968-1986 | Periodic pool sampling |
| `stopPoolMonitoring()` | 1992-1998 | Cancel monitoring task |
| `isPoolExhausted()` | 1949-1962 | Pool utilization check |

Fields moved: `poolMonitoringTask`, health SLA threshold references.

### TDD Sequence

1. Write characterization tests capturing current behavior of all 5 methods
2. Create `DhtMetricsCollector.java`
3. Move methods and fields
4. Update KerlDHT to instantiate and delegate
5. Run full test suite

### Files

| File | Action |
|------|--------|
| `thoth/src/main/java/.../DhtMetricsCollector.java` | NEW (~100 lines) |
| `thoth/src/main/java/.../KerlDHT.java` | Remove methods, add delegation (-100 lines net) |

### Key Tests to Verify

- `KerlDHTHealthCheckTest.java` — health monitoring
- `KerlDHTLifecycleTest.java` — start/stop lifecycle
- `PerformanceBaselineTest.java` — performance baselines
- `PerformanceSLATest.java` — SLA validation

### Success Criteria

- [ ] `DhtMetricsCollector.java` exists with 5 extracted methods
- [ ] KerlDHT delegates all health/monitoring calls
- [ ] KerlDHT public API unchanged
- [ ] All thoth tests pass: `./mvnw test -pl thoth`

### Test Command

```bash
./mvnw test -pl thoth
```

---

## Phase B Step 2: Extract DhtReconciliationService

**Bead**: Delos-ard
**Depends on**: Delos-0x5 (B Step 1)
**Estimated effort**: ~1 day
**Risk level**: Medium

### What to Extract

Reconcile inner class + reconciliation protocol methods (Cluster C):

| Component | KerlDHT line | Description |
|-----------|-------------|-------------|
| `Reconcile` inner class | 2009-2050 | `Reconciliation` interface impl |
| `reconcile(Update, ReconciliationService)` | 1811-1826 | Process received update |
| `reconcile(ReconciliationService, Integer)` | 1828-1844 | Initiate reconciliation |
| `reconcile(Duration)` | 1846-1872 | Periodic reconciliation loop |
| `schedule(Duration)` | 1874-1876 | Schedule next reconciliation |
| `keyIntervals()` | helper | Compute key intervals |

Fields moved: `reconcileComms`, `kerlSpace` reference, `fpr` (false positive rate), `reconcileLog`.

### TDD Sequence

1. Use `mcp__sequential-thinking__sequentialthinking` to analyze `Reconcile` class field dependencies
2. Write characterization tests capturing reconciliation behavior
3. Create `DhtReconciliationService.java`
4. Move inner class, methods, and fields
5. `DhtReconciliationService` receives dependencies via constructor injection
6. KerlDHT instantiates and delegates
7. Run full test suite

### Files

| File | Action |
|------|--------|
| `thoth/src/main/java/.../DhtReconciliationService.java` | NEW (~300 lines) |
| `thoth/src/main/java/.../KerlDHT.java` | Remove Reconcile class + methods (-300 lines net) |

### Key Tests to Verify

- `AbstractDhtTest.java`, `KerlDhtTest.java` — core DHT operations
- `DhtRebalanceTest.java` — reconciliation protocol
- `DhtReconciliationValidationTest.java` — from Phase A
- `ByzantineFaultToleranceTest.java` — Byzantine detection

### Risk Mitigation

The `Reconcile` inner class accesses `KerlDHT.this` fields (tight coupling). Mitigation:
pass required dependencies (`kerlSpace`, `kerlPool`, `member`, `context`, `fpr`,
`validationPipeline`, `byzantineProvider`) via constructor injection.

### Success Criteria

- [ ] `DhtReconciliationService.java` exists
- [ ] `Reconcile` inner class removed from KerlDHT
- [ ] All reconciliation protocol logic delegated
- [ ] Reconciliation validation (from Phase A) preserved
- [ ] KerlDHT public API unchanged
- [ ] All thoth tests pass: `./mvnw test -pl thoth`

### Test Command

```bash
./mvnw test -pl thoth
```

---

## Phase B Step 3: Consolidate Validation into DhtValidationPipeline

**Bead**: Delos-alw
**Depends on**: Delos-ard (B Step 2)
**Estimated effort**: ~1 day
**Risk level**: Medium

### What to Move

Response validation methods from KerlDHT into `DhtValidationPipeline`:

| Method | KerlDHT line | Description |
|--------|-------------|-------------|
| `validateResponseFreshness()` | 348+ | Timestamp + nonce checking |
| `verifyResponseSignature()` | 1803-1809 | DhtClient signature check |
| `verifyWriteAcknowledgment()` | 2172+ | Write response structural check |
| `validateKeyStatesStructure()` | 2185+ | KeyStates structural integrity |
| `validateEventWithAttachmentsStructure()` | 2203+ | Event structural validation |

Field moved: `NonceVerifier` (line 132, created at 184, closed at 1350).

### TDD Sequence

1. Write characterization tests for each validation method
2. Move methods to `DhtValidationPipeline` (or create wrapper)
3. Move `NonceVerifier` ownership to pipeline
4. Update all KerlDHT callers to use pipeline methods:
   - Line 1559: `verifyWriteAcknowledgment` call
   - Line 1625: `verifyResponseSignature` call
   - Line 1730: `verifyResponseSignature` call
   - Line 1740: `validateResponseFreshness` call
5. Update `KerlDHT.stop()` (line 1350) — NonceVerifier cleanup moves to pipeline
6. Run full test suite

### Files

| File | Action |
|------|--------|
| `thoth/src/main/java/.../DhtValidationPipeline.java` | Add methods (+200 lines) |
| `thoth/src/main/java/.../KerlDHT.java` | Remove methods, update callers (-200 lines net) |

### Key Tests to Verify

- `KerlDHTResponseFreshnessTest.java` — freshness validation
- `KerlDHTReadSignatureVerificationTest.java` — read signature checks
- `KerlDHTMutateSignatureVerificationTest.java` — write signature checks
- `KerlDHTNonceVerificationTest.java` — nonce verification
- `DhtValidationPipelineTest.java` — pipeline tests
- `NonceVerifierTest.java` — nonce verifier unit tests

### Risk Mitigation

Validation methods reference KerlDHT fields (`byzantineProvider`, `byzantineCoordinator`,
`operationTimeout`, `member`). Mitigation: these are already dependencies of
`DhtValidationPipeline` (it already has `byzantineProvider`) or can be passed
as method parameters.

### Success Criteria

- [ ] All 5 validation methods moved to `DhtValidationPipeline`
- [ ] `NonceVerifier` owned by `DhtValidationPipeline`
- [ ] KerlDHT callers updated to use pipeline
- [ ] NonceVerifier lifecycle managed by pipeline
- [ ] KerlDHT public API unchanged
- [ ] All thoth tests pass: `./mvnw test -pl thoth`

### Test Command

```bash
./mvnw test -pl thoth
```

---

## Phase B Step 4: Slim KerlDHT to Orchestrator

**Bead**: Delos-62g
**Depends on**: Delos-alw (B Step 3)
**Estimated effort**: ~0.5 day
**Risk level**: Low

### What Remains

After all extractions, KerlDHT retains only:
- **Cluster A**: Core DHT read operations (8 methods) — quorum-based reads
- **Cluster B**: Core DHT write operations (9 methods) — quorum-based writes
- **Cluster F**: Quorum consensus management (5 methods) — read/mutate/completeIt
- **Cluster G**: Lifecycle management (4 methods) — start/stop/close
- **Cluster L**: Serialization and interface adaptation (3 methods)

### Tasks

1. Verify `KerlDHT.java` < 1,500 lines: `wc -l KerlDHT.java`
2. Remove unused imports from previous extractions
3. Remove dead fields (fields moved to extracted classes)
4. Remove stale comments referencing moved code
5. Update Javadoc to reference extracted classes
6. Run full test suite
7. Run full module build: `./mvnw install -pl thoth`

### Success Criteria

- [ ] `KerlDHT.java` < 1,500 lines (`wc -l`)
- [ ] No dead imports or unused fields
- [ ] Javadoc updated with delegation references
- [ ] KerlDHT public API unchanged
- [ ] All thoth tests pass: `./mvnw test -pl thoth`
- [ ] Full module build passes: `./mvnw install -pl thoth`

### Test Command

```bash
./mvnw test -pl thoth
./mvnw install -pl thoth
wc -l thoth/src/main/java/com/hellblazer/delos/thoth/KerlDHT.java
```

---

## Line Count Projections

| After Step | KerlDHT lines | Reduction | New file | New file lines |
|------------|--------------|-----------|----------|---------------|
| Phase A    | ~2,395       | +16 (security) | -- | -- |
| B Step 1   | ~2,295       | -100      | DhtMetricsCollector.java | ~100 |
| B Step 2   | ~1,995       | -300      | DhtReconciliationService.java | ~300 |
| B Step 3   | ~1,795       | -200      | (DhtValidationPipeline grows) | +200 |
| B Step 4   | ~1,450       | -345 (cleanup) | -- | -- |

Target: < 1,500 lines. If Step 4 cleanup alone does not achieve this, identify
additional helper methods to move during Step 4.

## Test Strategy

### Per-Step Protocol

1. Write characterization/TDD tests BEFORE implementation
2. Implement changes
3. Run full thoth test suite: `./mvnw test -pl thoth`
4. Verify KerlDHT public API unchanged (existing tests serve as contract)
5. Commit: one commit per step
6. Rollback strategy: `git revert <commit>` for any single step

### Key Test Files (43 test files, ~9,437 lines total)

| Category | Test Files | What They Verify |
|----------|-----------|-----------------|
| Core DHT | `KerlDhtTest`, `AbstractDhtTest` | Quorum operations, basic DHT |
| Validation | `DhtValidationPipelineTest`, `OrderedValidationPipelineTest` | Pipeline validation |
| Freshness | `KerlDHTResponseFreshnessTest` | Response freshness checks |
| Signatures | `KerlDHTReadSignatureVerificationTest`, `KerlDHTMutateSignatureVerificationTest`, `KerlDHTConcurrentSignatureVerificationTest` | Signature verification |
| Nonce | `KerlDHTNonceVerificationTest`, `NonceVerifierTest` | Replay protection |
| Health | `KerlDHTHealthCheckTest` | Health monitoring |
| Lifecycle | `KerlDHTLifecycleTest` | Start/stop/close |
| Byzantine | `ByzantineFaultToleranceTest`, `ByzantineDetectionIntegrationTest`, `KerlDHTEquivocationDetectionTest` | BFT detection |
| Reconciliation | `DhtRebalanceTest` | Reconciliation protocol |
| Performance | `PerformanceBaselineTest`, `PerformanceSLATest` | Performance SLA |

### Regression Risk Matrix

| Task | Highest Risk Tests | Mitigation |
|------|-------------------|------------|
| Phase A | `DhtRebalanceTest`, `ByzantineFaultToleranceTest` | Two-pass ordering, circuit breaker |
| B Step 1 | `KerlDHTHealthCheckTest`, `KerlDHTLifecycleTest` | Simple delegation |
| B Step 2 | `DhtRebalanceTest`, `AbstractDhtTest`, `KerlDhtTest` | Constructor injection |
| B Step 3 | `KerlDHTResponseFreshnessTest`, `*SignatureVerificationTest` | Same behavior, moved location |
| B Step 4 | All tests | No behavioral changes, cleanup only |

## Risks and Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| Reconciliation validation false positives | Medium | Two-pass inception-first ordering; circuit breaker fail-open |
| Extraction breaks existing tests | Medium | One commit per step; full test suite after each; git revert |
| KerlDHT public API changes | Low | Extractions are internal; public interface unchanged |
| Field reference complexity | Medium | Start with lowest-coupling extraction; sequential execution |
| Performance regression from indirection | Low | Method-call overhead only; PerformanceSLATest validates |
| Concurrent modification with 3.2 (View) | Medium | Must NOT run simultaneously; 3.2 is deferred |

## Branch Strategy

All work on branch: `feature/Delos-izm.3.1-kerldht-decomposition`

Commit sequence:
1. `Implement reconciliation event validation (Phase A)` — References: Delos-7gj
2. `Extract DhtMetricsCollector from KerlDHT` — References: Delos-0x5
3. `Extract DhtReconciliationService from KerlDHT` — References: Delos-ard
4. `Consolidate validation methods into DhtValidationPipeline` — References: Delos-alw
5. `Slim KerlDHT to orchestrator (<1500 lines)` — References: Delos-62g

PR: Single PR with 5 commits for review coherence. Alternative: 2 PRs (Phase A
separately for faster security review, Phase B as follow-up).

## Knowledge References

- nx store: `architecture-delos-thoth-dht` (T3) — Thoth architecture overview
- nx store: `decision-developer-dht-response-signature-phase-a` (T3) — Prior Phase A work
- nx store: `technical-debt-delos-2026-03-12` (T3) — TODO inventory
- nx store: `pattern-delos-byzantine-fault-tolerance` (T3) — BFT patterns
- ADR-0013: Thoth Byzantine Fault Tolerance Integration
- Design doc: `docs/plans/2026-03-13-kerldht-decomposition-design.md`
