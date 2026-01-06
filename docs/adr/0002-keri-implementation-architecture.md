# ADR-0002: KERI Implementation Architecture

**Status**: ACCEPTED

**Date**: 2026-01-06

**Context**

Delos requires a decentralized identity and key management system to support secure multi-tenant operations and MTLS-based communication. The Key Event Receipt Infrastructure (KERI) specification provides a framework for cryptographically verifiable identity and key events without reliance on a centralized authority.

This ADR documents the architectural decisions made in the Stereotomy module, which provides a faithful implementation of the KERI specification using Protobuf serialization for wire format efficiency.

**Decision**

**We adopt a layered KERI implementation with the following components:**

1. **Stereotomy (Controller Interface)**
   - Main entry point for identity and key management operations
   - Provides API for creating identifiers, signing events, and managing key state
   - Handles MTLS certificate generation with embedded KERI identity information
   - Location: `stereotomy/src/main/java/com/hellblazer/delos/stereotomy/Stereotomy.java`

2. **Key Event Processing Layer**
   - `KeyEventProcessor`: Validates and processes KERI key events
   - Implements KERI-compliant event authentication:
     - Signature verification against current key state (non-inception events)
     - Event chain integrity via prior event hash
     - Sequence number validation
     - Witness threshold enforcement for establishment events
   - Location: `stereotomy/src/main/java/com/hellblazer/delos/stereotomy/processing/KeyEventProcessor.java`

3. **Key State Management**
   - `KeyState`: Immutable representation of current keypairs and configuration
   - `KeyStateVerifier`: Abstract base for cryptographic signature verification
   - Supports point-in-time lookups via sequence number
   - Location: `stereotomy/src/main/java/com/hellblazer/delos/stereotomy/KeyState.java`

4. **Key Event Log Storage**
   - `KERL` (Key Event Receipt Log): Persistent append-only log of key events
   - `KEL` (Key Event Log): In-memory event sequence
   - Supports multiple backend implementations (memory, database)
   - Location: `stereotomy/src/main/java/com/hellblazer/delos/stereotomy/KERL.java`

5. **Identifier System**
   - `SelfAddressingIdentifier` (SAI): Identifier derived from inception event hash
   - `BoundIdentifier`: Identifier bound to a specific key state
   - `ControlledIdentifier`: Full controller with signing capability
   - Location: `stereotomy/src/main/java/com/hellblazer/delos/stereotomy/identifier/`

**Rationale**

**1. Protobuf-based Serialization**
- Provides efficient binary encoding vs JSON
- Self-describing wire format enables version evolution
- Reduces network bandwidth for high-frequency signing operations
- Enables deterministic serialization for cryptographic operations

**2. Layered Architecture**
- Separation of concerns: event processing, state management, verification
- Allows substitution of different KERL backends (memory vs persistent)
- Clear API boundaries for integration with Fireflies, Choam, Thoth modules
- Enables testing of individual layers

**3. Immutable KeyState Pattern**
- Prevents corruption of historical key state
- Thread-safe without locks (pure functional approach)
- Enables point-in-time verification of signatures across key rotations
- Consistent with event sourcing architecture used in Fireflies, Choam

**4. SAI-Based Identification**
- Inception event hash serves as self-verifying identifier
- No bootstrap ceremony required (cryptographic authority)
- Prevents identifier spoofing through signature verification
- Aligns with Delos identity bootstrapping via Gorgoneion

**Consequences**

**Positive**:
- Implements KERI specification for cryptographic identity
- Enables decentralized key management without trusted third parties
- Integrates cleanly with MTLS certificate generation (DN encoding)
- Supports key rotation with full audit trail
- Blocks attacks on identity spoofing, key forgery, out-of-order events

**Negative**:
- Adds complexity vs centralized PKI approaches
- Requires KERI specification knowledge for operators
- Event log must be maintained indefinitely (no pruning)
- Key rotation requires witness threshold coordination (if applicable)

**Trade-offs Made**:

1. **Protobuf over JSON**
   - Trade-off: Binary format vs human-readability
   - Decision: Choose efficiency; JSON variant can be added later if needed

2. **Immutable KeyState over Mutable**
   - Trade-off: Garbage collection overhead vs thread safety
   - Decision: Choose safety; modern JVM handles immutable object allocation well

3. **SAI over Named Identifiers**
   - Trade-off: Cryptographic derivation vs human-readable names
   - Decision: Choose security; Thoth provides DHT for name→SAI mapping

**Architectural Integration Points**

1. **Fireflies**: Uses Stereotomy for MTLS certificate generation and member identity verification
2. **Choam**: Validates transaction signatures using KeyStateVerifier
3. **Gorgoneion**: Bootstraps initial identifiers and witnesses
4. **Thoth**: Distributed hash table for decentralized KERL storage
5. **Delphinius**: Enforces identity-based access control policies

**Security Properties Guaranteed**

1. **Event Integrity**: Hash-chained event log prevents modification of historical events
2. **Authentication**: Signature verification against committed key state
3. **Authenticity**: SAI prevents identifier spoofing
4. **Key Confidentiality**: Private keys never transmitted; only signatures exported
5. **Rotation Safety**: New keys cannot affect historical signature verification

**Alternative Approaches Considered**

1. **Centralized PKI (X.509)**
   - Simpler to understand; compromises decentralization goal
   - Requires trusted CA; single point of failure
   - Not compatible with Delos multi-tenant architecture

2. **JSON-based KERI**
   - More human-readable; higher bandwidth and slower serialization
   - Compatible with JavaScript implementations; not a Delos requirement

3. **Alternate Event Log Storage**
   - Considered blockchain (Ethereum, etc.); requires external dependency
   - Chose in-repo KERL to maintain self-contained system

**Related Decisions**

- ADR-0001: Defer JaCoCo baseline until Java 25 support available
- ADR-0003: BFT Membership (Fireflies integration)
- ADR-0004: Consensus Design (Choam integration)
- ADR-0005: Deterministic SQL (identity in state machine)

**Implementation Status**

| Component | Status | Coverage | Notes |
|-----------|--------|----------|-------|
| Stereotomy interface | ✅ COMPLETE | All core operations | Minimal controller prototype |
| KeyEventProcessor | ✅ COMPLETE | 4-step authentication | Production-ready |
| KeyState management | ✅ COMPLETE | Immutable pattern | Fully tested |
| KERL/KEL storage | ✅ COMPLETE | Memory + database | Unicode, UniKERL implementations |
| Identifier system | ✅ COMPLETE | SAI, Bound, Controlled | KERI spec compliant |
| MTLS integration | ✅ COMPLETE | Certificate encoding | X.500 DN with embedded SAI |
| Security hardening | ✅ COMPLETE | 6 vulns fixed | Threat model documented |

**References**

- KERI Specification: https://github.com/decentralized-identity/keri
- Threat Model: `stereotomy/docs/THREAT_MODEL.md`
- Core Classes:
  - `stereotomy/src/main/java/com/hellblazer/delos/stereotomy/Stereotomy.java`
  - `stereotomy/src/main/java/com/hellblazer/delos/stereotomy/processing/KeyEventProcessor.java`
  - `stereotomy/src/main/java/com/hellblazer/delos/stereotomy/KeyStateVerifier.java`
  - `stereotomy/src/main/java/com/hellblazer/delos/stereotomy/KERL.java`
- Test Coverage: `stereotomy/src/test/java/com/hellblazer/delos/stereotomy/security/`

---

**Decision Made By**: Quality Initiative Phase 1b Team
**Last Updated**: 2026-01-06
**Status**: ACCEPTED - Implementation complete, security hardened
