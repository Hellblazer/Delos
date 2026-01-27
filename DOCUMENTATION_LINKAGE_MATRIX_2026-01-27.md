# Documentation Linkage Matrix
**Date**: January 27, 2026
**Purpose**: Visual representation of documentation interconnections

---

## How to Read This Matrix

**Rows** = Source documents (what they link to)
**Columns** = Target documents (what links to them)

- ✓ = Valid link exists
- ✗ = Broken link exists
- - = No link
- Blank = Not applicable

---

## Core Documentation Linkage Matrix

```
                          README  CONTRIB  AGENTS  BEADS  CLAUDE  INDEX  ARCH  QUICK
README                      -      ✓       -      -      -      -      -     -
CONTRIBUTING                -      -       -      -      -      -      -     -
AGENTS                       -      -       -      -      -      -      -     -
BEADS                        -      -       -      -      -      -      -     -
CLAUDE                       -      -       -      -      -      -      -     -
docs/INDEX                   -      -       -      -      -      -      -     -
docs/ARCHITECTURE            -      -       -      -      -      ✓      -     ✓
docs/DEVELOPER_QUICKSTART    ✓      ✓       -      -      -      ✓      ✓     -
```

**Current State**: Most core docs are orphaned (no incoming links from README)

---

## Incoming Links by Document (Current State)

### Documents With Good Connectivity (5+ incoming links)

```
choam/README.md                          13 incoming links ✓
fireflies/README.md                      11 incoming links ✓
stereotomy/README.md                      9 incoming links ✓
ethereal/README.md                        9 incoming links ✓
sql-state/README.md                       9 incoming links ✓
```

### Documents With Moderate Connectivity (2-4 links)

```
claude.md                                 7 incoming links
tron/README.md                            7 incoming links
cryptography/README.md                    6 incoming links
delphinius/README.md                      6 incoming links
contributing.md                           6 incoming links
model/README.md                           5 incoming links
docs/testing_guide.md                     4 incoming links
memberships/README.md                     4 incoming links
docs/code_quality_standards.md            3 incoming links
```

### Documents With Poor Connectivity (0-1 links)

```
AGENTS.md                                 0 incoming links ✗
ARCHITECTURE_UPDATE_SUMMARY.md            0 incoming links ✗
BEADS.md                                 0 incoming links ✗
CODE_QUALITY_UPDATE_SUMMARY.md           0 incoming links ✗
docs/API_REFERENCE.md                     0 incoming links ✗
docs/ARCHITECTURE.md                      0 incoming links ✗ CRITICAL!
docs/BLS_AGGREGATION_GUIDE.md             0 incoming links ✗
docs/BUILD.md                             0 incoming links ✗
docs/CONFIGURATION_GUIDE.md               0 incoming links ✗
docs/CRYPTOGRAPHY_ALGORITHMS.md           0 incoming links ✗
docs/DEPLOYMENT_GUIDE.md                  0 incoming links ✗ CRITICAL!
docs/DEVELOPER_QUICKSTART.md              0 incoming links ✗ CRITICAL!
[and 24 more orphaned documents]          0 incoming links ✗
```

---

## Key Documentation Pathways

### Getting Started Path (BROKEN - No links)

```
README.md
  └─ [Missing Link] → docs/DEVELOPER_QUICKSTART.md (ORPHANED)
       └─ [Missing Link] → docs/ARCHITECTURE.md (ORPHANED)
            └─ [Missing Link] → Module READMEs
```

**Status**: ✗ BROKEN - No way to reach getting started docs from README

---

### Operations Path (BROKEN - No links)

```
README.md
  └─ [Missing Link] → docs/DEPLOYMENT_GUIDE.md (ORPHANED)
       ├─ [Broken Links] → docs/SECURITY_THREAT_MODEL.md (ORPHANED)
       ├─ [Broken Links] → docs/CONFIGURATION_GUIDE.md (ORPHANED)
       ├─ [Broken Links] → docs/HARDWARE_REQUIREMENTS.md (ORPHANED)
       └─ [Missing Link] → docs/OPERATIONAL_PROCEDURES.md (ORPHANED)
            ├─ [Missing Link] → docs/MONITORING_AND_ALERTING.md (ORPHANED)
            └─ [Missing Link] → docs/TROUBLESHOOTING_GUIDE.md (ORPHANED)
```

**Status**: ✗ BROKEN - Operators cannot navigate through deployment workflow

---

### Development Path (BROKEN - No links)

```
README.md
  └─ [Missing Link] → CONTRIBUTING.md
       ├─ [Missing Anchors] → Code Style (9 broken anchors)
       └─ [Missing Link] → docs/CODE_QUALITY_STANDARDS.md (ORPHANED)
            ├─ [Missing Link] → docs/TESTING_GUIDE.md (ORPHANED)
            └─ [Missing Link] → docs/BUILD.md (ORPHANED)
```

