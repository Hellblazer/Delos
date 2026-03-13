# Phase 1 P0 Completion — Implementation Plan

**Epic**: Delos-izm.1 (Phase 1: Critical Security P0)
**RDR**: RDR-001 (accepted)
**Branch**: feature/Delos-izm.1-phase1-p0-security
**Date**: 2026-03-12
**Prerequisite**: Delos-izm.1.1 (DHT Response Signatures) — CLOSED

## Executive Summary

This plan covers the implementation of two remaining P0 security items:

- **Delos-izm.1.3**: Nonce Verification Implementation (thoth module)
- **Delos-izm.1.5**: RecursiveProofValidator Completion (witness-service module)

Both items are now unblocked (dependency on Delos-izm.1.1 satisfied). They are **fully independent** — different modules, no shared code — and can be worked as **parallel tracks**.

## Dependency Graph

```
Delos-izm.1 (Phase 1: Critical Security P0)
│
├── [CLOSED] Delos-izm.1.1 — DHT Response Signature Verification
├── [CLOSED] Delos-izm.1.2 — Feature Flags Default to Secure
│
├── Track A (thoth module) ──────────────────────────────────────
│   Delos-izm.1.3 — Nonce Verification Implementation
│   ├── Delos-izm.1.3.1 — NonceVerifier Interface & Unit Tests
│   ├── Delos-izm.1.3.2 — InMemoryNonceVerifier Implementation  [blocked by 1.3.1]
│   ├── Delos-izm.1.3.3 — KerlDHT Nonce Integration             [blocked by 1.3.2]
│   └── Delos-izm.1.3.4 — Module Verification                   [blocked by 1.3.3]
│
├── Track B (witness-service module) ────────────────────────────
│   Delos-izm.1.5 — RecursiveProofValidator Completion
│   ├── Delos-izm.1.5.1 — Security Tests & constructEpochMessage
│   ├── Delos-izm.1.5.2 — Leaf & Intermediate Message Construction [blocked by 1.5.1]
│   ├── Delos-izm.1.5.3 — verifyIntermediateNodeBLS Implementation [blocked by 1.5.2]
│   └── Delos-izm.1.5.4 — Test Updates & Module Verification       [blocked by 1.5.3]
│
│   Track A ║ Track B    ← PARALLEL (no cross-track dependencies)
```

## Critical Path

Both tracks run in parallel. The critical path is the **longer** of the two.

**Track A estimated effort**: 4 sub-tasks, ~3-4 hours
**Track B estimated effort**: 4 sub-tasks, ~4-5 hours (BLS crypto is more complex)

**Critical path**: Track B (RecursiveProofValidator)

## Track A: Nonce Verification (Delos-izm.1.3)

### A.1 — NonceVerifier Interface & Unit Tests (Delos-izm.1.3.1)

**Module**: thoth
**Type**: Interface design + TDD test writing

**New Files**:
- `thoth/src/main/java/com/hellblazer/delos/thoth/NonceVerifier.java`
- `thoth/src/test/java/com/hellblazer/delos/thoth/NonceVerifierTest.java`

**Steps**:

1. Create `NonceVerifier` interface:
   ```java
   public interface NonceVerifier {
       String generateNonce();
       boolean recordAndVerify(String nonce, Digest memberId);
       void close();
   }
   ```

2. Write failing unit tests in `NonceVerifierTest.java`:
   - `testGenerateNonce_Uniqueness` — generate 10,000 nonces, assert all unique
   - `testRecordAndVerify_FirstUse_Accepted` — new nonce returns true
   - `testRecordAndVerify_Replay_Rejected` — same nonce returns false
   - `testRecordAndVerify_DifferentMember_SameNonce_Rejected` — replay from different member also rejected
   - `testTTLExpiry_NonceReusableAfterExpiry` — after operationTimeout, nonce slot freed
   - `testCapacityBounds_EvictionOccurs` — at capacity limit, oldest entries evicted
   - `testThreadSafety_ConcurrentAccess` — 100 threads doing concurrent recordAndVerify

