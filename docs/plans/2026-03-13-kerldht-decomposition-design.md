# KerlDHT Decomposition — Design

**Bead**: Delos-izm.3.1 (KerlDHT Decomposition)
**RDR**: RDR-001 (accepted)
**Date**: 2026-03-13
**Approach**: C — Extend DhtValidationPipeline + incremental extraction

## Overview

Two-phase work:
1. **Pre-condition**: Implement reconciliation event validation (Phase 5 TODO at KerlDHT.java:2045-2047)
2. **Decomposition**: Incremental extraction of KerlDHT (2,379 lines → <1,500 lines)

## Phase A: Reconciliation Event Validation

### Problem

`Reconcile.update()` receives events from a **single peer** (no quorum protection) and passes them directly to `kerlSpace.update()` with zero validation. A Byzantine peer can inject arbitrary KERI events into the DHT via reconciliation.

| Path | Consensus | Pre-validation | Post-validation | Risk |
|------|-----------|---------------|-----------------|------|
| Write (append) | BFT quorum (3f+1) | Structural | Async (DhtValidationPipeline) | Low |
| Read (get*) | BFT quorum (3f+1) | — | Freshness + signature | Low |
| **Reconciliation** | **Single peer** | **None** | **None** | **CRITICAL** |

### Design

Extend `DhtValidationPipeline` with `validateReconciliationBatch()`:

1. **Coordinate extraction utility**: Create a static method to extract `EventCoordinates` from `KeyEventWithAttachments` (oneof of InceptionEvent/RotationEvent/InteractionEvent). Reuse `ProtobufEventFactory` patterns — it already has `digestOf()` for this proto structure.

2. **Two-pass batch validation** (inside the pipeline):
   - Pass 1: Sort events by sequence number; process inception/establishment events first (they bootstrap trust for new identifiers)
   - Pass 2: Validate dependent events against now-available establishment events via Ani
   - For each event: verify signature, check self-addressing identifier digest

3. **Integration**: Wire into `Reconcile.update()` as a **pre-insertion gate** — only validated events proceed to `kerlSpace.update()`

4. **Byzantine signals**: Record `recordValidationFailure(peerId, "reconciliation_invalid_event")` for each rejected event

5. **Circuit breaker**: Reuse existing pipeline circuit breaker — fail-open during infrastructure issues (KERL unavailable) rather than blocking reconciliation entirely. This is the same trade-off made for write validation.

### Proto structure (reference)

```protobuf
message KeyEventWithAttachments {
  oneof event {
    InceptionEvent inception = 1;    // → identifier from inception.identifier
    RotationEvent rotation = 2;      // → identifier from rotation.specification.header.identifier
    InteractionEvent interaction = 3; // → identifier from interaction.specification.header.identifier
  }
  Attachment attachment = 4;
}
```

Coordinate extraction maps:
- InceptionEvent → `inception.specification.header.{sequenceNumber, ilk}`, `inception.identifier`
- RotationEvent → `rotation.specification.header.{identifier, sequenceNumber, ilk}`
- InteractionEvent → `interaction.specification.header.{identifier, sequenceNumber, ilk}`
- Digest: computed from event bytes via `DigestAlgorithm`

### Files to modify

- `thoth/src/main/java/com/hellblazer/delos/thoth/DhtValidationPipeline.java` — add `validateReconciliationBatch()`, coordinate extraction utility
- `thoth/src/main/java/com/hellblazer/delos/thoth/KerlDHT.java` — wire validation into `Reconcile.update()` (lines 2039-2049)
- New test: `DhtReconciliationValidationTest.java`

## Phase B: KerlDHT Incremental Decomposition

### Current state

- 2,379 lines, 12 responsibility clusters, 40+ public methods
- 23 injected fields, 3 inner classes (Service, Reconcile, RequestContext)
- ~100-130 tests across 14 test files
- DhtValidationPipeline (348 lines) already exists as extraction precedent

### Step 1: Extract DhtMetricsCollector (Low risk)

Extract Cluster H (5 methods): `isHealthy()`, `getHealthSnapshot()`, `startPoolMonitoring()`, `stopPoolMonitoring()`, `isPoolExhausted()`.

- New class: `DhtMetricsCollector.java`
- KerlDHT delegates health checks and pool monitoring
- Fields moved: pool monitoring timer, health SLA thresholds
- Estimated reduction: ~100 lines

### Step 2: Extract DhtReconciliationService (Medium risk)

Extract Cluster C: `Reconcile` inner class + `reconcile()` methods + `keyIntervals()` helper.

- New class: `DhtReconciliationService.java`
- Contains the reconciliation protocol (query/response/update)
- Contains the newly-added reconciliation validation (from Phase A)
- Fields moved: reconcileComms, kerlSpace reference, falsePositiveRate
- Estimated reduction: ~300 lines

### Step 3: Consolidate validation into DhtValidationPipeline (Medium risk)

Move response freshness validation (Cluster D) and signature verification (Cluster E) into DhtValidationPipeline (or a new DhtResponseValidator wrapping it).

- `validateResponseFreshness()` → pipeline
- `verifyResponseSignature()` → pipeline
- `verifyWriteAcknowledgment()` → pipeline
- NonceVerifier integration moves with freshness validation
- Estimated reduction: ~200 lines

### Step 4: Slim KerlDHT to orchestrator

- KerlDHT retains: core quorum operations (Clusters A, B, F), lifecycle (G), adaptation (L)
- Target: <1,500 lines
- Public API unchanged — all extracted classes are internal implementation details

### Each step protocol

- Separate commit
- Full thoth test suite (`./mvnw test -pl thoth`)
- Public API unchanged
- Rollback = `git revert` single commit

## Risks and mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| Reconciliation validation rejects valid events (false positive) | Medium | Two-pass ordering (inception first); circuit breaker fail-open |
| Extraction breaks existing tests | Medium | Each step is one commit; full test suite after each |
| KerlDHT public API changes | Low | Extractions are internal — public interface unchanged |
| Reconciliation validation performance | Low | Async validation; batch processing; event cache reuse |

## Success criteria

- [ ] Reconciliation events validated before insertion (Phase A)
- [ ] Byzantine signals recorded for invalid reconciliation events
- [ ] Circuit breaker protects reconciliation from infrastructure failures
- [ ] KerlDHT < 1,500 lines after decomposition (Phase B)
- [ ] All ~130 KerlDHT tests pass after each step
- [ ] Full thoth module tests pass
- [ ] KerlDHT public API unchanged
