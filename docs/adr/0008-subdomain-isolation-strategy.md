# ADR-0008: DelegatedDomain Isolation Strategy

**Status**: ACCEPTED

**Date**: 2026-02-13

**Context**

The Delos model module provides multi-tenant process domain management through a hierarchical domain structure:

- **Domain**: Top-level abstraction for distributed processes
- **ProcessDomain**: Domain running in a JVM process with SQL state machine
- **ProcessContainerDomain**: Container for multiple isolated subdomains
- **DelegatedDomain**: Isolated tenant execution environment

To achieve true multi-tenant isolation, we need a mechanism to run subdomains in separate address spaces with enforced resource boundaries. This prevents:
1. Cross-tenant memory access
2. Resource exhaustion attacks
3. Information leakage through JVM shared state
4. Side-channel attacks via CPU caches

**Decision**

**We adopt a dual-implementation strategy for DelegatedDomain isolation:**

1. **Production/Secure Deployment: GraalVM Isolates (JniBridge)**
   - Primary implementation for AWS Nitro Enclave deployment
   - Complete address space isolation via GraalVM isolates
   - JNI bridge between host JVM and native isolate
   - Unix domain socket communication for subdomain requests
   - Location: `model/src/main/java/com/hellblazer/delos/model/demesnes/JniBridge.java`
   - Native implementation: `isolates/src/main/java/com/hellblazer/delos/demesnes/isolate/DemesneIsolate.java`

2. **Development/Testing: In-Process (DemesneImpl)**
   - Lighter-weight in-process implementation
   - Shared JVM address space
   - Simplifies debugging and development workflow
   - Location: `model/src/main/java/com/hellblazer/delos/model/demesnes/DemesneImpl.java`

**Rationale**

**1. Strategic Direction: AWS Nitro Enclaves**

AWS Nitro Enclaves provide cryptographically isolated compute environments for sensitive workloads. GraalVM isolates map cleanly to Nitro's security model:

- **Hardware-backed isolation**: Nitro enforces process boundaries at hypervisor level
- **Attestation support**: Nitro can attest to enclave code integrity
- **Memory encryption**: All memory within enclave is encrypted
- **No persistent storage**: Stateless execution matches Delos event-sourced architecture

The isolates module implements the core technology required for Nitro deployment. See `~/a-demo` repository for prototype demonstration.

**2. Why Both Implementations**

**JniBridge (Production)**:
- ✅ True address space isolation
- ✅ Resource boundary enforcement (memory, CPU)
- ✅ Compatible with Nitro Enclave security model
- ✅ Prevents cross-tenant attacks via shared JVM state
- ❌ Complex debugging (native code, separate process)
- ❌ Higher overhead per subdomain

**DemesneImpl (Development)**:
- ✅ Simple debugging (standard Java tooling)
- ✅ Fast iteration during development
- ✅ Lower overhead for local testing
- ❌ No true isolation (shared JVM heap)
- ❌ Vulnerable to cross-tenant attacks
- ❌ Not suitable for production multi-tenant deployment

**3. GraalVM Isolates Technology**

GraalVM isolates provide lightweight process-like isolation within a single OS process:

- **Separate heap**: Each isolate has its own GC heap
- **Separate threads**: Thread-local state isolated per isolate
- **Shared native code**: Single copy of native image code (memory efficient)
- **JNI bridge**: Java host process communicates with isolates via JNI
- **Resource limits**: Per-isolate memory and CPU quotas

This provides stronger isolation than traditional JVM sandboxing (SecurityManager, ClassLoaders) while maintaining lower overhead than full OS processes.

**Consequences**

**Positive**:
- Supports secure production deployment (AWS Nitro)
- Maintains development velocity (DemesneImpl for local work)
- GraalVM isolates provide proven isolation technology
- Unix domain sockets enable efficient inter-subdomain communication
- Portal routing pattern allows dynamic subdomain request forwarding
- Clean separation between security-critical (JniBridge) and convenience (DemesneImpl)

**Negative**:
- Two implementations to maintain (JniBridge + DemesneImpl)
- JniBridge debugging complexity (GDB, native code)
- GraalVM native image build adds complexity to CI/CD
- Isolates require GraalVM installation (not standard JDK)
- AWS Nitro deployment requires additional infrastructure