3. Verify: `./mvnw compile -pl thoth` succeeds (interface compiles)
4. Verify: `./mvnw test -pl thoth -Dtest=NonceVerifierTest` compiles but all tests FAIL

**Success Criteria**:
- [ ] NonceVerifier interface compiles with Javadoc
- [ ] All 7 tests compile and fail (no implementation yet)
- [ ] Interface designed for future PersistentNonceVerifier expansion

**Build/Test Commands**:
```bash
./mvnw compile -pl thoth
./mvnw test -pl thoth -Dtest=NonceVerifierTest
```

---

### A.2 — InMemoryNonceVerifier Implementation (Delos-izm.1.3.2)

**Module**: thoth
**Type**: Implementation
**Depends on**: Delos-izm.1.3.1

**New Files**:
- `thoth/src/main/java/com/hellblazer/delos/thoth/InMemoryNonceVerifier.java`

**Steps**:

1. Implement `InMemoryNonceVerifier`:
   - `ConcurrentHashMap<String, Long>` mapping nonce to expiry timestamp (ms)
   - `generateNonce()`: UUID.randomUUID().toString() (128-bit entropy, collision-safe)
   - `recordAndVerify(nonce, memberId)`:
     - Check if nonce exists in map and not expired
     - If exists and not expired: return false (replay detected)
     - If not exists or expired: put nonce with expiry = now + ttl, return true
     - Atomic via `computeIfAbsent` or `putIfAbsent` for thread safety
   - Scheduled eviction: ScheduledExecutorService runs every ttl/2 to remove expired entries
   - Capacity bounds: if size > maxCapacity, evict oldest 10% (or reject — design choice)
   - `close()`: shutdown scheduler, clear map

2. Constructor parameters:
   - `Duration ttl` — nonce time-to-live (matches KerlDHT operationTimeout)
   - `int maxCapacity` — maximum nonce entries (default 100,000)
   - `ScheduledExecutorService scheduler` — for eviction task

3. Run tests: `./mvnw test -pl thoth -Dtest=NonceVerifierTest`

**Success Criteria**:
- [ ] All 7 unit tests from A.1 pass
- [ ] Thread safety confirmed (concurrent test passes)
- [ ] TTL expiry works (nonce reusable after timeout)
- [ ] Capacity bounds enforced (no OOM at limit)

**Build/Test Commands**:
```bash
./mvnw test -pl thoth -Dtest=NonceVerifierTest
```

---

### A.3 — KerlDHT Nonce Integration (Delos-izm.1.3.3)

**Module**: thoth
**Type**: Integration (modify existing code)
**Depends on**: Delos-izm.1.3.2

**Modified Files**:
- `thoth/src/main/java/com/hellblazer/delos/thoth/KerlDHT.java`

**New Files**:
- `thoth/src/test/java/com/hellblazer/delos/thoth/KerlDHTNonceVerificationTest.java`

**Steps**:

1. Write failing integration tests in `KerlDHTNonceVerificationTest.java`:
   - `testRequestContext_HasNonceField` — RequestContext includes nonce
   - `testValidateResponseFreshness_UniqueNonce_Accepted` — fresh request passes
   - `testValidateResponseFreshness_ReplayedNonce_Rejected` — duplicate nonce rejected
   - `testValidateResponseFreshness_ReplayedNonce_RecordsByzantineSignal` — Byzantine signal recorded on replay
   - `testKerlDHT_DefaultConstructor_CreatesInMemoryNonceVerifier` — backward compatibility

2. Modify `RequestContext` record (KerlDHT.java line 143):
   ```java
   private record RequestContext(String operation, Digest identifier, long timestamp, String nonce) {
   }
   ```

