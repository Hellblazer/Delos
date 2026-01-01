# Phase 2 - Phase 1: Security Hardening

## Overview

**Status**: PENDING
**Priority**: P1 - HIGH
**Target Duration**: Week 3-4
**Task Count**: 7
**Dependencies**: Some depend on Phase 0

---

## Objectives

1. Implement replay attack protection in Gorgoneion
2. Fix weak default verifiers and validators
3. Address rate limiting bypass for streaming RPCs
4. Secure SQL-State script compilation
5. Implement transaction replay prevention
6. Add certificate revocation checking

---

## Task 11: Gorgoneion Replay Attack Protection

### Bead: 869.11

**Depends On**: 869.7 (KeyEventProcessor authentication)

**Problem**: No protection against replay of attestation messages.

**Impact**:
- Attacker can capture valid attestation
- Replay later to gain access
- Bypasses time-based controls

**Fix Strategy**:
1. Add nonce to attestation messages
2. Add timestamp with bounded validity window
3. Implement nonce tracking/deduplication
4. Reject expired or replayed attestations

**Implementation**:
```java
public class AttestationValidator {
    private final Set<Digest> usedNonces = Collections.newSetFromMap(
        new ConcurrentHashMap<>()
    );
    private final Duration validityWindow = Duration.ofMinutes(5);

    public boolean validate(Attestation attestation) {
        // Check timestamp in validity window
        Instant now = Instant.now();
        if (attestation.getTimestamp().isBefore(now.minus(validityWindow))) {
            return false;  // Expired
        }
        if (attestation.getTimestamp().isAfter(now.plus(validityWindow))) {
            return false;  // Future (clock skew attack)
        }
        // Check nonce uniqueness
        if (!usedNonces.add(attestation.getNonce())) {
            return false;  // Replay
        }
        // Continue with signature verification...
        return true;
    }
}
```

**Acceptance Criteria**:
- [ ] Nonce field added to attestation protocol
- [ ] Timestamp validation implemented
- [ ] Nonce deduplication working
- [ ] Expired attestations rejected
- [ ] Replay test demonstrates protection

---

## Task 12: Fix Weak Default Verifier

### Bead: 869.12

**Problem**: Default verifier accepts ALL attestations without validation.

**Impact**: Bypasses attestation security entirely when no verifier configured.

**Fix Strategy**:
1. Remove permissive default
2. Require explicit verifier configuration
3. Fail-closed when no verifier present
4. Add configuration validation

**Acceptance Criteria**:
- [ ] No permissive default verifier
- [ ] Missing verifier causes startup failure
- [ ] Configuration validates verifier presence
- [ ] Test verifies fail-closed behavior

---

## Task 13: Fix Empty Certificate Validator Leak

### Bead: 869.13

**Problem**: Empty/permissive certificate validators can leak to production through misconfiguration.

**Impact**: MTLS security bypassed in production.

**Fix Strategy**:
1. Add production mode detection
2. Fail-closed in production without proper validator
3. Add explicit `insecure()` method for development
4. Log warning for development mode

**Implementation**:
```java
public class CertificateValidatorBuilder {
    private boolean productionMode = System.getenv("PRODUCTION") != null;

    public CertificateValidator build() {
        if (validators.isEmpty()) {
            if (productionMode) {
                throw new SecurityException(
                    "No certificate validators configured in production mode");
            }
            log.warn("Using permissive validator - DEVELOPMENT ONLY");
        }
        // ...
    }
}
```

**Acceptance Criteria**:
- [ ] Production mode fails without validators
- [ ] Development mode logs warning
- [ ] Explicit insecure() for testing
- [ ] No silent bypass possible

---

## Task 14: Fix Streaming RPC Rate Limiting Bypass

### Bead: 869.14

**Problem**: Rate limiting only applies to unary RPCs; streaming RPCs bypass limits.

**Impact**: DoS via streaming message abuse.

**Fix Strategy**:
1. Apply rate limits to streaming message count
2. Add bytes-per-second limit for streams
3. Add max stream duration limit
4. Track concurrent stream count per client

**Acceptance Criteria**:
- [ ] Streaming messages rate limited
- [ ] Byte rate limiting working
- [ ] Stream duration limited
- [ ] Concurrent streams limited
- [ ] DoS test blocked

---

## Task 15: Secure SQL-State Script Compilation

### Bead: 869.15

**Problem**: Script compilation allows arbitrary code execution.

**Impact**: Remote code execution vulnerability.

**Fix Strategy**:
1. Sandbox script execution environment
2. Restrict available classes/methods
3. Add timeout for script execution
4. Disable by default, require explicit enable
5. Audit existing script usage

