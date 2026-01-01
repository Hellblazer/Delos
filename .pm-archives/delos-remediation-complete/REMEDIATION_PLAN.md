# Delos Remediation Plan

## Executive Summary

This comprehensive remediation plan addresses critical findings from the cross-check critique of the Delos codebase against its foundational academic papers. The critique (stored in ChromaDB as `critique::master::delos-codebase-review-2025-12-30`) identified:

| Category | Count | Impact |
|----------|-------|--------|
| Critical Bugs | 3 | Consensus corruption, security vulnerabilities, broken API |
| Security Concerns | 3 | Cryptographic weaknesses, replay attacks |
| Thread Safety Issues | 3 | Race conditions, non-atomic operations |
| Incomplete Implementations | 5 | Missing features per paper specifications |
| Architectural Deviations | 3 | Threshold differences, undocumented protocols |

**Epic Bead**: `Delos-868` - Delos Remediation: Critical Bug Fixes and Security Hardening

---

## Phase Structure

### Phase 0: Critical Bug Fixes (IMMEDIATE - P1)

These bugs affect correctness and security. They have no dependencies and can be worked in parallel.

| Bead | Title | Module | Complexity | Status |
|------|-------|--------|------------|--------|
| Delos-868.1 | Fix Ethereal switch fall-through in UnanimousVoter | ethereal | S | Ready |
| Delos-868.2 | Fix Delphinius broken staleness check - New Enemy Problem | delphinius | M | Ready |
| Delos-868.3 | Complete expand(Subject) implementation in AbstractOracle | delphinius | M | Ready |

#### Delos-868.1: Fix Ethereal switch fall-through in UnanimousVoter

**Location**: `ethereal/src/main/java/com/hellblazer/delos/ethereal/linear/UnanimousVoter.java:283-289`

**Problem**:
```java
switch (counted.vote) {
case POPULAR:
    votesOne = true;
    // MISSING BREAK - falls through!
case UNPOPULAR:
    votesZero = true;
    // MISSING BREAK - falls through!
default:
}
```

**Impact**: POPULAR case sets BOTH votesOne AND votesZero to true, corrupting consensus voting.

**Fix**: Add `break;` after each case.

**Acceptance Criteria**:
- [ ] Break statements added after POPULAR and UNPOPULAR cases
- [ ] Unit test verifies POPULAR only sets votesOne
- [ ] Unit test verifies UNPOPULAR only sets votesZero
- [ ] Integration test validates consensus correctness

**Files to Modify**:
- `ethereal/src/main/java/com/hellblazer/delos/ethereal/linear/UnanimousVoter.java`
- `ethereal/src/test/java/.../UnanimousVoterTest.java` (create/enhance)

---

#### Delos-868.2: Fix Delphinius broken staleness check - New Enemy Problem

**Location**: `delphinius/src/main/java/com/hellblazer/delos/delphinius/DirectOracle.java:101-107`

**Problem**:
```java
public boolean check(Assertion assertion, ULong valid) throws SQLException {
    if (valid.compareTo(clock.get()) > 0) {
        return false;  // Only rejects FUTURE timestamps
    }
    return check(assertion);  // Reads CURRENT state, not snapshot at 'valid'
}
```

**Impact**: Authorization vulnerability - stale ACL reads can grant access to new content (New Enemy Problem per Zanzibar paper).

**Fix**: Implement true snapshot reads at specified timestamp using SQL temporal queries or versioned Edge table.

**Acceptance Criteria**:
- [ ] check(Assertion, ULong) reads Edge state as of the specified timestamp
- [ ] Uses SQL temporal queries or versioned data access
- [ ] Test proves stale timestamp returns stale ACL state
- [ ] Test proves current timestamp returns current ACL state
- [ ] Security review validates fix addresses New Enemy Problem

**Files to Modify**:
- `delphinius/src/main/java/com/hellblazer/delos/delphinius/DirectOracle.java`
- `delphinius/src/main/java/com/hellblazer/delos/delphinius/AbstractOracle.java`
- May require schema changes for temporal support

---

#### Delos-868.3: Complete expand(Subject) implementation in AbstractOracle

**Location**: `delphinius/src/main/java/com/hellblazer/delos/delphinius/AbstractOracle.java:976`

**Problem**:
```java
return Stream.empty(); // my brain hurts too much currently to construct the sql
```

**Impact**: Core API broken - cannot discover what objects a subject has access to.

**Fix**: Complete SQL query following the subjects() pattern with proper traversal of Edge table.

