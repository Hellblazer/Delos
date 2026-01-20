# Phase 1B-3 Genesis Migration Boundary Plan Audit Report

**Auditor**: plan-auditor (Sonnet 4.5)
**Date**: 2026-01-19
**Epic**: Delos-3890
**Plan Document**: `/Users/hal.hildebrand/git/Delos/witness-service/docs/PHASE_1B_3_GENESIS_MIGRATION_PLAN.md`
**Verdict**: **CONDITIONAL GO** (3 critical issues must be resolved)

---

## Executive Summary

The Phase 1B-3 Genesis Migration Boundary implementation plan is **architecturally sound and implementable** with three critical corrections required before execution. The plan correctly identifies the migration boundary goal, has comprehensive test coverage (95 tests), and builds on verified Phase 1B-2-C prerequisites (480 tests passing). However, proto message conflicts and Proof of Possession semantic mismatches must be resolved to prevent implementation failures.

### GO/NO-GO Decision

**CONDITIONAL GO** - Implementation may proceed after resolving 3 critical issues:

1. ✋ **CRITICAL**: Resolve proto message schema conflicts (PhaseTransitionRequest/Response already exist)
2. ✋ **CRITICAL**: Clarify Proof of Possession validation semantics (key-based vs message-based)
3. ⚠️ **IMPORTANT**: Break circular dependency between MigrationStateTracker and TransitionReadinessChecker

---

## Critical Issues

### Issue #1: Proto Message Schema Conflicts (HIGH SEVERITY)

**Problem**: Plan proposes creating proto messages that **already exist** with different schemas.

**Evidence**:
- Existing in `grpc/src/main/proto/witness.proto` (line 308-329):
  - `PhaseTransitionRequest` with field: `string newPhase = 1`
  - `PhaseTransitionResponse` with fields: `bool success, string oldPhase, string newPhase, string errorMessage`

- Plan proposes (lines 878-904):
  - `PhaseTransitionRequest` with fields: `TargetPhase target_phase, bool force, string justification`
  - `PhaseTransitionResponse` with fields: `Status status, string previous_phase, string new_phase, int64 epoch_transitioned, string message`

**Impact**: Protoc compilation will fail. Existing gRPC clients will break.

**Fix**: Extend existing messages instead of replacing:
```protobuf
message PhaseTransitionRequest {
  string newPhase = 1;          // KEEP
  bool force = 2;               // ADD
  string justification = 3;     // ADD
}

message PhaseTransitionResponse {
  bool success = 1;             // KEEP
  string oldPhase = 2;          // KEEP
  string newPhase = 3;          // KEEP
  string errorMessage = 4;      // KEEP
  int64 epoch_transitioned = 5; // ADD
}
```

---

### Issue #2: Proof of Possession Semantic Mismatch (CRITICAL SEVERITY)

**Problem**: Plan confuses standard BLS PoP (key ownership) with key registration signatures (member authorization).

**Evidence**:
- Existing `ProofOfPossession.java` (line 59-85): Signs **the PUBLIC KEY** with secret key
- Plan's usage (line 276-283): Tries to sign **MEMBER ID**, not public key

**Impact**: Existing API doesn't support arbitrary message signing. Security semantics are incompatible.

**Fix**: Use two-step validation separating concerns:

```java
public record BLSKeyRegistration(
    Identifier memberId,
    BLSPublicKey publicKey,
    ProofOfPossession proofOfPossession,      // Proves key ownership
    BLSSignature registrationSignature,       // Proves member authorization
    long registrationEpoch,
    Instant registrationTime
)
```

Updated validation:
```java
// Step 1: Verify standard PoP (key ownership)
var popValid = registration.proofOfPossession().verify(
    registration.publicKey().toBytesCompressed(),
    blsOperations.getProvider()
);

// Step 2: Verify registration signature (member authorization)
var message = registration.memberId().getDigest().getBytes();
var authValid = BLSOperations.verify(
    registration.publicKey(),
    message,
    registration.registrationSignature()
);
```

---

### Issue #3: Circular Dependency (MEDIUM-HIGH SEVERITY)

**Problem**: `MigrationStateTracker` and `TransitionReadinessChecker` reference each other.

**Fix**: Extract `PhaseProvider` interface:

```java
interface PhaseProvider {
    MigrationPhase getCurrentPhase();
}

// TransitionReadinessChecker depends on PhaseProvider, not full tracker
class TransitionReadinessChecker {
    private final PhaseProvider phaseProvider;
    ...
}

// MigrationStateTracker implements PhaseProvider
class MigrationStateTracker implements PhaseProvider {
    private volatile TransitionReadinessChecker readinessChecker;

    public void setReadinessChecker(TransitionReadinessChecker checker) {
        this.readinessChecker = checker;  // Setter injection breaks cycle
    }
}
```

---

## Important Issues

### Issue #4: Fireflies Shunning API Unclear
- No evidence of `shun()` method in Fireflies API
- Recommend: Investigate `AccusationWrapper.java` or implement witness-level shunning

### Issue #5: Key Persistence Strategy Undefined
- Plan shows in-memory only (keys lost on restart)
- Recommend: Specify persistence backend (SQL, CHOAM, etc.)

---

## Strengths

✅ All Phase 1B-2-C prerequisites verified to exist
✅ WitnessContext integration point is clean
✅ Thread-safety patterns sound
✅ Comprehensive test coverage (95 tests)
✅ Success criteria measurable
✅ Timeline feasible (14-17 days)

---

## Timeline Impact

- **Original**: 13 days (104 hours)
- **Issue Resolution**: +4-8 hours (proto, PoP, circular dep)
- **Adjusted**: 14-17 days (baseline)
- **With 20% buffer**: 17-20 days total

---

## Sign-Off Requirements

Before implementation proceeds:
- [ ] Issue #1 resolved: Proto messages extended (not replaced)
- [ ] Issue #2 resolved: Two-step PoP validation (PoP + registration signature)
- [ ] Issue #3 resolved: Circular dependency broken with PhaseProvider interface
- [ ] Issue #4 clarified: Fireflies shunning approach documented
- [ ] Plan updated: All recommendations incorporated

---

## Final Recommendation

**CONDITIONAL GO** - Implement after resolving 3 critical issues. All prerequisites exist. Integration points are clean. Test strategy is comprehensive. Proceed with Java-architect-planner creating detailed architecture post-corrections.
