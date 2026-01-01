# Delos Remediation - Risk Register

## Risk Summary

| ID | Risk | Probability | Impact | Priority | Status |
|----|------|-------------|--------|----------|--------|
| R001 | Consensus corruption from Ethereal bug | HIGH | CRITICAL | P0 | OPEN |
| R002 | Authorization bypass from Delphinius staleness | MEDIUM | CRITICAL | P0 | OPEN |
| R003 | Bloom filter collision attacks | MEDIUM | HIGH | P1 | OPEN |
| R004 | Cascading test failures | LOW | MEDIUM | P2 | OPEN |
| R005 | Build reproducibility issues | MEDIUM | LOW | P3 | OPEN |
| R006 | Incomplete understanding of paper algorithms | LOW | HIGH | P2 | OPEN |

---

## Critical Risks (P0)

### R001: Consensus Corruption from Ethereal Switch Bug

**Description**: The switch statement fall-through in `UnanimousVoter.java` causes POPULAR votes to set both `votesOne` and `votesZero` to true, potentially corrupting consensus voting.

**Location**: `ethereal/src/main/java/com/hellblazer/delos/ethereal/linear/UnanimousVoter.java:283-289`

**Impact**:
- Consensus decisions may be incorrect
- Byzantine fault tolerance guarantees violated
- Potential for network forks or stalls

**Probability**: HIGH (bug exists in production code path)

**Detection**:
- May manifest as consensus failures under load
- Difficult to detect without specific test coverage

**Mitigation**:
1. **Immediate**: Add break statements to switch
2. **Verification**: Write test that verifies vote counting
3. **Validation**: Run full consensus test suite

**Owner**: Phase 0 team
**Target Resolution**: Week 1

---

### R002: Authorization Bypass from Delphinius Staleness

**Description**: The `check()` method reads current state instead of state at the specified timestamp, enabling the "New Enemy Problem" where stale ACLs can grant access to new content.

**Location**: `delphinius/src/main/java/com/hellblazer/delos/delphinius/DirectOracle.java:101-107`

**Impact**:
- Unauthorized access to protected resources
- Violation of authorization invariants
- Potential data exfiltration

**Probability**: MEDIUM (requires specific attack scenario)

**Attack Scenario**:
1. User A has access to resource R at time T1
2. User A's access is revoked at time T2
3. New document D is created at time T3
4. User A queries with timestamp T1, gets current state including D
5. User A gains unauthorized access to D

**Mitigation**:
1. **Immediate**: Implement true snapshot reads
2. **Verification**: Write test demonstrating vulnerability
3. **Validation**: Security review of fix

**Owner**: Phase 0 team
**Target Resolution**: Week 1

---

## High Priority Risks (P1)

### R003: Bloom Filter Collision Attacks

**Description**: MurmurHash3 used in BloomFilter is not cryptographically secure. An adversary could craft inputs that cause collisions, affecting HexBloom View ID integrity.

**Location**: `cryptography/src/main/java/com/hellblazer/delos/bloomFilters/Hash.java`

**Impact**:
- Membership validation can be spoofed
- Potential for Byzantine actors to manipulate view membership
- Security guarantees degraded

**Probability**: MEDIUM (requires sophisticated attacker)

**Mitigation**:
1. **Short-term**: Document threat model
2. **Medium-term**: Add cryptographic hash option (HMAC-BLAKE2b)
3. **Long-term**: Make cryptographic hash default for security-critical paths

**Owner**: Phase 1 team
**Target Resolution**: Week 2

---

### R004: HexBloom Cross-Context Replay

**Description**: Direct XOR of crowns without domain separator allows potential cross-context replay attacks.

**Location**: `cryptography/src/main/java/com/hellblazer/delos/cryptography/HexBloom.java`

**Impact**:
- View IDs from one context could be replayed in another
- Potential for membership confusion
- Byzantine tolerance assumptions weakened

**Probability**: LOW-MEDIUM

**Mitigation**:
1. Add context binding: `XOR(crowns) XOR H(context_id || ring_index)`
2. Verify fix doesn't break existing functionality
3. Update all crown computation sites