**Trade-offs Made**:

1. **Dual Implementation vs Single**
   - Trade-off: Maintenance burden vs development velocity
   - Decision: Accept maintenance cost to keep development simple

2. **GraalVM Isolates vs OS Processes**
   - Trade-off: Complexity vs resource overhead
   - Decision: Choose isolates for lower memory/startup cost

3. **JNI Bridge vs Pure Java**
   - Trade-off: Debugging complexity vs isolation guarantees
   - Decision: Choose security; GDB debugging acceptable for production issues

4. **AWS Nitro vs Generic Containers**
   - Trade-off: Vendor lock-in vs attestation/encryption features
   - Decision: Choose Nitro for hardware-backed security; can support containers later

**Architectural Integration Points**

1. **ProcessContainerDomain**: Creates JniBridge or DemesneImpl based on configuration
2. **Portal Routing**: Routes GRPC requests to appropriate subdomain via Unix domain sockets
3. **KERI Delegation**: Subdomains delegate identity to parent ProcessDomain
4. **Gossip Protocol**: Replicates delegated KERI events across cluster
5. **Fireflies**: Subdomain membership within container context
6. **SQL State Machine**: Each subdomain has isolated SQL state

**Security Properties Guaranteed (JniBridge)**

1. **Address Space Isolation**: Separate GraalVM isolate heap per subdomain
2. **Resource Quotas**: Per-isolate memory and CPU limits
3. **No Shared State**: Each subdomain has independent JVM state
4. **Communication Isolation**: Unix domain sockets enforce message boundaries
5. **Lifecycle Independence**: Subdomain crash does not affect parent process

**Threat Model & Attack Surface**

1. **JNI Bridge Compromise**
   - If native library is exploited, attacker gains isolate access (not host JVM)
   - Isolation boundary: Compromised isolate cannot access other isolates or host process memory
   - Defense: GraalVM isolate guarantees enforced at native code level

2. **Side-Channel Attacks**
   - GraalVM isolates share CPU resources; timing attacks theoretically possible
   - Mitigation: AWS Nitro Enclaves provide hardware-level CPU isolation
   - Production deployment adds defense-in-depth via Nitro hardware boundaries

3. **Unix Domain Socket Tampering**
   - File permissions enforce process-level access control
   - Socket files created with 0600 permissions (owner-only)
   - Attack requires compromising parent process or OS privilege escalation

4. **Isolate Escape Vulnerabilities**
   - Requires GraalVM vulnerability in isolate boundary enforcement
   - Mitigation: GraalVM security updates monitored and applied
   - AWS Nitro provides additional containment if isolate escapes

5. **Resource Exhaustion (DoS)**
   - Malicious subdomain could exhaust isolate memory quota
   - Mitigation: Per-isolate resource limits enforced by GraalVM
   - Parent process monitors isolate health and terminates runaway subdomains

6. **Information Leakage via Shared Libraries**
   - Native libraries loaded into isolates are process-shared
   - Mitigation: Isolates use separate heap; no shared global state
   - Nitro deployment isolates entire enclave from host

**Implementation Status**

| Component | Status | Coverage | Notes |
|-----------|--------|----------|-------|
| JniBridge (model module) | ✅ COMPLETE | Basic operations | **Bug fixed**: stop() method (2026-02-13) |
| DemesneIsolate (isolates module) | ✅ COMPLETE | Full GraalVM implementation | Production-ready |
| Integration tests | ✅ COMPLETE | Unix domain sockets | `isolate-ftesting/src/test/java/` |
| DemesneImpl | ✅ COMPLETE | In-process variant | Development convenience |
| AWS Nitro prototype | 🚧 IN PROGRESS | Basic deployment | See `~/a-demo` repository |
| Production deployment | 📋 PLANNED | Nitro enclave config | Future work |

**Alternative Approaches Considered**

1. **SecurityManager Sandboxing**
   - Simpler to implement; weak isolation (same JVM heap)
   - Deprecated in Java 17+; removed in Java 21+
   - Not compatible with Nitro security model

2. **Separate OS Processes**
   - Stronger isolation; much higher memory overhead
   - Process startup cost (>100ms vs ~1ms for isolates)
   - Inter-process communication overhead

