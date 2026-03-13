# Continuation Prompt — RDR-001 Technical Debt Remediation

## Context

You are continuing work on **RDR-001: Technical Debt & Security Gap Remediation** for the Delos distributed systems project.

**Branch**: `feature/Delos-izm.1-phase1-p0-security`
**RDR**: `docs/rdr/RDR-001-technical-debt-security-remediation.md` (status: accepted)
**Implementation plan**: `docs/plans/2026-03-12-phase1-p0-completion-impl-plan.md`

## Completed Work (10 of 13 items)

### Phase 1 — Critical Security (P0) ✅ COMPLETE
- **1.1** DHT Response Signature Verification (`08e50378`)
- **1.2** Feature Flags Default to Secure (`802eda58`)
- **1.3** Nonce Verification Implementation (`e1a4c771`, `8eb0b2b1`) — Added `NonceVerifier` interface + `InMemoryNonceVerifier` with TTL/capacity bounds. Integrated into KerlDHT `validateResponseFreshness`. Composite key `nonce|memberId` prevents false positives on quorum responses.
- **1.4** WitnessServiceImpl Functional Gaps (`bd2f3555`)
- **1.5** RecursiveProofValidator Completion (`97ff26b0`) — Implemented `constructEpochMessage` (BLAKE2B_256 canonical encoding), replaced all `new byte[32]` placeholders, fixed both unconditional `ValidationResult.valid()` bypasses with real BLS verification, added `aggregateChildBitmaps()` helper.

### Phase 2 — Production Stubs (P1) ✅ COMPLETE
- **2.1** ShardedKERL appendValidations (`9ce55b33`) — Delegates to `UniKERL.appendValidations` via `dsl.transaction()`.
- **2.2** Enforcement Mode (`8bc33726`) — Added `ValidationMode` enum (LOG_ONLY/ENFORCE/METRICS_ONLY) to `FeatureFlags`, implemented in `ValidatingCombineTransitions.handleViolation`. System property: `-Dfeature.state.validation.mode=ENFORCE`.

### Phase 3 — Structural Improvements (P2) ❌ REMAINING
- **3.1** KerlDHT Decomposition (2,356 lines → KerlDHT core + DhtReconciliationService + DhtValidationService + DhtMetricsCollector)
- **3.2** View Decomposition (2,144 lines → View lifecycle + GossipEngine + AccusationManager + ViewMembershipTracker)
- **3.3** KERL API Deduplication (extract shared message types into kerl-common.proto)

### Phase 4 — Cleanup (P3) ✅ COMPLETE
- **4.1** Remove Deprecated Iona Module (`1067f89f`)
- **4.2** Clean Dead Test Infrastructure (`1067f89f`)
- **4.3** Witness Service Cosmetic Stubs (`5c07b01b`) — Real `matchesFilter` logic, receipt latency tracking, documented `estimateSignatureCount`.

## Remaining Work

Phase 3 items are the **god-class decomposition** work — high-risk refactoring with large test surfaces:

### 3.1 KerlDHT Decomposition
- **File**: `thoth/src/main/java/com/hellblazer/delos/thoth/KerlDHT.java` (2,356 lines)
- **Test surface**: 297+ tests in thoth module
- **Pre-condition from RDR**: Resolve Phase 5 reconciliation TODO at line 2017 before extracting `DhtReconciliationService`
- **Proposed decomposition**: KerlDHT core, DhtReconciliationService, DhtValidationService (partially exists as DhtValidationPipeline), DhtMetricsCollector
- **Risk**: KerlDHT is marked "Production-ready" — proceed incrementally with API compatibility

### 3.2 View Decomposition
- **File**: `fireflies/src/main/java/com/hellblazer/delos/fireflies/View.java` (2,144 lines)
- **Note**: `ViewManagement.java` (1,177 lines) was already extracted but is itself substantial
- **Proposed decomposition**: View lifecycle/API, GossipEngine, AccusationManager, ViewMembershipTracker
- **Risk**: Core Fireflies membership — extensive integration testing required

### 3.3 KERL API Deduplication
- **Files**: `stereotomy-services.proto` (KERLService, 15 RPCs), `thoth.proto` (KerlDht, 14 RPCs)
- **Fix**: Extract shared message types into `kerl-common.proto`, import from both
- **Note**: proto3 does not support service inheritance — services stay separate, share message definitions
- **Risk**: Proto changes affect generated code across multiple modules

## Key Findings / Decisions

1. **ADR-0015 does not exist** — it was a forward reference (commit `292418bb`) never written. The nonce implementation used Approach B (in-memory with TTL) instead of MVStore, with the `NonceVerifier` interface allowing future `PersistentNonceVerifier` behind `NONCE_PERSISTENCE` flag.

2. **Two unconditional `valid()` returns in RecursiveProofValidator** — line 830 (TreeNode IntermediateNode branch) AND line 992 (ProofPathNode method). Both fixed.

3. **`recordReplayAttack()` does not exist** on `ThothByzantineStateProvider`. Correct API is `recordValidationFailure(Digest memberId, String reason)`.

4. **Pre-existing flaky tests** (not related to our changes): `ByzantineDetectionPerformanceTest`, `BLSPerformanceBenchmarkTest`, `RateAnomalyDetectorTest`, `VersionCompatibilityMetadataTest`. All excluded from CI.

## Beads Status

Run `bd list --status=open` to see remaining work. Run `bd show Delos-izm.3.1` etc. for details.

## Untracked Files

- `docs/plans/` — design docs and this continuation prompt (not yet committed)
- `.serena/` — plugin config (do not commit)

## Recommended Next Steps

1. **Design Phase 3** — These are substantial refactorings. Use `/brainstorming-gate` before implementing. Consider whether to tackle 3.3 (proto dedup, lower risk) first vs 3.1/3.2 (god-class decomposition, higher risk).
2. **PR the completed work** — Phases 1, 2, and 4 (10 items) are self-contained and valuable. Consider PRing before starting Phase 3 decomposition.
3. **Phase 3 approach** — Incremental extraction with API compatibility. Extract one concern at a time, run full test suite after each extraction. Do NOT attempt big-bang refactoring.
