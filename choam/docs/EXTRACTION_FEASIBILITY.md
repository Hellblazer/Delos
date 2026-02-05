# CHOAM Inner Class Extraction Feasibility Report

**Date**: 2026-02-05
**Branch**: spike/extraction-feasibility
**Purpose**: Validate extraction mechanics before committing to Phase 2 plan

---

## Spike A: Formation Extraction (102 lines)

**Target**: Extract Formation inner class (lines 1834-1935) to GenesisFormation.java

### Attempt Summary

Created `choam/support/GenesisFormation.java` and attempted compilation.

### Compilation Errors (17 total)

**Category 1: Package/Import Issues** (6 errors):
- GenesisAssembly not found in com.hellblazer.delos.ethereal
- ViewContext not found in com.hellblazer.delos.ethereal
- ControlledIdentifierMember not found in com.hellblazer.delos.stereotomy
- Signer not found in com.hellblazer.delos.stereotomy
- HashedCertifiedBlock package incorrect (com.salesforce.apollo.ethereal.proto)
- PendingViews type not accessible

**Category 2: Visibility Issues** (2 errors):
- CHOAM.nextView is private (not accessible outside package)
- CHOAM.GenesisContext is private (not accessible outside package)

**Category 3: Missing Methods** (3 errors):
- acceptGenesisBlock() does not exist on CHOAM
- formationNextView() does not exist on CHOAM
- validateRegeneration() does not exist on CHOAM

### Root Cause Analysis

**Formation extraction is BLOCKED by tight coupling**:

1. **Private inner class dependencies**: Formation depends on private CHOAM types (nextView, GenesisContext) that cannot be accessed from extracted class
2. **Deep integration**: Formation calls CHOAM instance methods that would need to be exposed or refactored
3. **Import complexities**: Several dependencies have incorrect package paths or are not directly importable

### Feasibility Assessment: **CONDITIONAL**

Formation extraction is **feasible BUT requires significant refactoring**:

**Option A: Make private types public** (2-3 hours effort)
- Make nextView, GenesisContext public or package-private
- Add delegation methods to CHOAM (acceptGenesisBlock, formationNextView, validateRegeneration)
- Fix import paths for dependency classes
- **Risk**: Exposes internal types, may violate encapsulation

**Option B: Redesign extraction** (1-2 days effort)
- Keep Formation as inner class
- Extract only business logic methods
- **Risk**: Lower LOC reduction (estimated 40-50 lines instead of 102)

**Option C: Skip Formation, target simpler classes first** (0 effort)
- Extract Trampoline (29 lines - simple GRPC delegation)
- Extract Combiner (132 lines - FSM, may have similar issues)
- Return to Formation after validating extraction pattern
- **Risk**: May not meet LOC target without Formation

### Recommendation for Formation

**PROCEED with Option A** if Administration extraction succeeds (validates pattern).
**DEFER to Option C** if Administration extraction also blocked.

---

## Spike B: Administration Hierarchy Extraction (263 lines)

**Target**: Extract Administration abstract class (210 lines) + Associate (45 lines) + Client (8 lines)

**Status**: COMPLETED - BLOCKED

### Attempt Summary

Created `choam/support/CommitteeAdministration.java` with hierarchy:
- CommitteeAdministration (abstract base)
- CommitteeAdministration.Associate (inner class)
- CommitteeAdministration.Client (inner class)

Attempted compilation revealed **SEVERE coupling** - extraction is BLOCKED.

### Compilation Errors (162 total)

**Category 1: Missing Accessor Methods** (102 errors):
- `params()`: 68 errors - frequently accessed throughout
- `viewStateHolder()`: 22 errors - state management integration
- `controlState()`: 12 errors - lifecycle management integration

**Category 2: Missing Delegation Methods** (8 errors):
- `submissionComm()`: 2 errors - transaction submission
- `getNextView()`: 2 errors - accesses private `nextView` type
- `comm()`: 2 errors - terminal communication
- `validate(HashedCertifiedBlock, Map<>)`: 2 errors - validation logic

**Category 3: Import/Package Issues** (16 errors):
- `Producer`: 4 errors (ethereal package)
- `Signer`: 4 errors (stereotomy package)
- `SignerImpl`: 4 errors (stereotomy package)
- `ViewContext`: 2 errors (ethereal package)
- `ImmediateExecutor`: 2 errors (protocols package)

**Category 4: Type Access Issues** (22 errors):
- `Terminal`: 2 errors - private inner class, cannot be referenced outside CHOAM
- `nextView`: implicit - private inner class, cannot be used as parameter type
- `Result`: 10 errors - proto enum (import issue)
- `Empty`: 2 errors - proto class (import issue)
- `Attempt` record fields: 12 errors (m, fs) - cascade from other issues

**Category 5: Private Method Access** (1 error):
- `pendingViews()`: private method, cannot be called from extracted class

**Category 6: Additional Issues Discovered**:
- Administration.join() methods create virtual threads (`Executors.newSingleThreadScheduledExecutor`)
- Join protocol requires `checkpointManager()`, `constructBlock()`, `getLabel()` - 3 more missing methods
- Associate constructor requires `nextView` type as parameter - private inner class blocker
- Complex state management across multiple CHOAM components

### Root Cause Analysis

**Administration extraction is SEVERELY BLOCKED by deep integration**:

1. **Massive accessor requirement**: 102 errors from just 3 missing accessors (params, viewStateHolder, controlState)
2. **Private type dependencies**: Administration hierarchy depends on `nextView` and `Terminal` - both private inner classes
3. **Distributed state management**: Administration coordinates across 6+ CHOAM subsystems (viewState, control, submission, blockchain, checkpoints, pending views)
4. **Lifecycle integration**: Join protocol tightly coupled to CHOAM lifecycle (beginJoin, endJoin, isJoinOngoing)