**Acceptance Criteria**:
- [ ] expand(Subject) returns Stream of all Objects the subject can access
- [ ] Query traverses Edge table correctly (direct + transitive)
- [ ] Handles namespaces correctly
- [ ] Performance acceptable (indexed queries)
- [ ] Unit tests verify correct object enumeration

**Files to Modify**:
- `delphinius/src/main/java/com/hellblazer/delos/delphinius/AbstractOracle.java`
- `delphinius/src/test/java/.../AbstractOracleTest.java`

---

### Phase 1: Security Hardening (P1-P2)

Security improvements. Some depend on Phase 0 completion.

| Bead | Title | Depends On | Complexity | Status |
|------|-------|------------|------------|--------|
| Delos-868.4 | Add cryptographic hash option to BloomFilter | - | M | Ready |
| Delos-868.5 | Add context binding to HexBloom crown XOR | Delos-868.4 | S | Blocked |
| Delos-868.6 | Document Ed25519-X25519 key conversion threat model | - | S | Ready |
| Delos-868.7 | Fix BlockClock thread safety | - | S | Ready |
| Delos-868.8 | Fix BloomWindow buffer swap race condition | Delos-868.4 | M | Blocked |
| Delos-868.9 | Address Voting Memo thread safety | Delos-868.1 | S | Blocked |

#### Delos-868.4: Add cryptographic hash option to BloomFilter

**Location**: `cryptography/src/main/java/com/hellblazer/delos/bloomFilters/Hash.java`

**Problem**: MurmurHash3 (non-cryptographic) used in BloomFilter affecting HexBloom View ID. Adversary could craft collision inputs.

**Fix**:
- Add HMAC-BLAKE2b option for security-critical contexts
- Make hash algorithm configurable via constructor/builder
- Default to MurmurHash3 for performance, opt-in to crypto hash

**Acceptance Criteria**:
- [ ] Hash interface supports pluggable algorithms
- [ ] HMAC-BLAKE2b implementation added
- [ ] HexBloom can use cryptographic hash when needed
- [ ] Backward compatible with existing code
- [ ] Tests verify collision resistance in adversarial scenarios

---

#### Delos-868.5: Add context binding to HexBloom crown XOR

**Location**: `cryptography/src/main/java/com/hellblazer/delos/cryptography/HexBloom.java`

**Problem**: Direct XOR of crowns without domain separator enables cross-context replay attacks.

**Fix**: Add context binding: `XOR(crowns) XOR H(context_id || ring_index)`

**Acceptance Criteria**:
- [ ] Crown computation includes context binding
- [ ] Cross-context replay prevented (test verifies)
- [ ] Backward compatibility plan documented
- [ ] Migration strategy for existing deployments

---

#### Delos-868.7: Fix BlockClock thread safety

**Location**: `h2-deterministic/src/main/java/org/h2/util/BlockClock.java:39-48`

**Problem**: Volatile fields with compound read-modify-write operations. Single-threaded assumption undocumented.

**Fix**: Either use AtomicLong or document threading model explicitly with assertions.

**Acceptance Criteria**:
- [ ] AtomicLong or documented single-thread guarantee
- [ ] Assertions validate threading contract at runtime
- [ ] Tests verify correctness under concurrent access or single-thread enforcement

---

### Phase 2: API Completion (P2)

Complete missing functionality. Depends on Phase 0/1.

| Bead | Title | Depends On | Complexity | Status |
|------|-------|------------|------------|--------|
| Delos-868.10 | Complete BlockDate implementation | Delos-868.7 | M | Blocked |
| Delos-868.11 | Implement Watch API for Delphinius | Delos-868.2, Delos-868.3 | L | Blocked |
| Delos-868.12 | Enforce witness threshold in KERL interface | - | M | Ready |
| Delos-868.13 | Implement content-change checks in Delphinius | Delos-868.11 | M | Blocked |

#### Delos-868.11: Implement Watch API for Delphinius

**Location**: `delphinius/Oracle.java`

**Problem**: Watch API not implemented per Zanzibar paper Section 2.4.

**Fix**: Implement change notification mechanism for ACL updates following Zanzibar Watch semantics.

**Acceptance Criteria**:
- [ ] Watch interface defined in Oracle
- [ ] Implementation streams ACL changes since a given timestamp
- [ ] Supports filtering by namespace/relation
- [ ] Integration test validates real-time change propagation
- [ ] Documentation explains Zanzibar alignment

**Complexity**: L - Requires event sourcing or change data capture infrastructure

---

### Phase 3: Documentation & Testing (P2)

