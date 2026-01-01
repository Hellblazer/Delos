# Delos Remediation - Knowledge Map

## ChromaDB Document Index

### Master Critique

| Document ID | Type | Description |
|-------------|------|-------------|
| `critique::master::delos-codebase-review-2025-12-30` | critique | Comprehensive codebase review against academic papers |

**Metadata**:
- `critical_bugs`: 3
- `security_concerns`: 3
- `modules_analyzed`: 10
- `scope`: master

### Cross-Reference Documents

| Document ID | Module | Description |
|-------------|--------|-------------|
| `crossref::fireflies::implementation` | fireflies | Fireflies paper-to-code mapping |
| `crossref::ethereal::implementation` | ethereal | Ethereal/Aleph-BFT mapping |
| `crossref::choam::implementation` | choam | CHOAM/lightweight-SMR mapping |
| `crossref::delphinius::implementation` | delphinius | Delphinius/Zanzibar mapping |
| `crossref::sql-state::implementation` | sql-state | SQL state machine mapping |
| `crossref::cryptography::implementation` | cryptography | Cryptographic primitives mapping |
| `crossref::memberships::implementation` | memberships | Membership model mapping |
| `crossref::stereotomy-thoth::implementation` | stereotomy, thoth | KERI implementation mapping |
| `crossref::model::implementation` | model | Process domain mapping |
| `crossref::master::delos-paper-codebase` | all | Master cross-reference index |

### Specialized Critiques

| Document ID | Focus | Description |
|-------------|-------|-------------|
| `critique::delphinius::zanzibar-comparison` | delphinius | Detailed Zanzibar comparison |
| `critique::architecture::delos-comprehensive-2025-12-31` | all | Comprehensive architectural critique |

### Remediation Plans

| Document ID | Phase | Description |
|-------------|-------|-------------|
| `plan::remediation::delos-phase2-2025-12-31` | 2 | Phase 2 remediation plan (35 tasks) |

---

## Mixedbread Store Reference

### Store: "delos"

**Statistics**:
- Papers: 18
- Total tokens: ~774K
- Coverage: All foundational research

**Key Papers** (search queries):

| Topic | Query |
|-------|-------|
| Fireflies BFT | `"Fireflies byzantine fault tolerant membership"` |
| Rapid membership | `"Rapid scalable membership"` |
| Aleph BFT | `"Aleph BFT asynchronous consensus"` |
| Lightweight SMR | `"lightweight SMR state machine replication"` |
| Zanzibar | `"Zanzibar Google authorization"` |
| KERI | `"KERI key event receipt infrastructure"` |
| HexBloom | `"HexBloom bloom filter"` |
| BFT consensus | `"Byzantine fault tolerant consensus"` |

**Usage**:
```
mcp__mixedbread__store_search({
    query: "<search query>",
    store_identifiers: ["delos"],
    top_k: 5
})
```

---

## Key Source Files by Module

### Ethereal

| File | Purpose | Critical? |
|------|---------|-----------|
| `ethereal/src/main/java/com/hellblazer/delos/ethereal/linear/UnanimousVoter.java` | Consensus voting | FIXED (P1 - Delos-868.1) |
| `ethereal/src/main/java/com/hellblazer/delos/ethereal/Adder.java` | Unit addition | **CRITICAL** - validate() stubs at L772-780 |
| `ethereal/src/main/java/com/hellblazer/delos/ethereal/Dag.java` | DAG structure | No |
| `ethereal/src/main/java/com/hellblazer/delos/ethereal/Creator.java` | Unit creation | No |

### Delphinius

| File | Purpose | Critical? |
|------|---------|-----------|
| `delphinius/src/main/java/com/hellblazer/delos/delphinius/DirectOracle.java` | Direct authorization | YES - Bug at L101 |
| `delphinius/src/main/java/com/hellblazer/delos/delphinius/AbstractOracle.java` | Base oracle | YES - Bug at L976 |
| `delphinius/src/main/java/com/hellblazer/delos/delphinius/Oracle.java` | Oracle interface | Watch API missing |

### Cryptography

| File | Purpose | Critical? |
|------|---------|-----------|
| `cryptography/src/main/java/com/hellblazer/delos/bloomFilters/Hash.java` | Bloom hash functions | Security concern |
| `cryptography/src/main/java/com/hellblazer/delos/cryptography/HexBloom.java` | HexBloom implementation | Security concern |
| `cryptography/src/main/java/com/hellblazer/delos/cryptography/DigestAlgorithm.java` | Digest algorithms | Audit needed |
| `cryptography/src/main/java/com/hellblazer/delos/cryptography/SignatureAlgorithm.java` | Ed25519/X25519 | Documentation needed |
| `cryptography/src/main/java/com/hellblazer/delos/bloomFilters/BloomWindow.java` | Bloom window | Thread safety |

### SQL-State

| File | Purpose | Critical? |
|------|---------|-----------|
| `sql-state/src/main/java/com/hellblazer/delos/sql/BlockDate.java` | Block-based dates | TODO incomplete |
| `h2-deterministic/src/main/java/org/h2/util/BlockClock.java` | Deterministic clock | Thread safety |

### Fireflies

| File | Purpose | Critical? |
|------|---------|-----------|
| `fireflies/src/main/java/com/hellblazer/delos/fireflies/View.java` | Membership view | 2/3 vs 3/4 threshold |
| `fireflies/src/main/java/com/hellblazer/delos/fireflies/Ring.java` | Ring structure | No |

