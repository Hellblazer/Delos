# Stereotomy Module Security Remediation Plan

## Overview

**Epic**: Delos-7ro - Stereotomy Security Remediation (TDD)
**Status**: IN PROGRESS
**ChromaDB**: `plan::stereotomy::security-remediation-2026-01-02`
**Branch**: `fix/ethereal-signature-validation` (current) or create new branch

## Critical Issues

| ID | Issue | Bead (Test) | Bead (Fix) | Status |
|----|-------|-------------|------------|--------|
| CRIT-1 | XOR Permutation Attack | Delos-afy | Delos-1tj | READY |
| CRIT-2 | MemKERL Race Condition | Delos-6mw | Delos-8kb | Blocked by P1 |
| CRIT-3 | Inception Signature Bypass | Delos-s1c | Delos-sn8 | READY |
| CRIT-4 | UniKERL Transaction Gaps | Delos-al8 | Delos-4xi | Blocked by P1 |
| CRIT-5 | State Transition Atomicity | Delos-36f | N/A | Blocked by P2 |
| CRIT-6 | Key Material Not Cleared | Delos-7cw | Delos-srr | READY |

## Phase Structure

### Phase 1: Cryptographic Correctness (Day 1-2)
**Phase Bead**: Delos-bqq (IN_PROGRESS)

Ready Tasks (can run in parallel):
1. **Delos-afy**: CRIT-1 - Write failing tests for XOR permutation
2. **Delos-s1c**: CRIT-3 - Write failing tests for inception signature
3. **Delos-7cw**: CRIT-6 - Write failing tests for key material

Blocked Tasks:
4. **Delos-1tj**: CRIT-1 - Fix (depends on Delos-afy)
5. **Delos-sn8**: CRIT-3 - Fix (depends on Delos-s1c)
6. **Delos-srr**: CRIT-6 - Fix (depends on Delos-7cw)

### Phase 2: Storage Layer Hardening (Day 3-4)
**Phase Bead**: Delos-dip

Blocked Tasks (waiting for P1 fixes):
1. **Delos-6mw**: CRIT-2 - Write failing tests for race condition
2. **Delos-8kb**: CRIT-2 - Fix (depends on Delos-6mw)
3. **Delos-al8**: CRIT-4 - Write failing tests for transactions
4. **Delos-4xi**: CRIT-4 - Fix (depends on Delos-al8)

### Phase 3: Validation & Verification (Day 5)
**Phase Bead**: Delos-080

Blocked Tasks (waiting for P2):
1. **Delos-36f**: CRIT-5 - Investigate state atomicity
2. **Delos-0v5**: Integration tests
3. **Delos-dqq**: Documentation update

## Dependency Graph

```
              Delos-7ro (Epic)
                    |
         +--------------------+
         |                    |
    Delos-bqq (P1)      Delos-dip (P2)      Delos-080 (P3)
         |                    |                    |
    +----+----+          +----+----+         +----+----+
    |    |    |          |         |         |    |    |
 afy  s1c  7cw        6mw       al8       36f  0v5  dqq
    |    |    |          |         |              |
 1tj  sn8  srr        8kb       4xi              |
    |    |    |          |         |              |
    +----+----+----------+---------+--------------+
              |
        (All P1 fixes complete)
              |
         P2 tests ready
```

## Files to Modify

### Phase 1
- `stereotomy/.../identifier/spec/KeyConfigurationDigester.java` (CRIT-1)
- `stereotomy/.../processing/KeyEventProcessor.java` (CRIT-3)
- `stereotomy/.../jks/JksKeyStore.java` (CRIT-6)
- `stereotomy/.../mem/MemKeyStore.java` (CRIT-6)

### Phase 2
- `stereotomy/.../mem/MemKERL.java` (CRIT-2)
- `stereotomy/.../db/UniKERL.java` (CRIT-4)

### Tests to Create
- `KeyConfigurationDigesterSecurityTest.java`
- `InceptionSignatureSecurityTest.java`
- `KeyMaterialSecurityTest.java`
- `MemKERLConcurrencyTest.java`
- `UniKERLTransactionTest.java`
- `StereotomySecurityIntegrationTest.java`

## Commands

```bash
# Show ready work
bd ready

# Run stereotomy tests
./mvnw test -pl stereotomy

# Run specific test
./mvnw test -pl stereotomy -Dtest=KeyConfigurationDigesterSecurityTest

# Update task status
bd update <id> --status in_progress
bd close <id>
```

## Quality Gates

Before closing each fix task:
- [ ] Failing test exists (TDD Step 1)
- [ ] Fix makes test pass (TDD Step 2)
- [ ] No regression in existing tests
- [ ] Code compiled successfully

Before Phase completion:
- [ ] All phase tasks closed
- [ ] `./mvnw test -pl stereotomy` passes
- [ ] Dependent modules tested: `./mvnw test -pl thoth,gorgoneion`

## Notes

- Always write failing tests FIRST (TDD discipline)
- Use sequential thinking for complex debugging
- Search ChromaDB for prior art before implementation
- Update this file as tasks complete

---
**Created**: 2026-01-02
**Last Updated**: 2026-01-02