3. Add `NonceVerifier` field to KerlDHT:
   - Add field: `private final NonceVerifier nonceVerifier;`
   - Add to main constructor parameter list
   - Add backward-compatible constructor that creates `new InMemoryNonceVerifier(operationTimeout, 100_000, scheduler)`

4. Enhance `validateResponseFreshness` (line 337):
   - After timestamp check passes, verify nonce:
     ```java
     if (!nonceVerifier.recordAndVerify(context.nonce(), respondingMember.getId())) {
         log.warn("Replay detected: duplicate nonce for requestId={} from member={}",
                   requestId, respondingMember.getId());
         // Record Byzantine signal
         if (byzantineProvider != null) {
             byzantineProvider.recordReplayAttack(respondingMember.getId(), requestId);
         }
         return false;
     }
     ```

5. Wire nonce generation into request creation (wherever RequestContext is constructed):
   - Find all `new RequestContext(...)` call sites
   - Add `nonceVerifier.generateNonce()` as nonce parameter

6. Add `nonceVerifier.close()` to KerlDHT shutdown path

7. Run integration tests: `./mvnw test -pl thoth -Dtest=KerlDHTNonceVerificationTest`

**Success Criteria**:
- [ ] RequestContext includes nonce field
- [ ] validateResponseFreshness checks nonce uniqueness
- [ ] Replay detection triggers Byzantine signal
- [ ] Backward-compatible constructor works (no changes to existing call sites)
- [ ] All 5 integration tests pass

**Build/Test Commands**:
```bash
./mvnw test -pl thoth -Dtest=KerlDHTNonceVerificationTest
```

---

### A.4 — Nonce Verification Module Verification (Delos-izm.1.3.4)

**Module**: thoth
**Type**: Verification and cleanup
**Depends on**: Delos-izm.1.3.3

**Steps**:

1. Run full thoth test suite:
   ```bash
   ./mvnw test -pl thoth
   ```

2. Verify no regressions in existing 276+ KerlDHT tests:
   - All `KerlDHTResponseFreshnessTest` tests pass
   - All `KerlDHTLifecycleTest` tests pass (shutdown includes NonceVerifier close)
   - All `ByzantineFaultToleranceTest` tests pass

3. Remove the Phase 3 TODO comment at KerlDHT.java line 327-328:
   - Replace TODO with documentation of the implemented nonce verification

4. Verify compilation of dependent modules:
   ```bash
   ./mvnw compile -pl thoth -amd
   ```

5. Update KerlDHT Javadoc to reflect nonce verification capability

**Success Criteria**:
- [ ] `./mvnw test -pl thoth` passes with 0 failures
- [ ] No regressions in existing tests
- [ ] TODO at line 327 resolved (replaced with implementation documentation)
- [ ] Downstream modules compile

**Build/Test Commands**:
```bash
./mvnw test -pl thoth
./mvnw compile -pl thoth -amd
```

---

## Track B: RecursiveProofValidator (Delos-izm.1.5)

### B.1 — Security Tests & constructEpochMessage (Delos-izm.1.5.1)

**Module**: witness-service
**Type**: TDD test writing + foundational implementation

**New Files**:
- `witness-service/src/test/java/com/hellblazer/delos/witness/aggregation/recursive/RecursiveProofValidatorSecurityTest.java`

**Modified Files**:
- `witness-service/src/main/java/com/hellblazer/delos/witness/aggregation/recursive/RecursiveProofValidator.java`

**Steps**:

1. Write security tests in `RecursiveProofValidatorSecurityTest.java`:
   - `testForgedIntermediateNode_Rejected` — construct a tree with a forged intermediate node (random signature bytes), assert verification REJECTS it. **This test will FAIL with current code** (returns valid() unconditionally).
   - `testConstructEpochMessage_Deterministic` — same epoch + root hash produces same message
   - `testConstructEpochMessage_DifferentEpochs_DifferentMessages` — different epochs produce different messages
   - `testConstructEpochMessage_DifferentRootHashes_DifferentMessages` — different root hashes produce different messages
   - `testConstructEpochMessage_NotZeroArray` — result is NOT `new byte[32]` filled with zeros

