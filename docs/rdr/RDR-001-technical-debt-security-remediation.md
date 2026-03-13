---
title: "Technical Debt & Security Gap Remediation"
id: RDR-001
type: design
status: accepted
accepted_date: 2026-03-12
reviewed-by: self
priority: P1
created: 2026-03-12
author: hal.hildebrand
---

# RDR-001: Technical Debt & Security Gap Remediation

## Problem Statement

A comprehensive codebase analysis on 2026-03-12 identified 88 TODO/FIXME comments across 30 source files (strict grep), including critical security gaps, unimplemented production stubs, god classes, and feature flags defaulting to insecure states. These findings represent accumulated technical debt that poses security, maintainability, and reliability risks to the Delos distributed systems platform.

## Context

### Source
Findings from the deep codebase analysis stored in T3:
- `technical-debt-delos-2026-03-12` — full inventory
- `pattern-delos-security-boundaries` — trust model and known gaps
- `pattern-delos-error-handling-resilience` — feature flags analysis
- `inventory-delos-grpc-services` — API duplication findings

### Current State
- **88 TODO/FIXME** comments across 30 files (strict `grep -c "TODO|FIXME" *.java`)
- **5 critical security gaps** with TODO markers (see Phase 1)
- **3 god classes** exceeding 1,100 lines
- **2 feature flags** defaulting to insecure states
- **1 deprecated module** still compiled
- **Proto schema gap** preventing DHT signature verification

## Proposed Remediation

### Dependency Graph

Items are not fully independent. The following ordering constraints apply:

```
1.1 (DHT Signatures) ──► 1.3 (Nonce Verification)
    Nonce verification requires authenticated responses to be meaningful;
    without response signatures, a Byzantine node can forge nonce-acceptance.

1.1 (DHT Signatures) ──► 1.5 (RecursiveProofValidator)
    Proof verification depends on the signature schema defined in 1.1.

1.2 (Feature Flags) ──► requires Delos-g4sh migration tool complete
    NONCE_PERSISTENCE default change requires nonce migration tooling.
```

All other items may proceed in parallel within their phase.

### Phase 1: Critical Security (P0)

#### 1.1 DHT Response Signature Verification
**Files**: `KerlDHT.java:1778, 2147`, thoth.proto
**Problem**: DHT responses lack cryptographic signatures. Byzantine replicas can return forged KERL data without detection.
**Fix**:
1. Add `Sig signature` field to relevant thoth.proto response messages
2. Sign responses with member's key in `KerlDhtServer`
3. Verify signatures in `KerlDHT` client-side response handling
4. Add test: Byzantine member returns forged KERL → detected and rejected

**Rollout**: Adding a proto3 field is an additive, backward-compatible change. Implement as a two-phase rollout:
- **Phase A**: Add field, populate on send, accept-but-don't-require on receive (handles mixed-version clusters)
- **Phase B**: Enforce signature presence after all nodes are upgraded

No coordinated cutover or maintenance window required.

#### 1.2 Feature Flags Default to Secure
**Files**: `choam/FeatureFlags.java`, `Committee.java`
**Problem**: `VERIFIER_VALIDATION` and `NONCE_PERSISTENCE` default to OFF, allowing committee validation bypass and in-memory-only nonce tracking (replay vulnerability across restarts).
**Fix**:
1. Change default for `VERIFIER_VALIDATION` to `true`
2. Apply strict validation to the identity verifier path at `Committee.java:219-225` — when `VERIFIER_VALIDATION` is enabled, reject members with missing identity verifiers rather than unconditionally returning `NO_VERIFIER` (this second bypass path is not currently flag-gated)
3. Add integration tests validating both NO_VERIFIER bypass paths are closed when flag is ON
4. Document the flags and their security implications
5. Provide system property override for development/testing only

**Pre-conditions for `NONCE_PERSISTENCE`**:
- Verify Delos-g4sh nonce migration tool is complete for all target environments
- Default change to `true` must be gated behind a migration validation check at startup, or delivered in a separate release after migration tooling ships
- See `FeatureFlags.java:54` prerequisite comment

**Pre-conditions for `VERIFIER_VALIDATION`**:
- Run full large-test suite (`-Dlarge_tests=true`) with `VERIFIER_VALIDATION=true` and resolve all failures
- Document any legitimate cases where consensus key absence is expected during committee formation (e.g., the 30-second grace period referenced in `FeatureFlags.java`)
- Clarify whether the grace period is implemented or aspirational

**Risk**: Enabling `VERIFIER_VALIDATION` exercises a code path that has never run in production (flag has always been OFF). The flag was deliberately designed for gradual rollout, suggesting known edge cases. Concrete risk: validators may legitimately lack consensus keys during committee rotation windows.

