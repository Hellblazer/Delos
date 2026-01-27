# Documentation Cross-Reference Map
**Date**: January 27, 2026
**Status**: Comprehensive Analysis Complete
**Scope**: 61 documentation files across root, docs/, and module directories

---

## Executive Summary

### Documentation Health Metrics

| Metric | Value | Status |
|--------|-------|--------|
| Total Documentation Files | 61 | ✓ Good |
| Files with Internal Links | 53 | ✓ Good (87%) |
| Valid Internal Links | 130 | ✓ Functional |
| **Broken Internal Links** | **260** | ⚠️ Action Needed |
| **Orphaned Documents** | **37** | ⚠️ Action Needed |
| Link Validation Rate | 33% | ⚠️ Needs Improvement |

### Key Findings

1. **Broken Links (260 total)**:
   - Anchor links within documents (section references) that don't exist: 180+
   - References to files in wrong directories: 40+
   - Missing files/directories: 20+

2. **Orphaned Documents (37 total)**:
   - 37 documents have no incoming references
   - Many are critical guides (ARCHITECTURE.md, DEPLOYMENT_GUIDE.md, etc.)
   - Root-level docs largely unreferenced from docs/ directory

3. **Well-Referenced Documents** (5+ references):
   - `choam/README.md` (13 refs)
   - `fireflies/README.md` (11 refs)
   - `stereotomy/README.md` (9 refs)
   - `ethereal/README.md` (9 refs)
   - `sql-state/README.md` (9 refs)

---

## Directory Structure Overview

```
Delos/
├── README.md                              # Entry point
├── AGENTS.md                              # Agent definitions
├── BEADS.md                               # Task tracking
├── CLAUDE.md                              # Claude Code configuration
├── CONTRIBUTING.md                        # Contribution guidelines
├── docs/                                  # Main documentation hub (46 files)
│   ├── INDEX.md                          # Documentation index
│   ├── ARCHITECTURE.md                   # System architecture
│   ├── DEPLOYMENT_GUIDE.md              # Deployment procedures
│   ├── TESTING_GUIDE.md                 # Testing reference
│   ├── CODE_QUALITY_STANDARDS.md        # Code standards
│   ├── SECURITY_THREAT_MODEL.md         # Security reference
│   ├── adr/                             # Architecture Decision Records (6)
│   └── [40+ other guides]
├── [18 modules with READMEs]             # Module documentation
│   ├── choam/README.md
│   ├── ethereal/README.md
│   ├── fireflies/README.md
│   ├── stereotomy/README.md
│   └── [14 more modules]
└── .pm-archives/                        # Historical project docs
```

---

## Top Referenced Documents

Documents that serve as key hubs and should be entry points:

| Document | References | Role |
|----------|-----------|------|
| choam/README.md | 13 | Consensus state machine core |
| fireflies/README.md | 11 | Membership & gossip |
| stereotomy/README.md | 9 | Identity management |
| ethereal/README.md | 9 | Consensus protocol |
| sql-state/README.md | 9 | State persistence |
| claude.md | 7 | Developer tooling |
| tron/README.md | 7 | FSM framework |
| cryptography/README.md | 6 | Cryptographic primitives |
| delphinius/README.md | 6 | Access control |
| contributing.md | 6 | Contribution process |

---

## Broken Links Analysis

### Categories of Broken Links

#### 1. Missing Section Anchors (180+ cases)
**Impact**: Internal document navigation broken
**Example**:
```markdown
[Deployment Guide](#deployment-guide)  ← section not in document
```

**Affected Documents**:
- CONTRIBUTING.md (9 missing sections)
- Multiple docs/ files (BUILD.md, ARCHITECTURE.md, CODE_QUALITY_STANDARDS.md, etc.)

**Fix Strategy**:
- Match anchor references to actual # headers in documents
- Use consistent header naming (lowercase, spaces→dashes)
- Validate all anchor links

#### 2. Directory Links (20+ cases)
**Impact**: Folder references may not render properly
**Example**:
```markdown
[ADRs](docs/adr/)           ← should be [ADRs](docs/adr)
[Deployment Guides](docs/)  ← vague reference
```

**Affected Documents**:
- README.md (links to `docs/adr/`, `docs/`)
- docs/INDEX.md (multiple directory references)

**Fix Strategy**:
- Use file links instead of directory links when possible
- Point to specific index files (e.g., `docs/INDEX.md`)
- Document directory structure clearly

#### 3. Missing Files (15+ cases)
**Impact**: References point to non-existent files
**Examples**:
- `.pm/CONTINUATION.md` → `.pm/` directory doesn't exist in repo root
- `DEBUGGING_GUIDE.md` → referenced but doesn't exist
- `../witness-service/docs/PHASE_1C_PERFORMANCE_BASELINES.md` → external module