2. Implement `constructEpochMessage` (line 1006-1009):
   ```java
   private byte[] constructEpochMessage(long epochNumber, Digest previousRootHash) {
       var buffer = ByteBuffer.allocate(8 + previousRootHash.getBytes().length);
       buffer.putLong(epochNumber);  // 8 bytes big-endian
       buffer.put(previousRootHash.getBytes());
       return DigestAlgorithm.DEFAULT.digest(buffer.array()).getBytes();
   }
   ```
   - This is the foundation that leaf and intermediate message construction depend on

3. Verify constructEpochMessage tests pass, forged node test still fails (expected)

**Key Types**:
- `DigestAlgorithm.DEFAULT` = `BLAKE2B_256` (cryptography module)
- `Digest.getBytes()` returns the raw hash bytes (line 230 of Digest.java)
- `BLSOperations.verifyAggregate(keys, message, aggregate)` at line 194 of BLSOperations.java

**Success Criteria**:
- [ ] constructEpochMessage tests all PASS (deterministic, non-zero)
- [ ] Forged intermediate node test COMPILES but FAILS (vulnerability confirmed)
- [ ] constructEpochMessage implementation is canonical (big-endian epoch || root hash bytes, then hashed)

**Build/Test Commands**:
```bash
./mvnw test -pl witness-service -Dtest=RecursiveProofValidatorSecurityTest
```

---

### B.2 — Leaf & Intermediate Node Message Construction (Delos-izm.1.5.2)

**Module**: witness-service
**Type**: Implementation (replace placeholders)
**Depends on**: Delos-izm.1.5.1

**Modified Files**:
- `witness-service/src/main/java/com/hellblazer/delos/witness/aggregation/recursive/RecursiveProofValidator.java`

**Steps**:

1. **Leaf node message** (line 751-753):
   Replace:
   ```java
   // TODO: Message construction - need to know what message was signed
   // For now, use a placeholder. In practice, this would be the event digest.
   var message = new byte[32]; // Placeholder
   ```
   With:
   ```java
   // Leaf message: the epoch message that was signed by this committee
   // Uses committeeEpoch and the root hash for that epoch (from rootHashLookup)
   var message = constructEpochMessage(epochNumber, rootHashForEpoch);
   ```
   Note: Need to thread the root hash lookup into the verifyTreeNodeBLS method, or use a method parameter. Check how `rootHashLookup` (from verifyReceipt) can reach this point. May need to add a parameter to `verifyTreeNodeBLS`.

2. **Intermediate node message** (lines 818-820):
   Replace:
   ```java
   // TODO: Message and aggregate construction for intermediate nodes
   // This requires understanding how intermediate signatures are aggregated
   var message = new byte[32]; // Placeholder
   ```
   With:
   ```java
   // Intermediate message: hash of concatenated child node hashes
   // Each child's "hash" is the hash of its aggregated signature bytes
   var childData = ByteBuffer.allocate(intermediate.children().size() * 32);
   for (var child : intermediate.children()) {
       var childSigBytes = extractSignatureBytes(child);
       childData.put(DigestAlgorithm.DEFAULT.digest(childSigBytes).getBytes());
   }
   var message = DigestAlgorithm.DEFAULT.digest(childData.array()).getBytes();
   ```

3. **committeeEpoch encoding documentation** (line 721):
   Replace TODO comment with documentation:
   ```java
   // committeeEpoch encoding: committeeEpoch field directly encodes the epoch number.
   // leaf.index() provides the committee index within that epoch.
   // This is the canonical encoding per design doc (2026-03-12).
   ```