#### 1.3 Nonce Verification Implementation
**File**: `KerlDHT.java:327`
**Problem**: Phase 3 nonce verification not implemented. Replay attacks possible on DHT operations.
**Depends on**: Item 1.1 — nonce verification is meaningless without authenticated responses (a Byzantine node can forge nonce-acceptance without response signatures).
**Fix**:
1. Implement cryptographic nonce generation and verification
2. Persist nonces using MVStore (already used elsewhere in CHOAM)
3. Add request freshness validation with configurable window
4. Add test: replayed DHT request → rejected

#### 1.4 WitnessServiceImpl Functional Gaps
**File**: `WitnessServiceImpl.java`
**Problem**: `notifyViewChange` does not update `WitnessContext` with new members (line 904), and `getCommitteeInfo` returns hardcoded committee size without actual member list (line 824). These are functional correctness gaps in a production BFT witness service — the witness service cannot track membership changes, running with stale membership.
**Fix**:
1. Implement `notifyViewChange` to update `WitnessContext` with new members from the `ViewChange` request
2. Implement `getCommitteeInfo` to return actual committee member list from `WitnessContext`
3. Add integration test: view change → witness context updated with new membership
4. Integrate with Fireflies view change listener (line 995 TODO)

#### 1.5 RecursiveProofValidator Completion
**File**: `RecursiveProofValidator.java` (6 TODOs)
**Problem**: Intermediate node message construction uses `new byte[32]` placeholder throughout (lines ~753, ~820), `constructEpochMessage` returns a 32-byte zero array (line 1009), and intermediate node BLS verification returns `ValidationResult.valid()` unconditionally (line 993). Any Byzantine node can insert a forged intermediate proof node and it will pass verification.
**Depends on**: Item 1.1 — the signature schema for what gets verified must be defined first.
**Fix**:
1. Implement `constructEpochMessage` to produce a deterministic, canonically-encoded message from epoch number and previous root hash (not a zero array)
2. Remove all `new byte[32]` placeholder message constructions
3. Implement proper intermediate node verification (line 993) with actual BLS signature checking
4. Define proper epoch resolution for intermediate nodes (line 793 TODO)
5. Add test: forged intermediate proof node → detected and rejected

### Phase 2: Production Stubs (P1)

#### 2.1 ShardedKERL Auto-Generated Stub
**File**: `ShardedKERL.java:118`
**Problem**: `appendValidations` returns `null` with a `// TODO Auto-generated method stub` comment. This method has active callers: `Publisher.java:51,62`, `DirectPublisher.java:39,54`, `KerlSpace.java:360`, and `DemesneKERLServer.java:107`. Callers that chain on the returned state silently succeed while doing nothing — validations are silently dropped.
**Fix**: Implement the method by delegating to the appropriate shard's KERL, or throw `UnsupportedOperationException` with clear message if `ShardedKERL` legitimately does not support validation appending (and verify callers handle the exception).

#### 2.2 Enforcement Mode Implementation
**File**: `ValidatingCombineTransitions.java:238`
**Problem**: Enforcement mode not implemented — validation rules exist but aren't enforced. Only `LOG_ONLY` mode exists; `STATE_VALIDATION` flag enables logging but not rejection of invalid transitions.
**Fix**: Implement enforcement logic that rejects invalid state transitions when `FeatureFlags.STATE_VALIDATION` is in enforcement mode. Document the progression: OFF → LOG_ONLY → ENFORCE.

### Phase 3: Structural Improvements (P2)

#### 3.1 KerlDHT Decomposition
**File**: `KerlDHT.java` (2,356 lines)
**Problem**: God class combining DHT, BFT validation, metrics, and reconciliation.
**Pre-condition**: Resolve the Phase 5 reconciliation TODO at line 2017 ("Add reconciliation validation once correct event coordinate extraction is implemented") before extracting `DhtReconciliationService`. Otherwise the extracted service carries a known-broken validation path.
**Proposed decomposition**:
- `KerlDHT` — core DHT operations and lifecycle
- `DhtReconciliationService` — anti-entropy gossip (extract existing reconciliation logic)
- `DhtValidationService` — post-quorum Byzantine validation (partially exists as `DhtValidationPipeline`)
- `DhtMetricsCollector` — metrics instrumentation

**Note**: CLAUDE.md marks KerlDHT as "Production-ready" with 276 tests and 99.6% passing. Decomposition regression risk is not negligible given this test surface area. Proceed incrementally with API compatibility.

#### 3.2 View Decomposition
**File**: `View.java` (2,144 lines)
**Problem**: God class combining membership, gossip, accusation, and KERI verification.
**Proposed decomposition**:
- `View` — lifecycle and public API
- `GossipEngine` — gossip protocol execution
- `AccusationManager` — accusation/rebuttal handling
- `ViewMembershipTracker` — membership state tracking
Note: `ViewManagement.java` (1,177 lines) was already extracted but is itself substantial.