### CHOAM

| File | Purpose | Critical? |
|------|---------|-----------|
| `choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java` | Main consensus (1738 lines) | **CRITICAL** - Race conditions at L543-602, L754-793, L604-627; Unbounded queue at L91 |
| `choam/src/main/java/com/hellblazer/delos/choam/Bootstrapper.java` | Recovery | Documentation needed |
| `choam/src/main/java/com/hellblazer/delos/choam/Synchronizer.java` | State sync | Documentation needed |

### Model

| File | Purpose | Critical? |
|------|---------|-----------|
| `model/src/main/java/com/hellblazer/delos/model/demesnes/JniBridge.java` | GraalVM isolate bridge | **CRITICAL** - stop() calls start() at L133 |
| `model/src/main/java/com/hellblazer/delos/model/Domain.java` | Process domain | Needs Builder pattern |

### Stereotomy

| File | Purpose | Critical? |
|------|---------|-----------|
| `stereotomy/src/main/java/com/hellblazer/delos/stereotomy/KERL.java` | KERI event log | Witness threshold |

---

## Test File Locations

### By Module

| Module | Test Location | Key Tests |
|--------|---------------|-----------|
| ethereal | `ethereal/src/test/java/com/hellblazer/delos/ethereal/` | Voter tests |
| delphinius | `delphinius/src/test/java/com/hellblazer/delos/delphinius/` | Oracle tests |
| cryptography | `cryptography/src/test/java/com/hellblazer/delos/cryptography/` | Digest/Signature tests |
| fireflies | `fireflies/src/test/java/com/hellblazer/delos/fireflies/` | View tests |
| choam | `choam/src/test/java/com/hellblazer/delos/choam/` | Consensus tests |
| sql-state | `sql-state/src/test/java/com/hellblazer/delos/sql/` | SQL state tests |

### Test Count Summary

| Module | Test Classes | Notes |
|--------|--------------|-------|
| cryptography | TBD | Many digest/signature tests |
| ethereal | TBD | Consensus tests |
| fireflies | TBD | Membership tests |
| delphinius | TBD | Authorization tests |
| Total | ~98 | From critique analysis |

---

## Build Artifacts

### Generated Sources

| Module | Type | Location |
|--------|------|----------|
| grpc | Protobuf/GRPC | `grpc/target/generated-sources/` |
| sql-state | JOOQ | `sql-state/target/generated-sources/` |

### Special Build Requirements

| Module | Requirement | Command |
|--------|-------------|---------|
| h2-deterministic | Pre-build | `./mvnw clean install -Ppre -DskipTests` |
| isolates | GraalVM | `./mvnw clean install -Pisolates` |

---

## External Dependencies

### Critical Libraries

| Library | Version | Usage |
|---------|---------|-------|
| gRPC | 1.68.0 | Service communication |
| Protobuf | 4.28.2 | Message serialization |
| H2 | custom | Deterministic SQL |
| Netty | 4.1.x | Networking |
| Bouncy Castle | latest | Cryptography |
| JOOQ | latest | SQL query building |
| Liquibase | custom | Schema migration |

---

## Memory Bank Structure

### Project: Delos_active

| File | Purpose |
|------|---------|
| `current_phase.md` | Active phase state |
| `hypotheses.md` | Active hypotheses |
| `findings.md` | Session findings |
| `blockers.md` | Current blockers |

### Usage
```
mcp__allPepper-memory-bank__memory_bank_read({
    projectName: "Delos_active",
    fileName: "current_phase.md"
})
```

---

## Search Strategies

### Finding Bug Context

1. Search ChromaDB for module cross-reference:
   ```
   mcp__chromadb__search_similar(query="<module> implementation bug", num_results=5)
   ```

2. Search mixedbread for paper algorithm:
   ```
   mcp__mixedbread__store_search(query="<algorithm> specification", store_identifiers=["delos"])
   ```

3. Grep codebase for related code:
   ```
   Grep(pattern="<class or method>", path="<module>/src/main/java")
   ```

### Finding Paper References

1. Search mixedbread by topic:
   ```
   mcp__mixedbread__store_search(query="<topic> algorithm", store_identifiers=["delos"])
   ```

2. Check cross-reference for implementation:
   ```
   mcp__chromadb__read_document(document_id="crossref::<module>::implementation")
   ```

### Finding Related Tests

1. Glob for test files:
   ```
   Glob(pattern="**/*Test.java", path="<module>/src/test")
   ```

2. Grep for test methods:
   ```
   Grep(pattern="@Test.*<feature>", path="<module>/src/test")
   ```

---

## Quick Reference

### ChromaDB Prefixes

| Prefix | Usage |
|--------|-------|
| `critique::` | Code review critiques |
| `crossref::` | Paper-to-code mappings |
| `decision::` | Architectural decisions |
| `fix::` | Bug fix documentation |
| `pattern::` | Reusable patterns |

### File Path Patterns

```
# Source
<module>/src/main/java/com/hellblazer/delos/<module>/

# Tests
<module>/src/test/java/com/hellblazer/delos/<module>/

# Resources
<module>/src/main/resources/
<module>/src/test/resources/
```

---

*Last Updated: 2025-12-30*