4. **Epoch resolution for intermediate nodes** (line 793):
   Document the current approach (extract from first child) and add validation:
   ```java
   // Epoch resolution: intermediate nodes derive epoch from first child.
   // All children in a well-formed tree share the same epoch.
   // TODO validation: assert all children have consistent epochs.
   ```

5. Add helper method `extractSignatureBytes(TreeNode)`:
   ```java
   private byte[] extractSignatureBytes(TreeNode node) {
       return switch (node) {
           case TreeNode.LeafNode leaf -> leaf.aggregatedSignature().toBytes();
           case TreeNode.IntermediateNode intermediate -> intermediate.aggregatedSignature().toBytes();
       };
   }
   ```

**Success Criteria**:
- [ ] No `new byte[32]` placeholders remain in message construction
- [ ] Leaf message uses constructEpochMessage with real epoch data
- [ ] Intermediate message is hash of child signature hashes
- [ ] committeeEpoch encoding documented (line 721 TODO resolved)
- [ ] Code compiles: `./mvnw compile -pl witness-service`

**Build/Test Commands**:
```bash
./mvnw compile -pl witness-service
./mvnw test -pl witness-service -Dtest=RecursiveProofValidatorSecurityTest
```

---

### B.3 — verifyIntermediateNodeBLS Implementation (Delos-izm.1.5.3)

**Module**: witness-service
**Type**: Implementation (critical security fix)
**Depends on**: Delos-izm.1.5.2

**Modified Files**:
- `witness-service/src/main/java/com/hellblazer/delos/witness/aggregation/recursive/RecursiveProofValidator.java`

**Steps**:

1. Replace `verifyIntermediateNodeBLS` (line 975-993):
   Current code returns `ValidationResult.valid()` unconditionally.
   Replace with:
   ```java
   private ValidationResult verifyIntermediateNodeBLS(
       TreeNode.IntermediateNode intermediate,
       RecursiveKeyResolver keyResolver,
       byte[] message,
       GracePeriodKeyLookup gracePeriodLookup
   ) {
       // Validate signature format
       var formatResult = verifySignatureFormat(intermediate.aggregatedSignature());
       if (!formatResult.isValid()) {
           return formatResult;
       }

       // Resolve epoch and committee for key lookup
       var epochNumber = extractEpochFromNode(intermediate);
       var parentCommitteeIndex = intermediate.index();

       // Get parent-level committee keys
       List<BLSPublicKey> keys;
       try {
           keys = keyResolver.getCommitteeKeys(epochNumber, parentCommitteeIndex);
           if (keys == null || keys.isEmpty()) {
               return new ValidationResult.KeyResolutionFailure(
                   "No keys for intermediate node at depth " + intermediate.depth(),
                   epochNumber, parentCommitteeIndex,
                   KeyResolutionReason.KEYS_NOT_FOUND
               );
           }
       } catch (Exception e) {
           return new ValidationResult.KeyResolutionFailure(
               "Key resolver error: " + e.getMessage(),
               epochNumber, intermediate.depth(),
               KeyResolutionReason.KEY_RESOLVER_ERROR
           );
       }

       // Construct message from child hashes
       var childMessage = constructIntermediateMessage(intermediate);

       // Build aggregate and verify
       var aggregate = new BLSAggregate(
           intermediate.aggregatedSignature(),
           extractBitmapFromIntermediate(intermediate)
       );
       var verified = BLSOperations.verifyAggregate(keys, childMessage, aggregate);

       if (verified) {
           return ValidationResult.valid();
       } else {
           return new ValidationResult.BLSVerificationFailure(
               "Intermediate node BLS verification failed at depth " + intermediate.depth(),
               intermediate.depth(),
               intermediate.index(),
               BLSVerificationReason.AGGREGATE_MISMATCH
           );
       }
   }
   ```