#### 3.3 KERL API Deduplication
**Files**: `stereotomy-services.proto` (KERLService, 15 RPCs), `thoth.proto` (KerlDht, 14 RPCs)
**Problem**: Near-identical APIs with Thoth adding DHT distribution on top.
**Fix**: Extract shared message types into a common `kerl-common.proto`, then import and use those types in both `stereotomy-services.proto` and `thoth.proto`. Note: proto3 does not support service inheritance or extension — services must remain separate but can share message definitions.

### Phase 4: Cleanup (P3)

#### 4.1 Remove Deprecated Iona Module
**File**: `Iona.java` (31 lines) — `@Deprecated(since="0.5.1", forRemoval=true)`
**Problem**: Noise protocol server never implemented, still compiled.
**Fix**: Remove `Iona.java` and references. Update `RouterSupplier` enum if needed.

#### 4.2 Clean Dead Test Infrastructure
**File**: `SubDomainSpawnBenchmarkTest.java` — 24 TODO markers, every method is a stub.
**Fix**: Either implement the benchmarks or delete the file.

#### 4.3 Witness Service Cosmetic Stubs
**File**: `WitnessServiceImpl.java` — remaining cosmetic stubs after Phase 1.4
**Problem**: `estimateSignatureCount` (placeholder, lines 615/642), average receipt latency (returns 0.0, line 863), and collection filter (accepts all, line 666) are operational quality issues but not correctness gaps.
**Fix**: Implement or clearly mark with `@NotImplemented` annotation and issue tracking.

### Deferred Items (Acknowledged)

The following items were identified during analysis but are not addressed in this RDR:

- **`StallRecoveryStrategy.java`**: Two TODOs — partition status monitoring before triggering resync (line 123, requires Fireflies integration) and Byzantine incident view change (line 269, requires Ethereal/CHOAM integration). Partition healing without awareness could trigger unnecessary resyncs. Track separately.
- **`CHOAM.java`** (1,012 lines): Approaching but below the 1,100-line threshold. Monitor for growth.

## Decision Criteria

