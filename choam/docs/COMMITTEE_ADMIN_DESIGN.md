# Committee Administration Extraction - Design Decision

**Bead**: Delos-mdrh
**Date**: 2026-02-06
**Status**: PROPOSED

---

## Executive Summary

**Decision**: **Option A - Single file with inner classes**

Extract Administration hierarchy as `CommitteeAdministration.java` (259 lines) containing:
- `Administration` (abstract base class, ~210 lines)
- `Associate` (inner class, ~43 lines)
- `Client` (inner class, ~6 lines)

**Rationale**: High cohesion, preserves inheritance hierarchy, matches existing extraction pattern (GenesisFormation, CombinerFSM), avoids boilerplate for minimal Client class.

---

## Current State Analysis

### Administration Hierarchy

Located in `CHOAM.java` lines 1478-1740 (259 lines total):

```java
private abstract class Administration implements Committee {
    protected final Digest viewId;
    private final GroupIterator servers;
    private final Map<Member, Verifier> validators;

    // Committee interface methods (11 methods)
    // View joining logic (~108 lines)
    // Transaction submission logic
    // Block validation
}

private class Associate extends Administration {
    private final Producer producer;

    // Consensus block production
    // Join message handling
    // Transaction submission to producer
}

private class Client extends Administration {
    // Marker class - no additional fields or methods
}
```

### Responsibilities

**Administration** (abstract):
- Committee membership validation (`isMember()`, `validate()`)
- Transaction submission to committee servers (`submitTxn()`)
- View joining protocol (complex, ~108 lines, async coordination)
- Block acceptance and validation

**Associate** (concrete):
- All Administration responsibilities
- Consensus block production via `Producer`
- Handles join messages from other members
- Direct transaction submission to local Producer

**Client** (concrete):
- All Administration responsibilities
- Passive observer (no block production)
- Submits transactions to remote committee servers

### Dependencies

**External classes**:
- `Producer` (consensus block production)
- `ViewContext` (view-specific consensus context)
- `Committee` interface (11 methods)

**CHOAM state accessed**:
- `controlState` (ControlStateHolder)
- `viewStateHolder` (ViewStateHolder)
- `blockChainState` (BlockChainStateHolder)
- `comm`, `submissionComm` (communication)

**Usage**:
- Created at CHOAM.java:984 (Associate), :993 (Client)
- Stored in `committeeState` (CommitteeStateHolder)

---

## Class Diagram

```
┌─────────────────────────────────────────────┐
│          Committee (interface)              │
│  - accept(HashedCertifiedBlock)             │
│  - validate(HashedCertifiedBlock)           │
│  - submitTxn(Transaction)                   │
│  - join(SignedViewMember, Digest)           │
│  - nextView(Digest, Context)                │
│  + 6 more methods                           │
└─────────────────────────────────────────────┘
                      ▲
                      │ implements
                      │
┌─────────────────────────────────────────────┐
│   Administration (abstract)                 │
│  - viewId: Digest                           │
│  - servers: GroupIterator                   │
│  - validators: Map<Member, Verifier>        │
│  + accept(block)                            │
│  + validate(block)                          │
│  + submitTxn(transaction)                   │
│  + assemble(Assemble)                       │
│  # join(View) : void         [108 lines]    │
│  + nextView(Digest, Context)                │
└─────────────────────────────────────────────┘
          ▲                           ▲
          │                           │
    ┌─────┴──────┐            ┌───────┴──────┐
    │            │            │              │
┌───────────┐  ┌───────────┐
│ Associate │  │  Client   │
│  + producer│  │           │
│  + join()  │  │  (marker) │
│  + submit()│  │           │
└───────────┘  └───────────┘
  ~43 lines      ~6 lines
```

---

## Option Analysis

### Option A: Single File with Inner Classes ⭐ **RECOMMENDED**

**Structure**:
```
choam/src/main/java/com/hellblazer/delos/choam/support/
└── CommitteeAdministration.java (259 lines)
    ├── Administration (abstract, ~210 lines)
    ├── Associate (inner class, ~43 lines)
    └── Client (inner class, ~6 lines)
```

**Pros**:
1. ✅ **High cohesion**: All committee administration logic in one place
2. ✅ **Preserves hierarchy**: Inheritance relationships explicit and natural
3. ✅ **Matches extraction pattern**: GenesisFormation (134 lines), CombinerFSM (170 lines) are single files
4. ✅ **Avoids boilerplate**: Client class (6 lines) too small for dedicated file
5. ✅ **Clean encapsulation**: Private inner classes hidden from external access
6. ✅ **Easier navigation**: One file to understand committee administration
7. ✅ **Simpler refactoring**: Changes to base class and subclasses in same file

**Cons**:
1. ❌ Large file (259 lines) - but cohesive and well-structured
2. ❌ Some prefer granular files - but hierarchy justifies consolidation

**Complexity**: 259 lines total (comparable to existing extractions)