### Feasibility Assessment: **BLOCKED**

Administration extraction is **NOT feasible in current form**. The coupling is 9.5x worse than Formation (162 errors vs 17 errors).

**Comparison to Formation**:
- Formation: 17 errors (conditional feasibility)
- Administration: 162 errors (severe blocking)

**Why Administration is Harder**:
1. **State management complexity**: Formation is relatively standalone; Administration coordinates across entire CHOAM lifecycle
2. **Type coupling**: Formation uses 2 private types; Administration uses 2 private types PLUS requires them as constructor parameters
3. **Method count**: Formation has 6 delegation methods; Administration needs 11+ delegation methods
4. **Integration depth**: Formation is a specialized component; Administration is a core integration point

### Options for Administration

**Option A: Make Everything Public** (1-2 weeks effort):
- Make nextView, Terminal public or package-private
- Add 11+ delegation methods to CHOAM
- Fix all import paths
- Refactor Associate constructor to avoid nextView parameter type
- **Risk**: MASSIVE API surface exposure, violates encapsulation severely
- **Likelihood of success**: Medium (structural refactoring required)

**Option B: Extract Only Business Logic** (3-5 days effort):
- Keep Administration as inner class
- Extract helper methods to utility classes
- **Risk**: Lower LOC reduction (estimated 50-80 lines instead of 263)
- **Impact**: Would NOT meet M2.4 milestone (need 487 lines, would get ~200 lines total with other extractions)

**Option C: Redesign CHOAM State Management** (3-4 weeks effort):
- Refactor CHOAM to use state holder pattern more comprehensively
- Replace private inner classes with public state holders
- Decouple Administration from direct CHOAM field access
- **Risk**: Requires Phase 0-style architectural refactoring
- **Impact**: Would delay Phase 2 by 3-4 weeks but enable clean extraction

**Option D: Abandon Administration Extraction** (0 effort):
- Extract other inner classes (Trampoline, Combiner, Formation, Synchronizer)
- Total available: 571 - 263 = 308 lines
- **Impact**: Would NOT meet M2.4 milestone (need 487 lines, only 308 available)
- **Consequence**: Phase 2 cannot complete without Administration OR requires finding 179 additional lines elsewhere

### Recommendation for Administration

**STOP** extraction attempts for Administration. The coupling is too severe.

**Recommended path forward**:
1. **Complete Formation extraction** (Option A from Spike A - 2-3 hours)
2. **Extract Trampoline** (29 lines - should be simple GRPC delegation)
3. **Extract Combiner** (132 lines - FSM, may have similar issues)
4. **Extract Synchronizer** (45 lines - similar to Formation)
5. **Reassess** after 4 extractions: 102 + 29 + 132 + 45 = 308 lines
6. **If milestone still not met**, escalate to user with options:
   - Accept partial completion (308 lines = 63% of target)
   - Pursue Option C (comprehensive refactoring, 3-4 weeks)
   - Find alternative extraction targets (methods, not just inner classes)

---

## Overall Assessment

**Spike Status**: ✅ COMPLETE (both Formation and Administration attempted)

### Key Findings

| Extraction Target | LOC | Compilation Errors | Feasibility | Estimated Effort |
|-------------------|-----|-------------------|-------------|------------------|
| Formation | 102 | 17 | **CONDITIONAL** | 2-3 hours (Option A) |
| Administration | 263 | 162 | **BLOCKED** | 1-2 weeks (Option A) or 3-4 weeks (Option C) |

### Critical Discovery

**The planned Phase 2 extraction strategy has a FUNDAMENTAL FLAW**:

Administration (263 lines, 54% of LOC target) is **NOT extractable** without massive refactoring due to:
1. 162 compilation errors vs Formation's 17 errors (9.5x worse coupling)
2. Deep integration with CHOAM state management (6+ subsystems)
3. Private inner class type dependencies (nextView, Terminal) used as constructor parameters
4. Requires 11+ new delegation methods on CHOAM

**Impact on M2.4 Milestone**:
- Target: Reduce CHOAM.java by 487 lines to reach <1500 LOC
- Available without Administration: 571 - 263 = 308 lines (63% of target)
- **Shortfall**: 179 lines (37% of target)

### Recommended Actions

**IMMEDIATE** (before continuing Phase 2):

1. **Report findings to user** - Phase 2 plan requires revision
2. **Provide options**:
   - **Option 1**: Accept partial completion (308 lines = 1679 final LOC, slightly over target)
   - **Option 2**: Pursue comprehensive refactoring (3-4 weeks, high risk)
   - **Option 3**: Find alternative extraction targets (methods, not just inner classes)

**DO NOT PROCEED** with current Phase 2 plan without user decision on Administration.

### Lessons Learned

**For Future Extractions**:
1. **Feasibility spikes are ESSENTIAL** - they prevented wasted effort on Administration
2. **Line count ≠ extractability** - Administration is 2.6x Formation but 9.5x harder
3. **Private inner classes with lifecycle integration are extraction blockers**
4. **Accessor method count is a coupling indicator** - 68 calls to `params()` alone!

### Next Steps

1. ✅ Attempt Formation extraction - **COMPLETED** (conditional feasibility)
2. ✅ Attempt Administration hierarchy extraction - **COMPLETED** (blocked)
3. ✅ Document findings in EXTRACTION_FEASIBILITY.md
4. ✅ Provide GO/NO-GO recommendations
5. ⏳ **Report to user** - await decision on Administration
6. ⏳ Clean up spike branch (rollback extractions)
7. ⏳ Update Phase 2 plan based on user decision

---

**Spike Status**: ✅ COMPLETE - Ready for user review and decision