| Criterion | Weight | Notes |
|-----------|--------|-------|
| Security impact | High | Phase 1 items are security-critical |
| Breaking changes | Medium | Proto changes are additive (proto3 backward-compatible) |
| Test coverage | High | Each fix must include regression tests |
| Backward compatibility | Medium | Feature flag changes need migration path |
| Dependency ordering | Medium | Items 1.3 and 1.5 depend on 1.1; Item 1.2 NONCE_PERSISTENCE depends on migration tooling |

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Proto field addition requires mixed-version handling | Low | Medium | Proto3 additive fields are backward-safe; implement two-phase rollout (accept-but-don't-require, then enforce) |
| Enabling VERIFIER_VALIDATION exposes latent bugs | Medium | Medium | Run full large-test suite with flag ON first; document legitimate consensus-key-absent scenarios; implement grace period if not already present |
| NONCE_PERSISTENCE default change causes data loss on restart | High | High | Gate behind Delos-g4sh migration tool completion; add startup validation check; deliver in separate release after migration ships |
| KerlDHT decomposition introduces regressions | Medium | Medium | Resolve Phase 5 reconciliation TODO first; refactor incrementally; maintain API compatibility; run all 276 KerlDHT tests after each extraction |
| View decomposition breaks Fireflies gossip | Low | High | Extract one concern at a time; extensive integration testing |
| RecursiveProofValidator fix reveals deeper proof-chain issues | Medium | Medium | Start with `constructEpochMessage` implementation; add fuzz testing for intermediate nodes before removing placeholder returns |

## Success Metrics

- [ ] Zero critical security TODOs in security-critical paths: KerlDHT, Committee, NonceTracker, RecursiveProofValidator, WitnessServiceImpl (Phase 1)
- [ ] All production stubs either implemented or explicitly documented as deferred (Phase 2)
- [ ] No class exceeds 1,500 lines (Phase 3)
- [ ] No deprecated code compiled (Phase 4)
- [ ] Full test suite passes with `VERIFIER_VALIDATION=true` (both bypass paths closed)
- [ ] Full test suite passes with `NONCE_PERSISTENCE=true` (after migration tool completion)
- [ ] TODO/FIXME count in production source files (excluding tests) reduced to zero in security-critical modules

## Research Findings

### RF-1: TODO/FIXME Inventory (2026-03-12)

**Method**: `grep -c "TODO|FIXME" *.java` across entire codebase

**Result**: 88 occurrences across 30 files (production + test). Top offenders:

| File | Count | Category |
|------|-------|----------|
| `SubDomainSpawnBenchmarkTest.java` | 24 | Dead test infrastructure (Phase 4.2) |
| `SimpleServer.java` (tron test) | 10 | Test fixture stubs |
| `WitnessServiceImpl.java` | 7 | Phase 1A-3 stubs (Phase 1.4, 4.3) |
| `RecursiveProofValidator.java` | 6 | Incomplete proof validation (Phase 1.5) |
| `KerlDHT.java` | 4 | Security gaps: signature verification, nonce (Phase 1.1, 1.3) |
| `ByzantineFaultToleranceTest.java` | 4 | Test placeholders |
| `SubDomainHandleImpl.java` | 3 | Model integration gaps |
| `Eth2BLSVectorTest.java` | 3 | Crypto test vectors |
| `StallRecoveryStrategy.java` | 2 | Recovery logic gaps (deferred) |
| `WitnessRecoveryManager.java` | 2 | Recovery stubs |

### RF-2: Feature Flag Security Analysis (2026-03-12)

**Method**: Traced `FeatureFlags.*` usage through production code paths

**Findings**:

**VERIFIER_VALIDATION** (default: OFF) — **Critical security bypass**
- `Committee.java:46` — reads flag at committee construction
- `Committee.java:81-83` — when OFF, returns `Verifier.NO_VERIFIER` instead of throwing for missing consensus keys (flag-gated)
- `Committee.java:224-225` — returns `NO_VERIFIER` for missing member identity verifier **unconditionally** (NOT flag-gated — bypasses verification even when flag is ON)
- `BatchVerificationHelper.java:147` — skips verification entirely when verifier is `NO_VERIFIER`
- **Impact**: Two bypass paths. Changing the flag default to ON closes the first path but NOT the second. Both must be addressed.

**NONCE_PERSISTENCE** (default: OFF) — **Replay vulnerability**
- `NonceTracker.java:32` — controls store selection
- `Session.java:68` — logs persistence state at session creation
- `InMemoryNonceStore.java:20` — in-memory store loses nonces on restart
- `FeatureFlags.java:54` — documents prerequisite: Delos-g4sh migration tool must complete first
- **Impact**: After restart, previously-used nonces are not tracked; replayed DHT requests will be accepted

**QUEUE_EVICTION** (default: OFF) — **Resource management**
- `Producer.java:79,419,459` — controls queue eviction for stale transactions
- **Impact**: Memory growth from accumulated stale transactions (operational, not security-critical)

**STATE_VALIDATION** (default: OFF) — **Integrity checking**
- `CHOAM.java:192` — validates state transitions
- `ValidatingCombineTransitions.java:239` — enforcement mode TODO marker (Phase 2.2)
- **Impact**: State corruption may go undetected

**Test coverage**: Comprehensive test exists in `FeatureFlagManagerTest.java` (thread-safety, JMX, system property override), `CommitteeTest.java` (NO_VERIFIER bypass vs strict mode), `CommitteeValidationTest.java` (vulnerability demonstration).

### RF-3: God Class Metrics (2026-03-12)

**Method**: `wc -l` on identified large files

| File | Lines | Responsibility Coupling |
|------|-------|------------------------|
| `KerlDHT.java` | 2,356 | DHT + BFT validation + metrics + reconciliation |
| `View.java` | 2,144 | Membership + gossip + accusation + KERI verification |
| `ViewManagement.java` | 1,177 | Already extracted from View, still large |

`CHOAM.java` was evaluated at 1,012 lines — below the 1,100-line threshold but should be monitored for growth.

### RF-4: Deprecated Module Status (2026-03-12)

**Method**: Located `Iona.java` at `memberships/src/main/java/.../archipelago/Iona.java`

**Finding**: 31-line file with `@Deprecated(since="0.5.1", forRemoval=true)`. Noise protocol server stub — never implemented, still compiled as part of `memberships` module. Removal is safe and trivial.

### RF-5: ShardedKERL.appendValidations Caller Analysis (2026-03-12)

**Method**: `grep appendValidations *.java` across codebase

**Finding**: `ShardedKERL.appendValidations` (returns `null`) has active callers through the KERL interface chain: `Publisher.java:51,62`, `DirectPublisher.java:39,54`, `KerlSpace.java:360`, `DemesneKERLServer.java:107`. Validations passed through these paths are silently dropped.

## Related Documents

- T3: `technical-debt-delos-2026-03-12`
- T3: `pattern-delos-security-boundaries`
- T3: `pattern-delos-error-handling-resilience`
- T3: `inventory-delos-grpc-services`
- T3: `tidying-delos-assessment-2026-03-12`
- ADR-0007: Cross-Layer Byzantine Detection
- ADR-0013: Thoth Byzantine Fault Tolerance Integration
