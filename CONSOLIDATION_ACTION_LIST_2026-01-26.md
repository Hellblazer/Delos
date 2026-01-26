# Delos Consolidation Action List
**Date**: 2026-01-26
**Status**: Ready for Execution
**Purpose**: Detailed checklist of consolidation candidates and cleanup actions

---

## Part 1: Files to Consolidate into ChromaDB

### Root Directory Consolidation

**ETHEREAL_TEST_CONSOLIDATION_SUMMARY.md** (315 lines, 12KB)
- Collection Target: `delos::consensus-architecture`
- Key Content: Ethereal test reliability, Byzantine patterns, CI timeout strategy, race condition fix
- Line Range: 1-315
- Related Beads: Delos-3XXX series
- Action: Extract and consolidate → Store in ChromaDB → KEEP (referenced frequently)

**FUTURE_WORK_BACKLOG.md** (134 lines, 6.5KB)
- Collection Target: `delos::operational-roadmap`
- Key Content: 50 work items with dependencies, execution strategy, priorities
- Line Range: 1-134
- Related Beads: Multiple (dependency tracking)
- Action: Extract and consolidate → Store in ChromaDB → CAN BE REMOVED (backlog managed in beads)

---

### .pm/audits/ Consolidation (11 audit files)

**1. PHASE_1C_AUDIT_REPORT.md** (~220 lines, 9KB)
- Collection Target: `delos::operational-decisions`
- Content: Phase 1C plan audit (82/100 score), critical compilation blocker, timeline impact
- Related Beads: Delos-3925
- Action: Consolidate → Archive

**2. DELOS_3917_PLAN_AUDIT.md** (~150 lines, 8KB)
- Collection Target: `delos::operational-decisions`
- Content: Recursive epoch aggregation audit findings
- Related Beads: Delos-3917
- Action: Consolidate → Archive

**3. DELOS_3921_AUDIT_REPORT.md** (~250 lines, 11KB)
- Collection Target: `delos::operational-decisions`
- Content: Byzantine detection framework audit, design review findings
- Related Beads: Delos-3921
- Action: Consolidate → Archive

**4. DELOS_3934_PLAN_AUDIT.md** (~200 lines, 10KB)
- Collection Target: `delos::operational-decisions`
- Content: Key rotation mechanism design audit
- Related Beads: Delos-3934
- Action: Consolidate → Archive

**5. DELOS_4000_PLAN_AUDIT.md** (~180 lines, 8KB)
- Collection Target: `delos::consensus-architecture`
- Content: Epoch transition validator plan audit
- Related Beads: Delos-4000
- Action: Consolidate → Archive

**6. MULTI_COMMITTEE_AGGREGATE_DESIGN_AUDIT.md** (~180 lines, 8.6KB)
- Collection Target: `delos::operational-decisions`
- Content: Multi-committee aggregation design audit
- Related Beads: Delos-3930
- Action: Consolidate → Archive

**7. h2-function-audit.md** (large file, 15KB)
- Collection Target: `delos::system-architecture`
- Content: H2 function determinism audit, comprehensive function analysis
- Related Beads: Delos-3886 and related H2 work
- Action: Consolidate → Archive

**8. phase1a0-round2-audit-2026-01-18.md** (very large, 35KB)
- Collection Target: `delos::operational-decisions`
- Content: Comprehensive Phase 1A round 2 audit, multi-part findings
- Related Beads: Phase 1A related beads
- Action: Consolidate → Archive

**9. DELOS-3937_AUDIT_REPORT.md** (~200 lines, 9.3KB)
- Collection Target: `delos::operational-decisions`
- Content: Byzantine detection framework audit, detailed findings
- Related Beads: Delos-3937
- Action: Consolidate → Archive

**10-11. Additional audit files** (2 remaining)
- Action: Consolidate → Archive

**Total Audits**: 11 files, ~95KB → Consolidate all to `delos::operational-decisions`

---

### .pm/designs/ Consolidation (18+ design files)

**Cryptography/Signatures Design Files** → `delos::cryptography-signatures`:
1. `PHASE_1B_BLS_ARCHITECTURE.md` - BLS architecture decisions
2. `CROWN_AGGREGATION_APPROACHES.md` - Aggregation approach comparison
3. `IMPLEMENTATION_ROADMAP.md` - Implementation strategy
4. `MULTI_COMMITTEE_AGGREGATE_DESIGN.md` - Multi-committee design
5. `BLS_KEY_ROTATION_DESIGN.md` - Key rotation architecture
6. `AGGREGATE_SIGNATURE_PHASES.md` - Signature aggregation phases
7. `PHASE_1A3_ARCHITECTURE.md` - Phase 1A3 architecture

