# Stereotomy

The Stereotomy module provides base trust and identifiers for the rest of Delos. Identifiers are autonomic, self
describing, self certifying and decentralized.

## Based on KERI

Stereotomy is a faithful implementation of
the [Key Event Receipt Infrastructure, or KERI](https://github.com/decentralized-identity/keri). This implementation
uses Protobuf serializations for the key events.

## Stereotomy Controller

Like most of the KERI implementations, the Stereotomy controller is the point of entry for entities that control keys n'
such. It is largely a policy enforcement via APIs for managing identifiers, signing, issuing, etc.

## Status

The Inception, Rotation and Interaction KERI events are implemented and minimally tested. The Stereotomy class provides
a minimal KERI controller prototype, operating in direct mode (i.e., writes to its own maintained logs).
The Current state includes the KERI Verifier and Validator implementations, as well as persistent (or in memory) key
state
store - KERI's version of the chain state of KERI events. Key management isn't sorted yet, as I need
to integrate PKCS11 and other models. The Stereotomy controller model is primitive and isn't appropriate for personal
use points, etc., and so refactoring the kernel out to support multiple use cases will continue.

## Security

Stereotomy underwent comprehensive security remediation as of January 2026, addressing 6 critical security considerations with formal threat modeling and validation. The module has been hardened against concurrency attacks, database corruption, and side-channel key extraction.

**Vulnerability Status:**
- 4 critical vulnerabilities fixed with code changes and test coverage
- 2 protocol design properties validated as intentional (not exploitable)
- 16+ dedicated security tests for concurrency, persistence, and key material handling
- Full threat model and security analysis documented

For detailed security analysis, attack vectors, mitigations, and test coverage, see [`docs/THREAT_MODEL.md`](./docs/THREAT_MODEL.md).

## Documentation and Resources

Developers working with Stereotomy (KERI implementation) can reference these documentation resources:

### Identity & KERI Architecture
- **[KERI Integration Guide](../docs/KERI_INTEGRATION.md)** - KERI architecture, key events, identifier lifecycle
- **[Architecture Guide](../docs/ARCHITECTURE.md)** - Stereotomy's role in identity/security layer
- **[Gorgoneion README](../gorgoneion/README.md)** - Identity bootstrapping using Stereotomy
- **[Fireflies README](../fireflies/README.md)** - Member identification via Stereotomy identifiers

### Security & Cryptography
- **[Security Threat Model](../docs/SECURITY_THREAT_MODEL.md)** - Threat model, attack surfaces, mitigations
- **[Cryptography Algorithms](../docs/CRYPTOGRAPHY_ALGORITHMS.md)** - ED25519, BLS-12-381, signature schemes
- **[Security Threat Model - Stereotomy](./docs/THREAT_MODEL.md)** - Module-specific threat analysis and vulnerabilities

### Development & Testing
- **[Testing Guide](../docs/TESTING_GUIDE.md)** - Test patterns, key event validation, deterministic testing
- **[Build Guide](../docs/BUILD.md)** - Building Stereotomy and dependent modules
- **[IDE Setup](../docs/IDE_SETUP.md)** - Development environment configuration

### Operations & Deployment
- **[Configuration Guide](../docs/CONFIGURATION_GUIDE.md)** - Key store configuration, PKCS11 integration
- **[Operational Checklists](../docs/OPERATIONAL_CHECKLISTS.md)** - Key rotation procedures, bootstrap checklist
- **[Hardware Requirements](../docs/HARDWARE_REQUIREMENTS.md)** - HSM requirements, key management hardware

## Implementation

Stereotomy is loosely based on the design of the foundation Java implementation of KERI.
Stereotomy uses protobuf to encode and represent KERI events.
Rather than focus on a JSON implementation of KERI events, Stereotomy
leverages protobuf for efficient implementation and cryptographic processing.  