**Status**: ⚠️ PARTIALLY BROKEN - Some links exist but many anchors broken

---

## Cross-Module Linkage Status

### Core Protocol Layer
```
grpc/README.md
  ├─ [No links] ← cryptography/README.md
  ├─ [No links] ← protocols/README.md
  └─ [No links] ← memberships/README.md
```
**Status**: ✗ ISOLATED - No interdependency documentation

### Identity & Security Layer
```
stereotomy/README.md → gorgoneion/README.md ✓
  ├─ gorgoneion-client/README.md ✓
  └─ thoth/README.md (missing link)

stereotomy-services/README.md (missing from main structure)
```
**Status**: ⚠️ PARTIAL - Some links, some missing

### Consensus Layer
```
ethereal/README.md ← fireflies/README.md ✓
  └─ choam/README.md ✓
       └─ sql-state/README.md ✓
            └─ schemas/README.md (missing link)
```
**Status**: ⚠️ PARTIAL - Core chain linked, but gaps exist

### Application Layer
```
model/README.md ← delphinius/README.md ✓
  └─ tron/README.md ✓
```
**Status**: ✓ GOOD - Application layer well-linked

---

## Link Health by File Type

### Root-Level Files (7 total)
```
README.md                     BROKEN (10 links)
CONTRIBUTING.md              BROKEN (9 anchors)
CLAUDE.md                     OK (no internal refs)
AGENTS.md                     ORPHANED
BEADS.md                      ORPHANED
ARCHITECTURE_UPDATE_SUMMARY   ORPHANED
CODE_QUALITY_UPDATE_SUMMARY   ORPHANED

Health: 28% (2 of 7 OK)
```

### docs/ Directory (46 total)
```
Well-linked docs:             3 files (7%)
Moderately-linked docs:       8 files (17%)
Poorly-linked docs:          35 files (76%) - MOSTLY ORPHANED

Health: 24% (valid links only, not counting orphaned)
```

### Module READMEs (18 total)
```
Well-linked modules:         10 files (56%)
Moderately-linked modules:    5 files (28%)
Poorly-linked modules:        3 files (16%)

Health: 84% (best performance)
```

### Overall Health
```
Well-linked:                 13 files (21%)
Moderately-linked:           15 files (25%)
Poorly-linked:              33 files (54%)
```

---

## Critical Broken Links Map

### README.md → Outgoing Links (10)

```
BROKEN: [Knowledge Base](.pm/CONTINUATION.md)
        File doesn't exist - should be removed

BROKEN: [**ADRs**](docs/adr/)
        Directory link - should point to specific file

BROKEN: [**Deployment Guides**](docs/)
        Directory link - should point to docs/INDEX.md

BROKEN: [Stereotomy Services](stereotomy-services)
        Missing /README.md suffix

BROKEN: [Modules section](#modules)
        Anchor doesn't exist - should verify or remove
```

### docs/INDEX.md → Outgoing Links (15+)

```
BROKEN ANCHORS (internal):
  - [Deployment Guide](#deployment-guide)
  - [Security Threat Model](#security-threat-model)
  - [Developer Guide](#developer-guide--getting-started)
  - [Component Modules](#modules--component-documentation)
  - [Troubleshooting](#troubleshooting--common-issues)
  - [Operations Guide](#operations--monitoring)
  - [Consensus Architecture](#consensus--agreement)

BROKEN PATHS (external):
  - [adr/](../adr/) should be ./adr/
  - [TRANSACTION_FLOW_GUIDE.md](TRANSACTION_FLOW_GUIDE.md) should be ./TRANSACTION_FLOW_GUIDE.md
  - [and 8 more similar issues]
```

### docs/DEPLOYMENT_GUIDE.md → Outgoing Links (6)

```
BROKEN: [DISASTER_RECOVERY_GUIDE.md] - file named DISASTER_RECOVERY.md
BROKEN: [Missing section anchor] - #phase-1c-bls-performance--operational-hardening
BROKEN: [Witness service path] - ../witness-service/docs/PHASE_1C_PERFORMANCE_BASELINES.md
```

---

## Documentation Hub Effectiveness

### Attempted Hubs (Should Have High Connectivity)

| File | Incoming Links | Outgoing Valid | Outgoing Broken | Health |
|------|----------------|----------------|-----------------|--------|
| README.md | 0 | 6 | 10 | 37% ✗ |
| docs/INDEX.md | 0 | 20 | 15+ | 57% ✗ |
| docs/ARCHITECTURE.md | 0 | 3 | 7 | 30% ✗ |
| CONTRIBUTING.md | 6 | 3 | 9 | 25% ✗ |

**Assessment**: No effective documentation hub exists. README is broken, INDEX is orphaned.

---

## Recommended Link Targets After Fixes

### What README.md Should Link To (Priority 1)