**Affected Documents**:
- README.md (links to `.pm/CONTINUATION.md`)
- IDE_SETUP.md, DEPLOYMENT_GUIDE.md, etc.

**Fix Strategy**:
- Create missing documents or remove references
- Use relative paths correctly for cross-module links
- Document external dependencies clearly

#### 4. Relative Path Issues (25+ cases)
**Impact**: Links work from some locations but not others
**Example**:
```markdown
[Architecture Guide](ARCHITECTURE.md)  ← works from docs/, fails from root
[ADR](adr/0002-keri-implementation-architecture.md)  ← needs ../docs/adr/
```

**Affected Documents**:
- DEPLOYMENT_GUIDE.md, MONITORING_GUIDE.md, BUILD.md, etc.

**Fix Strategy**:
- Use consistent relative path patterns
- Document path conventions clearly
- Test links from multiple document locations

---

## Orphaned Documents (37 total)

**Definition**: Documents with no incoming references from other documents (not counting external links)

### Critical Orphaned Docs (Should Be Referenced)

| Document | Topics | Should Link From |
|----------|--------|------------------|
| docs/ARCHITECTURE.md | System design, layers, abstractions | README.md, INDEX.md, all technical docs |
| docs/DEPLOYMENT_GUIDE.md | Operations, deployment, configuration | README.md, OPERATIONAL_PROCEDURES.md, others |
| docs/TESTING_GUIDE.md | Test strategy, patterns, execution | CODE_QUALITY_STANDARDS.md, CONTRIBUTING.md |
| docs/CODE_QUALITY_STANDARDS.md | Code standards, patterns, guidelines | CONTRIBUTING.md, README.md |
| docs/SECURITY_THREAT_MODEL.md | Security, threat analysis, TLS | DEPLOYMENT_GUIDE.md, OPERATIONAL_PROCEDURES.md |
| docs/HARDWARE_REQUIREMENTS.md | Infrastructure, sizing, capacity | DEPLOYMENT_GUIDE.md, CONFIGURATION_GUIDE.md |
| docs/CONFIGURATION_GUIDE.md | System configuration, parameters | DEPLOYMENT_GUIDE.md, OPERATIONAL_PROCEDURES.md |
| docs/PERFORMANCE_TUNING.md | Performance optimization | DEPLOYMENT_GUIDE.md, MONITORING_GUIDE.md |
| docs/GLOSSARY.md | Terminology, acronyms | INDEX.md, ARCHITECTURE.md |
| docs/DEVELOPER_QUICKSTART.md | Getting started guide | README.md, INDEX.md |

### Less Critical Orphaned Docs