**System/Network Design Files** → `delos::system-architecture`:
1. `FIREFLIES_KERI_MAPPING.md` - Fireflies/KERI integration
2. `WITNESS_NETWORK_ARCHITECTURE.md` - Witness network design
3. `KERI_REQUIREMENTS.md` - KERI requirements specification
4. `RECEIPT_PROTOCOL.md` - Receipt protocol specification
5. Additional phase-specific architecture files

**Total Design Files**: 18+ files → Consolidate to appropriate collections → Archive

---

### .pm/plans/ Consolidation (30+ plan files)

**Cryptography Plans** → `delos::cryptography-signatures`:
- `PHASE_1B_BLS_OPTIMIZATION_PLAN.md`
- `PHASE_1B_2_BLS_RECEIPT_AGGREGATION_PLAN.md`
- `PHASE_1B_2_IMPLEMENTATION_PLAN_v2.md`
- Plus additional BLS-related plans

**System/Architecture Plans** → `delos::system-architecture`:
- `FIREFLIES_KERI_WITNESS_NETWORK_PLAN.md`
- Plus network/system architecture plans

**Operations/Performance Plans** → `delos::operational-roadmap`:
- `PHASE_1C_BLS_OPTIMIZATION_PLAN.md`
- `PHASE_1C_3D_BLS_METRICS_PLAN.md`
- Plus roadmap/strategy plans

**Consensus/Testing Plans** → `delos::consensus-architecture`:
- `DELOS_4000_EPOCH_TRANSITION_VALIDATOR_PLAN.md`
- Plus consensus-related plans

**Decision/Audit Plans** → `delos::operational-decisions`:
- `DELOS-3937_BYZANTINE_DETECTOR_PLAN.md`
- `PHASE_1C_3D_BLS_METRICS_AUDIT.md`
- `DELOS_3926_DISPATCH_OVERHEAD_PLAN.md`
- `DELOS_3921_ENHANCED_BYZANTINE_DETECTION_PLAN.md`
- Plus additional audit/decision plans

**Total Plan Files**: 30+ files → Consolidate to appropriate collections → Archive

---

### .pm/ Root Level Files

**Phase Reconciliation Files** → `delos::operational-decisions`:
- `PHASE_1B_2_RECONCILIATION.md` (80 lines, 11KB) - Phase completion status
- `PHASE_1B3_COMPLETION.md` (75 lines, 9.8KB) - Phase completion summary
- `RECONCILIATION_2026-01-20.md` (80 lines, 6.6KB) - Session reconciliation
- `DEPENDENCY_GRAPH.md` (150+ lines) - Module dependency tracking
- `EXECUTIVE_SUMMARY.md` - Phase/project executive summary
- `QUICK_REFERENCE.md` - Quick reference guide
- Plus additional reconciliation/summary documents

**Total Phase Files**: 5+ files → Consolidate to `delos::operational-decisions` → Archive (keep for session continuity for now)

---

## Part 2: Files to KEEP (Not Remove)

### Root Level - PRESERVE (5 core documentation files)
- [x] `README.md` - Main project documentation (16,469 bytes)
- [x] `CLAUDE.md` - Claude Code directives (10,672 bytes)
- [x] `CONTRIBUTING.md` - Contribution guidelines (9,632 bytes)
- [x] `AGENTS.md` - Agent documentation (1,327 bytes)
- [x] `BEADS.md` - Task tracking documentation (3,308 bytes)

**Total**: 45KB core documentation (DO NOT REMOVE)

### .pm/ Level - PRESERVE
- [x] `CONTINUATION.md` - Active session state (required for session management)
- [x] `.pm/checkpoints/` - Phase progress tracking (evaluate for consolidation)

### .beads/ Level - PRESERVE
- [x] `.beads/issues.jsonl` - Primary issue tracker (464KB, CRITICAL)
- [x] `.beads/config.yaml` - Beads configuration
- [x] `.beads/README.md` - Beads documentation