Documentation and test coverage. Depends on implementation phases.

| Bead | Title | Depends On | Complexity | Status |
|------|-------|------------|------------|--------|
| Delos-868.14 | Document 2/3 vs 3/4 threshold decision | Delos-868.1 | S | Blocked |
| Delos-868.15 | Document recovery protocol mapping | Delos-868.10 | M | Blocked |
| Delos-868.16 | Implement checkpoint chain verification | - | M | Ready |
| Delos-868.17 | Add comprehensive test coverage | Delos-868.1,2,3 | L | Blocked |

#### Delos-868.14: Document 2/3 vs 3/4 threshold decision

**Problem**: Fireflies uses 2/3+1 threshold vs Rapid paper's 3/4 quorum for cut detection.

**Deliverable**: Decision record with:
- [ ] Explanation of the threshold difference
- [ ] Security analysis comparing 2/3 vs 3/4
- [ ] Trade-off rationale (performance vs safety margin)
- [ ] Configuration recommendation for production

---

### Phase 4: Architectural Improvements (P3)

Long-term enhancements. Low priority but valuable.

| Bead | Title | Depends On | Complexity | Status |
|------|-------|------------|------------|--------|
| Delos-868.18 | Implement dynamic diameter adaptation | - | L | Ready |
| Delos-868.19 | Audit DigestAlgorithm.NONE usage | - | S | Ready |
| Delos-868.20 | Design Zanzibar-style runtime rewrites | Delos-868.11, Delos-868.13 | XL | Blocked |

---

## Dependency Graph

```
Phase 0 (Parallel - No Dependencies)
├── Delos-868.1 (Ethereal switch)
├── Delos-868.2 (Delphinius staleness)
└── Delos-868.3 (expand Subject)

Phase 1 (Security)
├── Delos-868.4 (BloomFilter hash) ──┬─→ Delos-868.5 (HexBloom context)
│                                    └─→ Delos-868.8 (BloomWindow race)
├── Delos-868.6 (Ed25519 docs)
├── Delos-868.7 (BlockClock safety) ──→ Delos-868.10 (BlockDate)
└── Delos-868.1 ──→ Delos-868.9 (Voting Memo safety)

Phase 2 (API)
├── Delos-868.2 ──┬
└── Delos-868.3 ──┴─→ Delos-868.11 (Watch API) ──→ Delos-868.13 (content-change)
├── Delos-868.12 (KERL threshold)
└── Delos-868.10 ──→ Delos-868.15 (Recovery docs)

Phase 3 (Docs/Tests)
├── Delos-868.1 ──→ Delos-868.14 (Threshold docs)
└── Delos-868.1,2,3 ──→ Delos-868.17 (Test coverage)
├── Delos-868.16 (Checkpoint verification)

Phase 4 (Enhancements)
├── Delos-868.18 (Dynamic diameter)
├── Delos-868.19 (NONE audit)
└── Delos-868.11,13 ──→ Delos-868.20 (Zanzibar rewrites)
```

### Critical Path

The longest dependency chain determining minimum remediation time:

```
Delos-868.2/3 → Delos-868.11 → Delos-868.13 → Delos-868.20
(Staleness/expand) → (Watch API) → (Content-change) → (Zanzibar rewrites)
```

**Critical Path Estimate**: ~3-4 weeks for core functionality (Phase 0-2)

---

## Risk Assessment

### High Risk

| Risk | Impact | Mitigation |
|------|--------|------------|
| Ethereal switch bug in production | Consensus corruption | Immediate hotfix, expedited testing |
| Delphinius staleness enables unauthorized access | Security breach | No production use until fixed |
| BlockClock race under concurrent load | Determinism violation | Document or fix before high-load scenarios |

### Medium Risk

| Risk | Impact | Mitigation |
|------|--------|------------|
| HexBloom replay attacks | View spoofing | Add context binding in Phase 1 |
| Threshold difference from paper | Reduced safety margin | Document decision, monitor in production |
| Recovery protocol deviates | Extended recovery time | Document mapping, add tests |

### Low Risk

| Risk | Impact | Mitigation |
|------|--------|------------|
| Missing Watch API | Feature gap | Defer to Phase 2 |
| DigestAlgorithm.NONE misuse | Potential security gap | Audit in Phase 4 |

---

## Parallel Execution Opportunities

### Maximum Parallelism Points