**Alignment with StateHolder pattern**:
- ✅ Separated from state (CommitteeStateHolder manages committee reference)
- ✅ Focused on behavior (Committee implementation)
- ✅ Follows GenesisFormation precedent (also implements Committee, 134 lines)

---

### Option B: Separate Class Files

**Structure**:
```
choam/src/main/java/com/hellblazer/delos/choam/support/
├── Administration.java (210 lines, abstract)
├── AssociateCommittee.java (45 lines)
└── ClientCommittee.java (8 lines)
```

**Pros**:
1. ✅ Smaller individual files (210, 45, 8 lines)
2. ✅ Clear file-per-class organization
3. ✅ Easier to find specific subclass implementation

**Cons**:
1. ❌ **Client.java boilerplate**: 6 lines of actual code, 2 lines boilerplate (import, class declaration)
2. ❌ **Breaks cohesion**: Administration logic split across 3 files
3. ❌ **Package visibility issues**: Administration must be public or package-private (exposes internals)
4. ❌ **Navigation overhead**: Must switch between 3 files to understand hierarchy
5. ❌ **More files**: Increases project file count without clear benefit
6. ❌ **Refactoring friction**: Changes to base class require checking 2 subclass files

**Complexity**: 3 files, 263 lines total (overhead from duplicate imports/headers)

**Alignment with StateHolder pattern**:
- ⚠️ Behavior extraction (not state), so separate from StateHolder
- ❌ Creates package-level exposure risk (Administration must be accessible to subclasses)

---

### Option C: Integrate into CommitteeStateHolder

**Structure**:
```
choam/src/main/java/com/hellblazer/delos/choam/support/
└── CommitteeStateHolder.java (extended with Administration)
```

**Pros**:
1. ✅ Reuses existing StateHolder from Phase 3
2. ✅ Aligns with StateHolder pattern (consolidation)

**Cons**:
1. ❌ **Low cohesion**: Mixing STATE management (AtomicReference<Committee>) with BEHAVIOR (Committee implementation)
2. ❌ **Violates Single Responsibility**: StateHolder should manage state, not implement Committee operations
3. ❌ **Confusion**: CommitteeStateHolder is for storing committee reference, not implementing committee logic
4. ❌ **Bloat**: Would make CommitteeStateHolder ~400 lines (130 current + 259 Administration)
5. ❌ **Wrong abstraction**: Administration is not state, it's a Committee implementation
6. ❌ **Precedent violation**: GenesisFormation (also Committee implementation) is separate file, not in StateHolder

**Complexity**: 400+ line file with mixed responsibilities

**Alignment with StateHolder pattern**:
- ❌ **Violates pattern**: StateHolders manage state (AtomicReference, AtomicBoolean), not behavior
- ❌ **Confusion**: StateHolder would both STORE committee and IMPLEMENT committee
- ❌ **Precedent**: Other Committee implementations (GenesisFormation) are separate

---

## Decision Matrix

| Criterion | Option A (Single File) | Option B (Separate Files) | Option C (StateHolder) |
|-----------|------------------------|---------------------------|------------------------|
| **Cohesion** | ✅ High (all admin together) | ❌ Low (split across 3 files) | ❌ Very Low (mixed state/behavior) |
| **Matches Pattern** | ✅ Yes (GenesisFormation, CombinerFSM) | ⚠️ Partial | ❌ No (violates StateHolder pattern) |
| **LOC Impact** | ✅ 1 file, 259 lines | ❌ 3 files, 263 lines + overhead | ❌ 1 file, 400+ lines (bloat) |
| **Maintainability** | ✅ Simplest (1 file) | ⚠️ Moderate (3 files) | ❌ Complex (mixed concerns) |
| **Client Boilerplate** | ✅ Avoids (inner class) | ❌ 8-line file (mostly boilerplate) | ⚠️ Avoids but wrong abstraction |
| **Navigation** | ✅ Easiest (1 file) | ❌ Harder (3 files) | ⚠️ Confusing (mixed responsibilities) |
| **Encapsulation** | ✅ Private inner classes | ⚠️ Package-private required | ❌ Exposes implementation details |
| **Single Responsibility** | ✅ Yes (committee admin) | ✅ Yes (per class) | ❌ No (state + behavior) |

**Winner**: **Option A** (5 ✅ vs 2 ✅ vs 1 ✅)

---

## Recommendation

### Selected Option: **Option A - Single File with Inner Classes**

**File**: `choam/src/main/java/com/hellblazer/delos/choam/support/CommitteeAdministration.java`

**Structure**:
```java
public class CommitteeAdministration {

    public abstract static class Administration implements Committee {
        protected final Digest viewId;
        private final GroupIterator servers;
        private final Map<Member, Verifier> validators;

        // 210 lines of committee administration logic
    }

    public static class Associate extends Administration {
        private final Producer producer;

        // 43 lines of consensus production logic
    }

    public static class Client extends Administration {
        // 6 lines (marker class)
    }
}
```

**Rationale**:

1. **High Cohesion**: Committee administration logic naturally belongs together. Associate and Client are VARIANTS of Administration, not independent components.

2. **Follows Extraction Pattern**:
   - GenesisFormation: 134 lines, single file, implements Committee
   - CombinerFSM: 170 lines, single file, implements Combine
   - CommitteeAdministration: 259 lines, single file, implements Committee (via Administration)

3. **Avoids Unnecessary Files**: Client class is 6 lines - creating a dedicated file adds more boilerplate than value.

4. **Preserves Hierarchy**: Inheritance relationships are clear and explicit within single file.

5. **Behavior, Not State**: Administration IMPLEMENTS Committee operations (behavior), distinct from CommitteeStateHolder which MANAGES committee reference (state).

6. **Encapsulation**: Inner classes can remain private if needed, or public static for external instantiation.

---

## Implementation Approach

### Step 1: Create CommitteeAdministration.java

Extract from CHOAM.java lines 1478-1740:
- Copy Administration class (abstract base)
- Copy Associate class (with Producer dependency)
- Copy Client class (marker)
- Add package declaration, imports
- Make inner classes `public static` for external instantiation

### Step 2: Update CHOAM.java

Replace inner class definitions with:
```java
import com.hellblazer.delos.choam.support.CommitteeAdministration.Administration;
import com.hellblazer.delos.choam.support.CommitteeAdministration.Associate;
import com.hellblazer.delos.choam.support.CommitteeAdministration.Client;

// Usage at lines 984, 993:
newCommittee = new Associate(h, validators, (nextView) currentView);
newCommittee = new Client(validators, getViewId());
```

### Step 3: Adjust Access Modifiers

**Administration**:
- Make `public abstract static class` (for subclass inheritance)
- Keep fields `protected` or `private` as appropriate

**Associate/Client**:
- Make `public static class` (for external instantiation)

### Step 4: Handle CHOAM Dependencies

Administration accesses CHOAM state via:
- `params()` - passed via Committee interface
- `controlState`, `viewStateHolder`, `blockChainState` - **need CHOAM reference or dependency injection**

**Options**:
- **A) Pass CHOAM reference to Administration constructor** (simplest)
- **B) Dependency injection** (more complex, better encapsulation)

**Recommendation**: Pass necessary state holders (controlState, viewStateHolder, etc.) as constructor parameters.

### Step 5: Update Tests

Check if Administration classes are tested:
- Search for tests of Associate/Client/Administration
- Update test imports
- Verify tests still pass

### Step 6: Update Documentation

- Update ARCHITECTURE.md with CommitteeAdministration extraction
- Document in LOCK_ORDERING.md if any lock usage (verify first)
- Add javadoc describing hierarchy and responsibilities

---

## Phase 3 Mapping

**Committee Management Architecture**:

```
┌──────────────────────────────────────────────┐
│         CommitteeStateHolder                 │
│  Manages: AtomicReference<Committee>         │
│  Responsibility: STATE (which committee)     │
└──────────────────────────────────────────────┘
                    │
                    │ stores reference to
                    ▼
┌──────────────────────────────────────────────┐
│         Committee (interface)                │
│  Implementations:                            │
│  - GenesisFormation (genesis bootstrapping)  │
│  - Administration (active committee)         │
│    ├── Associate (producer)                  │
│    └── Client (observer)                     │
│  - Synchronizer (sync protocol)              │
└──────────────────────────────────────────────┘
```

**Separation of Concerns**:
- **CommitteeStateHolder**: Manages which committee is active (state)
- **CommitteeAdministration**: Implements committee operations (behavior)
- **GenesisFormation**: Implements genesis-specific operations (behavior)
- **Synchronizer**: Implements synchronization operations (behavior)

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| CHOAM dependency coupling | Medium | Pass state holders as constructor params, not full CHOAM reference |
| Test breakage | Low | Update imports, verify all tests pass |
| Package visibility issues | Low | Use `public static` inner classes, verify instantiation works |
| Large file concerns | Low | 259 lines is acceptable for cohesive hierarchy (matches CombinerFSM, GenesisFormation) |

---

## Acceptance Criteria

- [x] All 3 options analyzed with pros/cons
- [x] Decision documented with rationale (Option A selected)
- [x] Class diagram included (inheritance relationships)
- [x] Phase 3 mapping clarified (StateHolder vs Behavior separation)
- [x] Implementation approach specified (6 steps)

---

## References

- **Extraction precedents**:
  - GenesisFormation.java (134 lines, single file, implements Committee)
  - CombinerFSM.java (170 lines, single file, implements Combine)
- **StateHolder pattern**: Phase 1-5 extractions (state management only)
- **Committee interface**: choam/src/main/java/com/hellblazer/delos/choam/Committee.java
- **Current implementation**: CHOAM.java lines 1478-1740

---

**Decision Made By**: Design spike (Delos-mdrh)
**Date**: 2026-02-06
**Status**: PROPOSED (pending approval)
**Next Steps**: Review decision, approve, create implementation bead (Delos-ikve)