### Source Code - PRESERVE
- [x] All module-level README.md files (in source directories)
- [x] All source code files (.java, .proto, etc.)
- [x] All build configuration files (pom.xml, etc.)

### Archives - PRESERVE
- [x] `.pm-archives/` directory (916K, historical reference)

---

## Part 3: Consolidation Candidates Summary

### Ready for Archive (After ChromaDB consolidation verified)

**From Root**:
- [ ] ETHEREAL_TEST_CONSOLIDATION_SUMMARY.md (12KB) - READY

**From .pm/audits/**:
- [ ] PHASE_1C_AUDIT_REPORT.md (9KB)
- [ ] DELOS_3917_PLAN_AUDIT.md (8KB)
- [ ] DELOS_3921_AUDIT_REPORT.md (11KB)
- [ ] DELOS_3934_PLAN_AUDIT.md (10KB)
- [ ] DELOS_4000_PLAN_AUDIT.md (8KB)
- [ ] MULTI_COMMITTEE_AGGREGATE_DESIGN_AUDIT.md (8.6KB)
- [ ] h2-function-audit.md (15KB)
- [ ] phase1a0-round2-audit-2026-01-18.md (35KB)
- [ ] DELOS-3937_AUDIT_REPORT.md (9.3KB)
- [ ] Plus 2 additional audit files

**Total to Archive from .pm/audits/**: ~95KB

**From .pm/designs/**:
- [ ] All 18+ design files (~150KB estimated)

**From .pm/plans/**:
- [ ] All 30+ plan files (~300KB estimated)

**From .pm/ root**:
- [ ] PHASE_1B_2_RECONCILIATION.md
- [ ] PHASE_1B3_COMPLETION.md
- [ ] RECONCILIATION_2026-01-20.md
- [ ] Plus additional phase files (~100KB estimated)

**Total for Archive**: ~640KB-800KB

---

## Part 4: Archive Strategy

After ChromaDB consolidation verified, create archive structure:

```
.pm-archives/
├── Consolidation-20260126/
│   ├── audits/
│   │   ├── PHASE_1C_AUDIT_REPORT.md
│   │   ├── DELOS_3917_PLAN_AUDIT.md
│   │   ├── (all audit files)
│   ├── designs/
│   │   ├── (all design files)
│   ├── plans/
│   │   ├── (all plan files)
│   ├── phases/
│   │   ├── PHASE_1B_2_RECONCILIATION.md
│   │   ├── (phase files)
│   ├── root/
│   │   ├── ETHEREAL_TEST_CONSOLIDATION_SUMMARY.md
│   │   ├── FUTURE_WORK_BACKLOG.md (optional)
│   └── README.md (consolidation manifest)
```

**Consolidation Manifest Content**:
- Date of consolidation (2026-01-26)
- List of files archived
- Total size: ~800KB
- ChromaDB collections created
- Migration guide (where to find information in ChromaDB)

---

## Part 5: Execution Checklist

### Phase 1: Verification (Before Consolidation)
- [ ] Verify all consolidation-candidate files are readable
- [ ] Confirm no critical information is in consolidation-marked files only
- [ ] Verify beads tracking (.beads/issues.jsonl) is current
- [ ] Check that CONTINUATION.md is up-to-date

### Phase 2: Consolidation (Populate ChromaDB)
- [ ] Create delos::consensus-architecture collection
- [ ] Create delos::cryptography-signatures collection
- [ ] Create delos::system-architecture collection
- [ ] Create delos::operational-decisions collection
- [ ] Create delos::operational-roadmap collection
- [ ] Extract and add documents from consolidation candidates
- [ ] Verify all key information present in ChromaDB
- [ ] Test search functionality across collections

### Phase 3: Archival (Move files)
- [ ] Create .pm-archives/Consolidation-20260126/ directory
- [ ] Move .pm/audits/ files to archive
- [ ] Move .pm/designs/ files to archive
- [ ] Move .pm/plans/ files to archive
- [ ] Move .pm/PHASE_*.md files to archive
- [ ] Move root consolidation candidates to archive (optional)
- [ ] Create consolidation manifest

### Phase 4: Cleanup (Remove from active workspace)
- [ ] Remove .pm/audits/ directory (empty after archival)
- [ ] Remove .pm/designs/ directory (empty after archival)
- [ ] Remove .pm/plans/ directory (empty after archival)
- [ ] Remove root consolidation files from repo root (if consolidation complete)
- [ ] Clean up empty directories

### Phase 5: Documentation (Update references)
- [ ] Update CONTINUATION.md with ChromaDB collection references
- [ ] Update CLAUDE.md with ChromaDB search tips
- [ ] Add consolidation note to README.md (optional)
- [ ] Commit cleanup changes with reference to consolidation audit

### Phase 6: Verification (Final audit)
- [ ] Verify no critical information lost
- [ ] Test ChromaDB search functionality
- [ ] Verify workspace is cleaner (compare before/after metrics)
- [ ] Update WORKSPACE_CONSOLIDATION_SUMMARY_2026-01-26.md with final results

---

## Part 6: Workspace Impact Analysis

### Before Consolidation
```
Root directory:
- 7 markdown files: 61KB
  - Core docs: 5 files (45KB) ← KEEP
  - Consolidation: 2 files (12KB) ← CONSOLIDATE

.pm/ directory:
- 100+ markdown files: ~1.0M
  - Active session: CONTINUATION.md ← KEEP
  - Consolidation candidates: 80+ files (~800KB) ← CONSOLIDATE

.pm-archives/ directory:
- Completed projects: 916K ← KEEP (historical)

.beads/ directory:
- Issue tracking: 464KB ← KEEP (primary)

Total tracking/documentation: ~1.96M
```

### After Consolidation (Projected)
```
Root directory:
- 5 markdown files: 45KB ← Core docs only

.pm/ directory:
- CONTINUATION.md: 12KB ← Active session only
- .pm/checkpoints/ (if kept): variable
- Estimated: 12-50KB

.pm-archives/ directory:
- Original: 916K
- New consolidation archive: +800KB
- Total: 1.7M ← Historical + consolidated

.beads/ directory:
- Issue tracking: 464KB ← KEEP (primary)

ChromaDB:
- 5 collections with 105-155 documents ← NEW (searchable knowledge)

Total active: ~45KB + 12KB + 464KB = ~521KB (75% reduction)
Total with archives: ~2.5M (knowledge preserved, accessible)
```

### Cleanup Savings
- **Active Workspace**: 1.96M → 521KB = **1.4M freed (73% reduction)**
- **Knowledge Preservation**: All information preserved in searchable ChromaDB
- **Session State**: Preserved in CONTINUATION.md
- **Issue Tracking**: Preserved in .beads/issues.jsonl

---

## Part 7: Success Metrics

### Consolidation Completeness
- [ ] All 248 markdown files inventoried
- [ ] 80-100 consolidation candidates identified
- [ ] 5 ChromaDB collections created
- [ ] 105-155 documents populated
- [ ] Cross-references verified
- [ ] Search functionality tested

### File Organization
- [ ] 5 core documentation files preserved
- [ ] 800KB+ of tracking files archived
- [ ] Session state preserved
- [ ] Issue tracking preserved
- [ ] Source code preserved

### Workspace Cleanliness
- [ ] 73% reduction in active workspace
- [ ] All information accessible
- [ ] Clean directory structure
- [ ] Easy documentation access via ChromaDB

### Knowledge Accessibility
- [ ] All architectural decisions findable
- [ ] All audit reports accessible
- [ ] All design documents discoverable
- [ ] All roadmap items referenceable
- [ ] Cross-domain searches possible

---

## Risk Assessment

### Low Risk
- Archiving audit/plan files (well-integrated in beads)
- Moving design documents (referenced in planning)
- Consolidating future work backlog (managed in beads)

### Medium Risk
- Removing root .md files from repo root (but preserved in ChromaDB)
- Removing .pm/designs, .pm/plans directories (but preserved in archive)

### Mitigation
- Create comprehensive consolidation archive
- Maintain full CONTINUATION.md for session continuity
- Keep beads as source of truth
- Test ChromaDB search before removing files
- Document consolidation thoroughly

---

## Timeline Estimate

**Consolidation Execution**:
- ChromaDB collection creation: 30 min
- Document extraction and population: 2-3 hours
- Verification and testing: 1 hour
- Archive creation and cleanup: 1 hour
- Final audit and documentation: 1 hour

**Total**: ~5-6 hours elapsed time

---

**Status**: Ready for Execution
**Approval**: Pending review
**Next Step**: Implement consolidation following checklist in Part 5