```
docs/DEVELOPER_QUICKSTART.md     ← Getting started
docs/ARCHITECTURE.md             ← System design
docs/CONTRIBUTING.md             ← How to contribute
docs/TESTING_GUIDE.md            ← Quality assurance
docs/CODE_QUALITY_STANDARDS.md   ← Code standards
docs/DEPLOYMENT_GUIDE.md         ← Operations
docs/SECURITY_THREAT_MODEL.md    ← Security overview
docs/INDEX.md                    ← Full documentation
```

### What docs/INDEX.md Should Reference (All 46 docs)

**Getting Started** (3 docs):
- DEVELOPER_QUICKSTART.md
- BUILD.md
- IDE_SETUP.md

**Architecture** (8 docs):
- ARCHITECTURE.md
- CRYPTOGRAPHY_ALGORITHMS.md
- BLS_AGGREGATION_GUIDE.md
- KERI_INTEGRATION.md
- INTEGRATION_PATTERNS.md
- TRANSACTION_FLOW_GUIDE.md
- adr/ directory
- GLOSSARY.md

**Operations** (15 docs):
- DEPLOYMENT_GUIDE.md
- OPERATIONAL_PROCEDURES.md
- CONFIGURATION_GUIDE.md
- HARDWARE_REQUIREMENTS.md
- MONITORING_AND_ALERTING.md
- MONITORING_GUIDE.md
- PERFORMANCE_TUNING.md
- TROUBLESHOOTING_GUIDE.md
- DISASTER_RECOVERY.md
- UPGRADE_PROCEDURES.md
- OPERATIONAL_QUICK_START.md
- OPERATIONAL_CHECKLISTS.md
- OPS_RUNBOOK_PHASE_1C.md
- PHASE_1C-3-A_KEY_ROTATION_OPERATIONS.md
- TLS_SETUP.md

**Security** (5 docs):
- SECURITY_THREAT_MODEL.md
- KERI_INTEGRATION.md
- TLS_SETUP.md
- (security topics from other docs)

**Quality** (5 docs):
- CODE_QUALITY_STANDARDS.md
- TESTING_GUIDE.md
- API_REFERENCE.md
- BUILD.md

**Reference** (5 docs):
- GLOSSARY.md
- MODULE_DOCUMENTATION_TEMPLATE.md
- MODULE_STANDARDIZATION_STATUS.md
- KNOWLEDGE_CONSOLIDATION.md
- (various update summaries)

---

## Current vs Target Link Count

### README.md
```
Current outgoing links: 16 (10 broken)
Target outgoing links: 20+
Current incoming links: 0
Target incoming links: 0 (entry point)
```

### docs/INDEX.md
```
Current outgoing links: 35+ (15 broken)
Target outgoing links: 46 (all docs)
Current incoming links: 0
Target incoming links: 5+ (from README, CONTRIBUTING, etc.)
```

### docs/ARCHITECTURE.md
```
Current outgoing links: 10 (7 broken)
Target outgoing links: 15+
Current incoming links: 0
Target incoming links: 5+ (from README, INDEX, modules, others)
```

### Average Module README
```
Current outgoing links: 2-3 (mostly valid)
Target outgoing links: 4-6 (include related modules)
Current incoming links: 5-9 (well-referenced)
Target incoming links: 7-10 (more cross-refs)
```

---

## Link Quality Scoring System

**Score Formula**: (Valid Links / Total Links) × 100

| Score | Status | Examples |
|-------|--------|----------|
| 90-100% | Excellent | choam/README.md, fireflies/README.md |
| 70-89% | Good | (none currently) |
| 50-69% | Fair | CONTRIBUTING.md (25%), docs/INDEX.md (57%) |
| 30-49% | Poor | README.md (37%), docs/ARCHITECTURE.md (30%) |
| <30% | Critical | Many docs/ files |

**Current Distribution**:
- Excellent (90%+): 2 docs
- Good (70-89%): 0 docs
- Fair (50-69%): 2 docs
- Poor (30-49%): 5 docs
- Critical (<30%): 52 docs

**Target Distribution**:
- Excellent (90%+): 20+ docs
- Good (70-89%): 25+ docs
- Fair (50-69%): 10+ docs
- Poor (30-49%): 5 docs
- Critical (<30%): 0 docs

---

## Summary

### Current State (Broken)
- 260 broken links
- 37 orphaned documents
- 33% valid link rate
- 0 effective documentation hubs
- Poor discoverability

### Target State (Fixed)
- <50 broken links
- <10 orphaned documents
- >85% valid link rate
- Multiple effective hubs (README, INDEX, ARCHITECTURE)
- Excellent discoverability

### Gap to Close
- Fix 210+ broken links
- Link 27+ orphaned documents
- Standardize path formats across 46 docs/files
- Create 15+ new cross-references
- Build validation infrastructure

---

**Analysis Complete**: January 27, 2026
**Next Steps**: See DOCUMENTATION_LINKING_ACTION_PLAN_2026-01-27.md for implementation