**Owner**: Phase 1 team
**Target Resolution**: Week 2

---

## Medium Priority Risks (P2)

### R005: Cascading Test Failures

**Description**: Fixing critical bugs may cause unexpected test failures in dependent modules.

**Impact**:
- Delays in remediation timeline
- Need to update multiple test files
- Potential for introducing new bugs

**Probability**: LOW (TDD approach mitigates)

**Mitigation**:
1. Run full test suite before any changes
2. Establish baseline test results
3. Run tests after each fix
4. Document any expected test changes

**Owner**: All teams
**Target Resolution**: Ongoing

---

### R006: Incomplete Understanding of Paper Algorithms

**Description**: Implementation deviations from papers may be intentional optimizations or may be bugs. Without deep paper understanding, fixes may introduce new issues.

**Impact**:
- Fixes may break intended optimizations
- May not fully address root cause
- Could introduce new deviations

**Probability**: LOW (papers available in mixedbread)

**Mitigation**:
1. Always reference paper before fixing
2. Document any intentional deviations
3. Use deep-research-synthesizer for complex questions
4. Review fixes with java-architect-planner

**Owner**: All teams
**Target Resolution**: Ongoing

---

## Lower Priority Risks (P3)

### R007: Build Reproducibility Issues

**Description**: The h2-deterministic module and other dependencies may have version-specific build requirements.

**Impact**:
- Build failures on different machines
- CI/CD issues
- Onboarding friction

**Probability**: MEDIUM

**Mitigation**:
1. Document build requirements clearly
2. Use Maven wrapper consistently
3. Pin all dependency versions
4. Test on clean environment

**Owner**: Infrastructure
**Target Resolution**: As needed

---

### R008: Thread Safety Issues

**Description**: Several thread safety concerns identified:
- BlockClock non-atomic operations
- BloomWindow buffer swap race
- Voting memo HashMap (not concurrent)

**Impact**:
- Race conditions under load
- Data corruption
- Inconsistent state

**Probability**: LOW (relies on specific timing)

**Mitigation**:
1. Document threading model
2. Use AtomicLong where needed
3. Use ConcurrentHashMap
4. Add thread safety tests

**Owner**: Phase 4 team
**Target Resolution**: Week 6-8

---

### R009: Ed25519-X25519 Key Conversion Security

**Description**: Key conversion is valid but requires documented threat model.

**Impact**:
- Potential for key-related attacks if threat model unclear
- Security audit concerns

**Probability**: LOW

**Mitigation**:
1. Document security assumptions
2. Consider separate keys via HKDF
3. Add to security documentation

**Owner**: Phase 3 team
**Target Resolution**: Week 5

---

## Risk Monitoring

### Weekly Review Checklist

- [ ] Review all OPEN risks
- [ ] Update probability/impact based on progress
- [ ] Add new risks identified during work
- [ ] Close resolved risks
- [ ] Update EXECUTION_STATE.md

### Risk Triggers

| Trigger | Action |
|---------|--------|
| Test failure in unrelated module | Investigate for cascading issue |
| Performance degradation | Check for thread safety issue |
| Consensus test failure | Priority escalation to P0 |
| Security concern raised | Immediate review meeting |

---

## Closed Risks

| ID | Risk | Resolution | Closed Date |
|----|------|------------|-------------|
| - | - | - | - |

---

## Risk Response Strategies

### Accept
- R005 (Cascading test failures) - Mitigated by TDD approach

### Mitigate
- R001 (Ethereal bug) - Fix immediately
- R002 (Delphinius staleness) - Fix immediately
- R003 (Bloom filter) - Add cryptographic option
- R004 (HexBloom replay) - Add context binding

### Transfer
- None currently

### Avoid
- None currently

---

## Escalation Path

| Severity | Contact | Response Time |
|----------|---------|---------------|
| CRITICAL | java-architect-planner | Immediate |
| HIGH | java-debugger | Same day |
| MEDIUM | java-developer | Next sprint |
| LOW | Backlog | As capacity allows |

---

*Last Updated: 2025-12-30*