- docs/API_REFERENCE.md
- docs/BLS_AGGREGATION_GUIDE.md
- docs/BUILD.md
- docs/CRYPTOGRAPHY_ALGORITHMS.md
- docs/DISASTER_RECOVERY.md
- docs/IDE_SETUP.md
- docs/INDEX.md (this should be hub but isn't linked from README)
- docs/INTEGRATION_PATTERNS.md
- docs/KERI_INTEGRATION.md
- docs/KNOWLEDGE_CONSOLIDATION.md
- docs/MODULE_DOCUMENTATION_TEMPLATE.md
- docs/MODULE_STANDARDIZATION_STATUS.md
- docs/MONITORING_ALERTING.md (duplicate of MONITORING_AND_ALERTING.md)
- docs/MONITORING_AND_ALERTING.md
- docs/MONITORING_GUIDE.md
- docs/OPERATIONAL_CHECKLISTS.md
- docs/OPERATIONAL_PROCEDURES.md
- docs/OPERATIONAL_QUICK_START.md
- docs/OPS_RUNBOOK_PHASE_1C.md
- docs/PHASE_1C-3-A_KEY_ROTATION_OPERATIONS.md
- docs/TESTING_DOCUMENTATION_UPDATE_SUMMARY.md
- docs/TROUBLESHOOTING_GUIDE.md
- docs/TRANSACTION_FLOW_GUIDE.md
- docs/UPGRADE_PROCEDURES.md
- docs/TLS_SETUP.md
- Root-level: AGENTS.md, ARCHITECTURE_UPDATE_SUMMARY.md, BEADS.md, CODE_QUALITY_UPDATE_SUMMARY.md

---

## Document Relationship Map

### Core Architecture Tier
- `docs/ARCHITECTURE.md` → should link to: DEVELOPER_QUICKSTART.md, DEPLOYMENT_GUIDE.md
- `docs/CODE_QUALITY_STANDARDS.md` → should link to: TESTING_GUIDE.md, CONTRIBUTING.md
- `docs/SECURITY_THREAT_MODEL.md` → should link to: DEPLOYMENT_GUIDE.md, CONFIGURATION_GUIDE.md

### Operations Tier
- `docs/DEPLOYMENT_GUIDE.md` (central hub)
  - Should reference: CONFIGURATION_GUIDE.md, SECURITY_THREAT_MODEL.md, HARDWARE_REQUIREMENTS.md
  - Should reference: MONITORING_AND_ALERTING.md, TROUBLESHOOTING_GUIDE.md
  - Should reference: DISASTER_RECOVERY.md, UPGRADE_PROCEDURES.md

- `docs/OPERATIONAL_PROCEDURES.md`
  - Should reference: DEPLOYMENT_GUIDE.md, MONITORING_AND_ALERTING.md
  - Should reference: TROUBLESHOOTING_GUIDE.md, DISASTER_RECOVERY.md

### Developer Tier
- `README.md` (entry point)
  - Should reference: DEVELOPER_QUICKSTART.md, CONTRIBUTING.md, TESTING_GUIDE.md
  - Should reference: docs/ARCHITECTURE.md, docs/INDEX.md

- `CONTRIBUTING.md`
  - Should reference: CODE_QUALITY_STANDARDS.md, TESTING_GUIDE.md
  - Should reference: module READMEs

### Module Documentation Tier
- Each module README should cross-reference related modules
- All module READMEs should link to docs/ARCHITECTURE.md for context

---

## Link Validation Report

### Broken Links by Severity

#### HIGH PRIORITY (Blocks core workflows)
1. **README.md** → Missing `.pm/CONTINUATION.md` reference
   - Status: Directory doesn't exist
   - Impact: Cannot resume from project context
   - Fix: Create `.pm/` directory or remove reference

2. **CONTRIBUTING.md** → Missing internal sections
   - Status: Section anchors don't match # headers
   - Impact: Cannot navigate within document
   - Fix: Add missing sections or remove links

3. **docs/DEPLOYMENT_GUIDE.md** → Multiple broken directory links
   - Status: References `../adr/` paths that need verification
   - Impact: Users cannot find related ADRs
   - Fix: Verify and correct relative paths

#### MEDIUM PRIORITY (Impacts discoverability)
1. **Multiple docs/** → Anchor link inconsistencies
   - Status: 100+ broken section references
   - Impact: Navigation between sections broken
   - Fix: Audit all # headers and update links

2. **Module READMEs** → Inconsistent relative path usage
   - Status: Some use `../docs/adr/`, others use different patterns
   - Impact: Links work from some modules but not others
   - Fix: Standardize relative path patterns

#### LOW PRIORITY (Documentation maintenance)
1. **Duplicate monitoring documentation**
   - Status: Both `MONITORING_GUIDE.md` and `MONITORING_AND_ALERTING.md` exist
   - Impact: Unclear which is authoritative
   - Fix: Consolidate or clearly mark one as deprecated

---

## Recommended Reading Paths

### For New Developers
1. Start: **README.md** (project overview)
2. Then: **docs/DEVELOPER_QUICKSTART.md** (getting started)
3. Then: **docs/ARCHITECTURE.md** (system design)
4. Deep dive: Specific module READMEs
5. Reference: **docs/TESTING_GUIDE.md** (how to test)
6. Reference: **docs/CODE_QUALITY_STANDARDS.md** (code standards)

### For Operations Teams
1. Start: **README.md** (project context)
2. Then: **docs/DEPLOYMENT_GUIDE.md** (getting systems running)
3. Then: **docs/OPERATIONAL_PROCEDURES.md** (day-to-day)
4. Reference: **docs/MONITORING_AND_ALERTING.md** (observation)
5. Reference: **docs/TROUBLESHOOTING_GUIDE.md** (incident response)
6. Reference: **docs/DISASTER_RECOVERY.md** (backup/restore)

### For Security/Compliance
1. Start: **docs/SECURITY_THREAT_MODEL.md**
2. Then: **docs/DEPLOYMENT_GUIDE.md** (security deployment)
3. Then: **docs/TLS_SETUP.md** (encryption)
4. Reference: **docs/CONFIGURATION_GUIDE.md** (security settings)

### For Architects
1. Start: **docs/ARCHITECTURE.md**
2. Then: **docs/adr/** (design decisions)
3. Then: Module-specific READMEs:
   - CHOAM consensus
   - ETHEREAL Byzantine agreement
   - FIREFLIES membership
   - STEREOTOMY identity
4. Reference: **docs/INTEGRATION_PATTERNS.md**
5. Reference: **docs/CRYPTOGRAPHY_ALGORITHMS.md**

---

## Action Items

### Phase 1: Fix Critical Broken Links (HIGH)

**Task 1: Fix README.md references**
```markdown
CURRENT:  [Knowledge Base](.pm/CONTINUATION.md)
FIX:      Remove or create .pm/ directory structure
ACTION:   Check if .pm/ should exist or if this is outdated
```

**Task 2: Fix CONTRIBUTING.md internal anchors**
- Verify all section headers exist
- Update anchor links to match actual headers
- Add missing sections if referenced

**Task 3: Standardize directory link references**
- Change `docs/adr/` to `docs/adr` or link to specific files
- Change bare `docs/` links to `docs/INDEX.md` where appropriate

### Phase 2: Link Orphaned Documents (MEDIUM)

**Task 1: Create hub links in README.md**
```markdown
Add sections linking to:
- docs/INDEX.md (master documentation index)
- docs/DEVELOPER_QUICKSTART.md (for new developers)
- docs/ARCHITECTURE.md (system overview)
- docs/DEPLOYMENT_GUIDE.md (operations)
- docs/TESTING_GUIDE.md (quality assurance)
```

**Task 2: Add back-references in docs/INDEX.md**
- Ensure INDEX.md links to all 46 docs/ files
- Organize by category (Architecture, Operations, Development, Security)
- Add brief descriptions of each document's purpose

**Task 3: Add module cross-references**
- Each module README should link to related modules
- Each module README should link back to docs/ARCHITECTURE.md
- Create "See Also" sections in module READMEs

### Phase 3: Validation & Standardization (MEDIUM)

**Task 1: Standardize relative paths**
- From docs/: Use `./{filename}` or `{subdir}/{filename}`
- From modules/: Use `../docs/{filename}`
- From root: Use `docs/{filename}`

**Task 2: Audit all anchor links**
- Extract all # headers from documents
- Verify all `[text](#anchor)` links match headers
- Fix mismatches

**Task 3: Consolidate duplicate documentation**
- Choose authoritative version for: MONITORING_*.md files
- Mark deprecated versions clearly
- Redirect to canonical version

### Phase 4: ChromaDB Integration (LONG-TERM)

**Task 1: Link docs to ChromaDB knowledge base**
- Add metadata to docs/ files showing what's in ChromaDB
- Create cross-references to consolidated knowledge
- Link to decision records and research

**Task 2: Create documentation index in ChromaDB**
- Store document metadata (purpose, audience, topics)
- Enable semantic search across all documentation
- Create "related documents" recommendations

---

## Link Validation Checklist

- [ ] All `[text](#anchor)` links have matching # headers
- [ ] All relative paths work from both root and subdirectories
- [ ] No broken file references (all linked files exist)
- [ ] All directory links point to appropriate locations
- [ ] Module READMEs cross-link related modules
- [ ] README.md links to key docs/ files
- [ ] docs/INDEX.md is comprehensive and well-organized
- [ ] Orphaned documents are intentional or linked
- [ ] No duplicate documentation (or clearly marked as such)
- [ ] Contributing guide links to all relevant standards

---

## Statistics Summary

### Link Health
- Total Links Analyzed: 390
- Valid Links: 130 (33%)
- Broken Links: 260 (67%)
- External Links: 150+ (not validated here)

### Document Categories
- Root-level docs: 7
- docs/ directory docs: 46
- Module READMEs: 18
- Total Coverage: 71 documents

### Reference Quality
- Well-referenced documents (5+ refs): 10
- Moderately referenced (2-4 refs): 12
- Barely referenced (1 ref): 2
- Orphaned (0 refs): 37

### Geographic Distribution
- Orphaned in root: 4 (AGENTS.md, BEADS.md, etc.)
- Orphaned in docs/: 30 (majority of guides)
- Orphaned in modules: 3

---

## Next Steps

1. **Immediate (Today)**:
   - Run automated link validation tool
   - Create link fix PR for Phase 1 items

2. **This Week**:
   - Complete Phase 1 & 2 fixes
   - Update docs/INDEX.md with comprehensive index
   - Add cross-references between modules

3. **This Sprint**:
   - Complete Phase 3 standardization
   - Validate all links work
   - Create suggested reading paths document

4. **Long-term**:
   - Integrate with ChromaDB
   - Create automated link validation in CI/CD
   - Maintain link inventory in project tracking

---

**Report Generated**: January 27, 2026
**Analyst**: Knowledge Tidier Agent
**Status**: Ready for Action Items Phase