2. Add helper `constructIntermediateMessage`:
   ```java
   private byte[] constructIntermediateMessage(TreeNode.IntermediateNode intermediate) {
       var childData = ByteBuffer.allocate(intermediate.children().size() * 32);
       for (var child : intermediate.children()) {
           var childSigBytes = extractSignatureBytes(child);
           childData.put(DigestAlgorithm.DEFAULT.digest(childSigBytes).getBytes());
       }
       return DigestAlgorithm.DEFAULT.digest(childData.array()).getBytes();
   }
   ```

3. Add helper `extractBitmapFromIntermediate`:
   - IntermediateNode does not have a signerBitmap field directly
   - Need to construct one from children or use a different aggregate constructor
   - Check if BLSAggregate supports intermediate nodes without bitmaps
   - May need to aggregate child bitmaps or use a full-participation bitmap

4. Run the security test: forged intermediate node test should now PASS (forged node rejected)

**Success Criteria**:
- [ ] verifyIntermediateNodeBLS performs real BLS aggregate verification
- [ ] Forged intermediate node test PASSES (node rejected)
- [ ] Valid intermediate node (with correct BLS signatures) accepted
- [ ] All 6 TODOs in RecursiveProofValidator.java resolved

**Build/Test Commands**:
```bash
./mvnw test -pl witness-service -Dtest=RecursiveProofValidatorSecurityTest
```

---

### B.4 — Test Updates & Module Verification (Delos-izm.1.5.4)

**Module**: witness-service
**Type**: Test maintenance + verification
**Depends on**: Delos-izm.1.5.3

**Modified Files**:
- `witness-service/src/test/java/com/hellblazer/delos/witness/aggregation/recursive/RecursiveProofValidatorTest.java`

**Steps**:

1. Run existing test suite to identify failures:
   ```bash
   ./mvnw test -pl witness-service -Dtest=RecursiveProofValidatorTest
   ```

2. For each failing test, determine cause:
   - If test used dummy BLS signatures (0xAA filled) and now fails BLS verification:
     - Option A: Generate real BLS test key pairs and signatures for test fixtures
     - Option B: Mock the BLS verification layer for structural tests
     - Preferred: Option A for security-critical code

3. Update test fixtures:
   - Generate test BLS key pairs using `BLSOperations.generateKeyPair()`
   - Sign test messages with generated keys
   - Use real signatures in TreeNode construction

4. Ensure all existing test scenarios still covered:
   - Single-epoch receipt verification
   - Multi-epoch receipt with chain
   - Null parameter handling
   - Hierarchical aggregate structure validation
   - Invalid node detection
   - Historical proof path verification
   - Nested intermediate node verification

5. Run full witness-service test suite:
   ```bash
   ./mvnw test -pl witness-service
   ```

6. Verify no regressions in related test files:
   - `RecursiveAggregateReceiptTest.java`
   - `RecursiveAggregationBuilderTest.java`
   - `EpochTransitionValidatorTest.java`
   - `HistoricalProofPathTest.java`

7. Verify dependent modules compile:
   ```bash
   ./mvnw compile -pl witness-service -amd
   ```

**Success Criteria**:
- [ ] All existing RecursiveProofValidatorTest tests pass (updated as needed)
- [ ] All RecursiveProofValidatorSecurityTest tests pass
- [ ] `./mvnw test -pl witness-service` passes with 0 failures
- [ ] No regressions in related test files
- [ ] Downstream modules compile

**Build/Test Commands**:
```bash
./mvnw test -pl witness-service
./mvnw compile -pl witness-service -amd
```

---

## Final Verification

After both tracks complete:

```bash
# Full project build
./mvnw clean install

# Verify no cross-module regressions
./mvnw test
```

## Test Strategy Summary