3. **Docker/Kubernetes Containers**
   - Standard cloud deployment; lacks Nitro attestation
   - Higher overhead than isolates
   - Can be supported as alternative deployment target later

4. **JVM ClassLoader Isolation**
   - Weak isolation; shared heap and thread state
   - Vulnerable to reflection attacks
   - Not suitable for adversarial multi-tenant scenarios

**Related Decisions**

- ADR-0002: KERI Implementation (subdomain delegation)
- ADR-0003: BFT Membership (Fireflies integration)
- ADR-0004: Consensus Design (CHOAM for process domain state)
- ADR-0005: Deterministic SQL (subdomain state machines)

**Related Beads**

- Delos-wubr: Isolation strategy decision (CLOSED 2026-02-13)
- Delos-bw18: Fix JniBridge.stop() resource leak (CLOSED 2026-02-13)
- Delos-0xf3: Platform-specific isolation tests (OPEN)
- Delos-mka0: Portal routing implementation (OPEN)

**Migration Strategy**

The rollout of JniBridge-based isolation follows a phased approach to validate functionality and performance before production deployment:

**Phase 1: Development & Local Testing** (Current)
- Use DemesneImpl for rapid iteration and debugging
- Test JniBridge locally on developer workstations
- Validate Unix domain socket communication
- Ensure feature parity between DemesneImpl and JniBridge

**Phase 2: Integration Testing** (Q1 2026)
- Deploy JniBridge to CI/CD pipeline
- Run full test suite with GraalVM isolates enabled
- Measure performance overhead vs DemesneImpl baseline
- Validate resource isolation under adversarial workloads

**Phase 3: Staging Deployment** (Q2 2026)
- Deploy to AWS staging environment with Nitro Enclaves
- Test attestation integration with KERI
- Validate encrypted communication between host and enclave
- Load testing with production-like traffic patterns
- Measure latency, throughput, memory usage

**Phase 4: Production Rollout** (Q3 2026)
- Canary deployment: 5% of subdomains use JniBridge
- Monitor for resource leaks, crashes, performance regressions
- Gradual rollout: 25% → 50% → 100%
- DemesneImpl remains available as fallback configuration

**Phase 5: Optimization** (Q4 2026)
- Performance tuning based on production telemetry
- Resource quota optimization
- Consider alternative deployment targets (Docker, other confidential compute platforms)

**Rollback Plan**:
- Configuration flag controls JniBridge vs DemesneImpl selection
- Can revert to DemesneImpl per-subdomain or globally
- No data migration required (state stored in SQL, not isolate)

**Success Metrics**:
- Zero isolate escape incidents
- <5% performance overhead vs DemesneImpl
- <1% resource leak rate
- 99.9% uptime for subdomain lifecycle operations

**Future Work**

1. **AWS Nitro Enclave Deployment** (see `~/a-demo`)
   - Enclave image creation from isolates module
   - Attestation integration with KERI
   - Encrypted communication between host and enclave
   - Production monitoring and observability

2. **Resource Management**
   - Per-subdomain memory quotas
   - CPU throttling for noisy neighbors
   - Lifecycle management (creation, suspension, termination)

3. **Security Hardening**
   - Fuzzing of JNI bridge boundary
   - Side-channel attack analysis
   - Formal verification of isolation properties

4. **Alternative Deployment Targets**
   - Google Confidential Computing (SEV-SNP)
   - Azure Confidential Computing (SGX)
   - Standard Docker containers (development)

**References**

- GraalVM Isolates: https://www.graalvm.org/latest/reference-manual/java/isolates/
- AWS Nitro Enclaves: https://aws.amazon.com/ec2/nitro/nitro-enclaves/
- Prototype Demo: `~/a-demo` repository
- Core Classes:
  - `model/src/main/java/com/hellblazer/delos/model/demesnes/JniBridge.java`
  - `isolates/src/main/java/com/hellblazer/delos/demesnes/isolate/DemesneIsolate.java`
  - `model/src/main/java/com/hellblazer/delos/model/demesnes/DemesneImpl.java`
- Test Coverage: `isolate-ftesting/src/test/java/com/hellblazer/delos/domain/`

---

**Decision Made By**: Model Module Remediation (Delos-wubr)
**Last Updated**: 2026-02-13
**Status**: ACCEPTED - JniBridge bug fixed, both implementations maintained