**Implementation Options**:
1. Use GraalJS with restricted context
2. Use Java SecurityManager (deprecated but functional)
3. Custom bytecode verifier
4. Whitelist-based AST transformer

**Acceptance Criteria**:
- [ ] Scripts run in sandbox
- [ ] Restricted API access
- [ ] Execution timeout enforced
- [ ] Disabled by default
- [ ] RCE test blocked

---

## Task 16: Implement Transaction Replay Prevention

### Bead: 869.16

**Depends On**: 869.1-4 (CHOAM fixes)

**Problem**: No nonce checking, timestamp validation, or transaction deduplication.

**Impact**:
- Double-spend attacks possible
- DoS via replay flood
- Audit trail pollution

**Fix Strategy**:
1. Add nonce field to Transaction proto
2. Implement per-client nonce tracking
3. Add timestamp validation
4. Implement deduplication cache
5. Reject out-of-order nonces

**Implementation**:
```java
public interface TransactionValidator {
    ValidationResult validate(Transaction tx);
}

public class NonceBasedValidator implements TransactionValidator {
    private final Map<Digest, Long> clientNonces = new ConcurrentHashMap<>();

    public ValidationResult validate(Transaction tx) {
        Long lastNonce = clientNonces.get(tx.getClient());
        if (lastNonce != null && tx.getNonce() <= lastNonce) {
            return ValidationResult.rejected("Nonce too low");
        }
        clientNonces.put(tx.getClient(), tx.getNonce());
        return ValidationResult.accepted();
    }
}
```

**Acceptance Criteria**:
- [ ] Nonce field in Transaction
- [ ] Per-client tracking working
- [ ] Replay rejected
- [ ] Gap handling documented
- [ ] Test demonstrates prevention

---

## Task 17: Implement Certificate Revocation

### Bead: 869.17

**Problem**: No CRL/OCSP checking for member certificates.

**Impact**: Compromised members cannot be immediately revoked.

**Fix Strategy**:
1. Add CRL distribution point support
2. Implement OCSP checking (optional)
3. Add revocation cache with refresh
4. Configure revocation checking in MTLS setup

**Implementation**:
```java
public interface CertificateRevocationChecker {
    boolean isRevoked(X509Certificate cert);
    Optional<RevocationReason> getRevocationReason(X509Certificate cert);
}

public class CrlRevocationChecker implements CertificateRevocationChecker {
    private final URI crlDistributionPoint;
    private volatile Set<BigInteger> revokedSerials;
    private final Duration refreshInterval = Duration.ofHours(1);

    // ...
}
```

**Acceptance Criteria**:
- [ ] CRL checking implemented
- [ ] OCSP support (optional)
- [ ] Revocation cache with refresh
- [ ] Revoked certs rejected
- [ ] Grace period handling

---

## Definition of Done

### Per Task

- [ ] Threat model documented
- [ ] Attack test demonstrates vulnerability
- [ ] Fix implemented
- [ ] Defense test passes
- [ ] Security review completed
- [ ] Bead closed

### Phase Complete

- [ ] All 7 tasks complete
- [ ] Security review sign-off
- [ ] Full test suite passes
- [ ] No regressions
- [ ] EXECUTION_STATE.md updated

---

## Estimated Effort

| Task | Research | Test | Implementation | Review | Total |
|------|----------|------|----------------|--------|-------|
| 869.11 Replay protection | 2h | 2h | 4h | 2h | 10h |
| 869.12 Weak verifier | 1h | 1h | 2h | 1h | 5h |
| 869.13 Cert validator | 1h | 1h | 2h | 1h | 5h |
| 869.14 Stream rate limit | 2h | 2h | 4h | 2h | 10h |
| 869.15 Script sandbox | 4h | 3h | 8h | 4h | 19h |
| 869.16 Tx replay | 2h | 2h | 4h | 2h | 10h |
| 869.17 Cert revocation | 3h | 2h | 6h | 2h | 13h |
| **Total** | **15h** | **13h** | **30h** | **14h** | **72h** |

---

## Dependencies

```
Phase 0
└── 869.7 (KERI auth) ──→ 869.11 (Replay protection)
└── 869.1-4 (CHOAM) ────→ 869.16 (Tx replay prevention)

Independent
├── 869.12 (Weak verifier)
├── 869.13 (Cert validator)
├── 869.14 (Stream rate limit)
├── 869.15 (Script sandbox)
└── 869.17 (Cert revocation)
```

---

*Last Updated: 2025-12-31*