| Task | Test Type | Tests Written | Tests Pass When |
|------|-----------|---------------|-----------------|
| A.1 | Unit | 7 new | Interface + impl compiles, impl complete |
| A.2 | Unit | 0 new (run A.1 tests) | InMemoryNonceVerifier correct |
| A.3 | Integration | 5 new | KerlDHT nonce integration complete |
| A.4 | Regression | 0 new (run all) | No regressions in thoth module |
| B.1 | Security + Unit | 5 new | constructEpochMessage implemented |
| B.2 | Compilation | 0 new | Message construction compiles |
| B.3 | Security | 0 new (run B.1 tests) | BLS verification implemented |
| B.4 | Regression | 0 new (update existing) | No regressions in witness-service |

**Total new tests**: 17
**Total existing tests affected**: ~10 (RecursiveProofValidatorTest updates)

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| KerlDHT constructor change breaks call sites | Medium | Medium | Backward-compatible overload constructor |
| Existing RecursiveProofValidatorTest tests fail with real BLS | High | Low | Update test fixtures with real BLS key pairs |
| IntermediateNode lacks signerBitmap for BLS aggregate | Medium | Medium | Construct bitmap from children or use full-participation bitmap |
| Nonce eviction under high load causes false rejections | Low | Medium | Tunable capacity (100K default), async eviction |
| Root hash lookup not threaded to verifyTreeNodeBLS | Medium | Medium | Add parameter to method or use closure/context object |

## Parallelization Opportunities

- **Track A and Track B**: Fully parallel (different modules)
- **Within Track A**: Sequential (interface -> impl -> integration -> verify)
- **Within Track B**: Sequential (tests -> constructEpochMessage -> messages -> BLS verify -> cleanup)
- **Agents**: SPAWN two parallel agents, one per track, to maximize throughput

## File Inventory

### New Files (Track A)
- `thoth/src/main/java/com/hellblazer/delos/thoth/NonceVerifier.java`
- `thoth/src/main/java/com/hellblazer/delos/thoth/InMemoryNonceVerifier.java`
- `thoth/src/test/java/com/hellblazer/delos/thoth/NonceVerifierTest.java`
- `thoth/src/test/java/com/hellblazer/delos/thoth/KerlDHTNonceVerificationTest.java`

### Modified Files (Track A)
- `thoth/src/main/java/com/hellblazer/delos/thoth/KerlDHT.java` (RequestContext, constructor, validateResponseFreshness)

### New Files (Track B)
- `witness-service/src/test/java/com/hellblazer/delos/witness/aggregation/recursive/RecursiveProofValidatorSecurityTest.java`

### Modified Files (Track B)
- `witness-service/src/main/java/com/hellblazer/delos/witness/aggregation/recursive/RecursiveProofValidator.java` (6 TODOs)
- `witness-service/src/test/java/com/hellblazer/delos/witness/aggregation/recursive/RecursiveProofValidatorTest.java` (test fixtures)

## Bead Summary

| Bead ID | Title | Track | Status |
|---------|-------|-------|--------|
| Delos-izm.1.3 | Nonce Verification Implementation | A | open |
| Delos-izm.1.3.1 | NonceVerifier Interface & Unit Tests (TDD) | A | open (ready) |
| Delos-izm.1.3.2 | InMemoryNonceVerifier Implementation | A | open (blocked by 1.3.1) |
| Delos-izm.1.3.3 | KerlDHT Nonce Integration | A | open (blocked by 1.3.2) |
| Delos-izm.1.3.4 | Nonce Verification Module Verification | A | open (blocked by 1.3.3) |
| Delos-izm.1.5 | RecursiveProofValidator Completion | B | open |
| Delos-izm.1.5.1 | Security Tests & constructEpochMessage | B | open (ready) |
| Delos-izm.1.5.2 | Leaf & Intermediate Message Construction | B | open (blocked by 1.5.1) |
| Delos-izm.1.5.3 | verifyIntermediateNodeBLS Implementation | B | open (blocked by 1.5.2) |
| Delos-izm.1.5.4 | Test Updates & Module Verification | B | open (blocked by 1.5.3) |