1. **Phase 0**: All three critical bugs (Delos-868.1, .2, .3) can be fixed simultaneously
2. **Phase 1**: Delos-868.4, .6, .7 can run in parallel
3. **Independent tracks**:
   - Track A: .1 → .9 → .14
   - Track B: .4 → .5, .8
   - Track C: .7 → .10 → .15
   - Track D: .2, .3 → .11 → .13

### Resource Allocation Suggestion

| Developer | Focus Area | Beads |
|-----------|------------|-------|
| Dev 1 | Ethereal/Consensus | .1, .9, .14 |
| Dev 2 | Delphinius/Authorization | .2, .3, .11, .13 |
| Dev 3 | Cryptography | .4, .5, .6, .8 |
| Dev 4 | SQL-State/Threading | .7, .10, .15, .16 |

---

## Verification Methodology

### Per-Fix Requirements

1. **TDD Discipline**: Write tests FIRST before implementation
2. **Compilation Gate**: Code must compile with all tests before PR
3. **Review Gate**: Use code-review-expert agent for all changes
4. **Documentation**: Update relevant docs alongside code

### Integration Testing

1. Run full test suite: `./mvnw clean install`
2. Large test suite: `./mvnw clean install -Dlarge_tests=true`
3. Module-specific: `./mvnw test -pl <module>`

### Knowledge Base Updates

After completing each phase:
1. Update ChromaDB with implementation decisions
2. Update crossref documents with any deviations
3. Archive completed critique findings

---

## Related ChromaDB Documents

- `critique::master::delos-codebase-review-2025-12-30` - Source critique
- `crossref::ethereal::implementation` - Ethereal module mapping
- `crossref::delphinius::implementation` - Delphinius module mapping
- `crossref::cryptography::implementation` - Cryptography module mapping
- `critique::delphinius::zanzibar-comparison` - Zanzibar gap analysis

---

## Commands Reference

### Beads

```bash
bd ready                           # Show unblocked work
bd blocked                         # Show blocked work
bd show Delos-868                  # Show epic details
bd update Delos-868.1 --status in_progress  # Start work
bd close Delos-868.1               # Complete work
bd dep add <id> <blocker>          # Add dependency
```

### Build

```bash
./mvnw clean install               # Full build
./mvnw test -pl ethereal           # Test Ethereal module
./mvnw test -pl delphinius         # Test Delphinius module
./mvnw test -Dtest=UnanimousVoterTest  # Single test
```

---

## Approval

**Created**: 2025-12-30
**Author**: Strategic Planner Agent
**Critique Source**: Deep Analyst cross-check against academic papers
**Status**: Awaiting plan-auditor review

---

## Appendix: Bead Summary

| ID | Title | Type | Priority | Dependencies | Status |
|----|-------|------|----------|--------------|--------|
| Delos-868 | Delos Remediation Epic | epic | P1 | - | open |
| Delos-868.1 | Fix Ethereal switch fall-through | bug | P1 | - | ready |
| Delos-868.2 | Fix Delphinius staleness check | bug | P1 | - | ready |
| Delos-868.3 | Complete expand(Subject) | bug | P1 | - | ready |
| Delos-868.4 | Add crypto hash to BloomFilter | task | P1 | - | ready |
| Delos-868.5 | Add HexBloom context binding | task | P1 | .4 | blocked |
| Delos-868.6 | Document Ed25519-X25519 | task | P2 | - | ready |
| Delos-868.7 | Fix BlockClock thread safety | task | P2 | - | ready |
| Delos-868.8 | Fix BloomWindow race | task | P2 | .4 | blocked |
| Delos-868.9 | Fix Voting Memo safety | task | P2 | .1 | blocked |
| Delos-868.10 | Complete BlockDate | feature | P2 | .7 | blocked |
| Delos-868.11 | Implement Watch API | feature | P2 | .2, .3 | blocked |
| Delos-868.12 | Enforce KERL witness threshold | task | P2 | - | ready |
| Delos-868.13 | Implement content-change checks | task | P2 | .11 | blocked |
| Delos-868.14 | Document threshold decision | chore | P2 | .1 | blocked |
| Delos-868.15 | Document recovery protocol | chore | P2 | .10 | blocked |
| Delos-868.16 | Checkpoint chain verification | task | P2 | - | ready |
| Delos-868.17 | Comprehensive test coverage | chore | P2 | .1, .2, .3 | blocked |
| Delos-868.18 | Dynamic diameter adaptation | feature | P3 | - | ready |
| Delos-868.19 | Audit DigestAlgorithm.NONE | chore | P3 | - | ready |
| Delos-868.20 | Design Zanzibar rewrites | feature | P3 | .11, .13 | blocked |
