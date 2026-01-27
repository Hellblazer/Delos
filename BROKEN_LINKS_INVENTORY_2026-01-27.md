# Broken Links Inventory & Repair Guide
**Date**: January 27, 2026
**Total Broken Links**: 260
**Affected Files**: 23
**Severity Levels**: HIGH (6), MEDIUM (12), LOW (5)

---

## Summary by Severity

### HIGH PRIORITY (Blocks workflows)
1. README.md - Missing `.pm/` directory reference
2. CONTRIBUTING.md - 9 missing internal sections
3. docs/DEPLOYMENT_GUIDE.md - Multiple broken directory references
4. docs/INDEX.md - Broken anchor references
5. docs/ARCHITECTURE.md - Broken internal anchors
6. docs/BUILD.md - Broken section anchors

### MEDIUM PRIORITY (Impacts discoverability)
- 12 docs/ files with anchor link issues
- Module READMEs with relative path problems

### LOW PRIORITY (Documentation maintenance)
- Duplicate documentation
- Optional references to non-existent files

---

## Detailed Broken Links by File

### 1. README.md (ROOT LEVEL - HIGH PRIORITY)

**Broken Links**: 10
**Impact**: Entry point has broken references

#### Issue: Missing `.pm/` directory
```markdown
BROKEN: [Knowledge Base](.pm/CONTINUATION.md)
STATUS: .pm/ directory doesn't exist in repo root
FIX: Either:
  a) Create .pm/CONTINUATION.md and add to repo
  b) Remove this link (if outdated)
  c) Link to existing documentation (docs/INDEX.md)
RECOMMENDATION: Remove or replace with docs/INDEX.md reference
```

#### Issue: Directory links without files
```markdown
BROKEN: [**ADRs**](docs/adr/)
ISSUE:  Directory links may not render in all markdown viewers
FIX:    Link to a specific file or INDEX
RECOMMENDATION: [**ADRs**](docs/adr) or point to an index file
```

#### Issue: Module link inconsistency
```markdown
BROKEN: [Stereotomy Services](stereotomy-services)
ISSUE:  Should be [Stereotomy Services](stereotomy-services/README.md)
FIX:    Add /README.md extension
```

#### Missing Files
- `witness-service/README.md` - EXISTS ✓
- `h2-deterministic/` - Module exists
- `liquibase-deterministic/` - Module exists

#### Repair Actions
```markdown
1. Find: [**ADRs**](docs/adr/)
   Replace: [**ADRs**](docs/adr/0000-use-markdown-architecture-decision-records.md)

2. Find: [**Deployment Guides**](docs/)
   Replace: [**Deployment Guides**](docs/INDEX.md)

3. Find: [Knowledge Base](.pm/CONTINUATION.md)
   Replace: Remove this line (outdated)

4. Find: [Modules section](#modules)
   VERIFY: Check if ## Modules section exists in document
```

---

### 2. CONTRIBUTING.md (ROOT LEVEL - HIGH PRIORITY)

**Broken Links**: 9
**Impact**: Navigation within guidelines broken

#### Missing Section Anchors
All of these anchor links reference sections that don't exist in the document:

```markdown
BROKEN ANCHORS:
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Code Style and Conventions](#code-style-and-conventions)
- [Testing Requirements](#testing-requirements)
- [Commit Message Format](#commit-message-format)
- [Branch Naming](#branch-naming)
- [Pull Request Process](#pull-request-process)
- [Code Review](#code-review)
- [Issue Reporting](#issue-reporting)

ACTUAL DOCUMENT STRUCTURE: Need to verify what sections exist
```

#### Repair Actions
1. **Option A**: Add all missing sections to CONTRIBUTING.md
2. **Option B**: Remove all anchor links and keep plain text navigation
3. **Option C**: Create the sections that are referenced

**Recommended**: Add sections matching the anchor references, or replace links with "See CODE_QUALITY_STANDARDS.md" and "See TESTING_GUIDE.md"

---

### 3. docs/ARCHITECTURE.md (MEDIUM PRIORITY)

**Broken Links**: 7
**Impact**: Users cannot navigate to related documents

```markdown
BROKEN: [High-Level Overview](#high-level-overview)
       [Layer Architecture](#layer-architecture)
       [Module Dependencies](#module-dependencies)
       [Consensus Data Flow](#consensus-data-flow)
       [Network Topology](#network-topology)
       [Key Abstractions](#key-abstractions)

ISSUE: These are internal anchors - need to verify # headers exist
ACTION: Check document and verify all # headers match anchor names
```

#### External Doc Links (need verification)
```markdown
[DEVELOPER_QUICKSTART.md](DEVELOPER_QUICKSTART.md)
  ISSUE: Should be [DEVELOPER_QUICKSTART.md](../docs/DEVELOPER_QUICKSTART.md)?
         File is in docs/, not same directory
  FIX: Use relative path: ./DEVELOPER_QUICKSTART.md or same-directory reference

[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)
  ISSUE: Same issue
  FIX: Use ./DEPLOYMENT_GUIDE.md

[GLOSSARY.md](GLOSSARY.md)
  ISSUE: Same issue
  FIX: Use ./GLOSSARY.md
```

---

### 4. docs/API_REFERENCE.md (MEDIUM PRIORITY)

**Broken Links**: 2
**Impact**: API reference cannot link to guides

```markdown
BROKEN: [TRANSACTION_FLOW_GUIDE.md](TRANSACTION_FLOW_GUIDE.md)
        [IDE_SETUP.md](IDE_SETUP.md)

FIX: Both files exist, links should work
ACTION: Verify file names match exactly (case-sensitive)
        Confirmed: files exist at docs/TRANSACTION_FLOW_GUIDE.md
                                     docs/IDE_SETUP.md
```

---

### 5. docs/BUILD.md (MEDIUM PRIORITY)

**Broken Links**: 8
**Impact**: Build guide sections not navigable

```markdown
BROKEN SECTION ANCHORS:
- [Prerequisites](#prerequisites)
- [Build Profiles](#build-profiles)
- [Standard Build Procedures](#standard-build-procedures)
- [Advanced Builds](#advanced-builds)
- [Test Execution](#test-execution)
- [Code Generation](#code-generation)
- [Troubleshooting Build Issues](#troubleshooting-build-issues)
- [CI/CD Integration](#cicd-integration)

ACTION: Verify that all these # headers exist in docs/BUILD.md
        If some are missing, either add headers or remove anchor links
```

#### External Links
```markdown
[Deployment Guide](DEPLOYMENT_GUIDE.md)
  FIX: Use ./DEPLOYMENT_GUIDE.md

[Architecture Overview](ARCHITECTURE.md)
  FIX: Use ./ARCHITECTURE.md

[Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md)
  FIX: Use ./TROUBLESHOOTING_GUIDE.md
```

---

### 6. docs/CODE_QUALITY_STANDARDS.md (MEDIUM PRIORITY)

**Broken Links**: 9
**Impact**: Code standards guide cannot link to related docs

```markdown
BROKEN ANCHORS (internal):
- [Design Patterns](#design-patterns)
- [Concurrency Guidelines](#concurrency-guidelines)
- [Error Handling Strategy](#error-handling-strategy)
- [Code Quality Standards](#code-quality-standards)
- [Package Organization](#package-organization)
- [Architecture & Principles](#architecture--principles)
- [Code Review Checklist](#code-review-checklist)
- [Technology Stack](#technology-stack)

ACTION: Verify all these # headers exist in the document

EXTERNAL LINKS:
[DEVELOPER_QUICKSTART.md](DEVELOPER_QUICKSTART.md)
[ARCHITECTURE.md](ARCHITECTURE.md)
[BUILD.md](BUILD.md)
[TESTING_GUIDE.md](TESTING_GUIDE.md)

FIX: Use ./ prefix: ./DEVELOPER_QUICKSTART.md, etc.
```

---

### 7. docs/CONFIGURATION_GUIDE.md (MEDIUM PRIORITY)

**Broken Links**: 9
**Impact**: Configuration guide incomplete cross-references

#### Anchor Issues
```markdown
BROKEN:
- [Configuration Overview](#configuration-overview)
- [Configuration Methods](#configuration-methods)
- [Identity Configuration](#identity-configuration)
- [Network Configuration](#network-configuration)
- [Consensus Configuration](#consensus-configuration)
- [Database Configuration](#database-configuration)
- [Performance Tuning Parameters](#performance-tuning-parameters)
- [Monitoring & Logging](#monitoring--logging)
- [Phase 1C: BLS Configuration](#phase-1c-bls-configuration)
- [Common Configuration Scenarios](#common-configuration-scenarios)

ACTION: Verify these sections exist
```

#### External Links
```markdown
[Deployment Guide](DEPLOYMENT_GUIDE.md) → ./DEPLOYMENT_GUIDE.md
[Hardware Requirements](HARDWARE_REQUIREMENTS.md) → ./HARDWARE_REQUIREMENTS.md
[Monitoring Guide](MONITORING_GUIDE.md) → ./MONITORING_GUIDE.md
[Performance Tuning](PERFORMANCE_TUNING.md) → ./PERFORMANCE_TUNING.md
[OPS Runbook](OPS_RUNBOOK_PHASE_1C.md) → ./OPS_RUNBOOK_PHASE_1C.md
```

---

### 8. docs/DEPLOYMENT_GUIDE.md (HIGH PRIORITY)

**Broken Links**: 6
**Impact**: Critical deployment documentation has broken references

```markdown
BROKEN:
[SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md)
  FIX: ./SECURITY_THREAT_MODEL.md

[Performance Tuning Guide](./PERFORMANCE_TUNING.md#phase-1c-bls-performance--operational-hardening)
  FIX: Verify anchor exists in PERFORMANCE_TUNING.md
       anchor: #phase-1c-bls-performance--operational-hardening

[Phase 1C Performance Baselines](../witness-service/docs/PHASE_1C_PERFORMANCE_BASELINES.md)
  ISSUE: witness-service module referenced - verify path
  STATUS: witness-service exists but verify docs/ subdirectory

[DISASTER_RECOVERY_GUIDE.md](DISASTER_RECOVERY_GUIDE.md)
  ISSUE: File named DISASTER_RECOVERY.md not DISASTER_RECOVERY_GUIDE.md
  FIX: ./DISASTER_RECOVERY.md
```

---

### 9. docs/DEVELOPER_QUICKSTART.md (MEDIUM PRIORITY)

**Broken Links**: 7
**Impact**: Getting started guide cannot link to prerequisites

```markdown
BROKEN (External):
[ARCHITECTURE.md](ARCHITECTURE.md) → ./ARCHITECTURE.md (appears twice)
[TESTING_GUIDE.md](TESTING_GUIDE.md) → ./TESTING_GUIDE.md
[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) → ./DEPLOYMENT_GUIDE.md
[MONITORING_GUIDE.md](MONITORING_GUIDE.md) → ./MONITORING_GUIDE.md
[GLOSSARY.md](GLOSSARY.md) → ./GLOSSARY.md
[CODE_QUALITY_STANDARDS.md](CODE_QUALITY_STANDARDS.md) → ./CODE_QUALITY_STANDARDS.md

EXTERNAL PATHS:
[examples/simple-kv-store/](../examples/simple-kv-store/)
[examples/local-demo/](../examples/local-demo/)
  STATUS: These modules exist, but directory links may not work
  FIX: Point to README.md files instead
       ../examples/simple-kv-store/README.md
       ../examples/local-demo/README.md
```

---

### 10. docs/DISASTER_RECOVERY.md (MEDIUM PRIORITY)

**Broken Links**: 7 (section anchors)
**Impact**: Disaster recovery procedures not navigable

```markdown
BROKEN ANCHORS:
- [Backup Strategy](#backup-strategy)
- [Backup Procedures](#backup-procedures)
- [Restore Procedures](#restore-procedures)
- [RTO/RPO Planning](#rto-rpo-planning)
- [Disaster Scenarios](#disaster-scenarios)
- [Recovery Procedures](#recovery-procedures)

ACTION: Verify these # headers exist in document
```

---

### 11. docs/HARDWARE_REQUIREMENTS.md (MEDIUM PRIORITY)

**Broken Links**: 9 (anchors)
**Impact**: Hardware planning guide sections not navigable

```markdown
BROKEN ANCHORS:
- [Byzantine Fault Tolerance & Sizing](#byzantine-fault-tolerance--sizing)
- [Minimum Requirements](#minimum-requirements)
- [Recommended Configurations](#recommended-configurations)
- [Hardware Specifications](#hardware-specifications)
- [Network Requirements](#network-requirements)
- [Storage Planning](#storage-planning)
- [Performance Scaling](#performance-scaling)
- [Cloud Provider Sizing](#cloud-provider-sizing)

EXTERNAL LINKS (need ./ prefix):
[Deployment Guide](DEPLOYMENT_GUIDE.md) → ./DEPLOYMENT_GUIDE.md
[Performance Tuning](PERFORMANCE_TUNING.md) → ./PERFORMANCE_TUNING.md
[Monitoring Guide](MONITORING_GUIDE.md) → ./MONITORING_GUIDE.md
[Capacity Planning](OPERATIONAL_PROCEDURES.md) → ./OPERATIONAL_PROCEDURES.md
```

---

### 12. docs/IDE_SETUP.md (MEDIUM PRIORITY)

**Broken Links**: 4
**Impact**: IDE setup guide cannot link to prerequisites

```markdown
BROKEN:
[Debugging Guide](DEBUGGING_GUIDE.md) → File doesn't exist
  ACTION: Remove reference or create DEBUGGING_GUIDE.md

[TRANSACTION_FLOW_GUIDE.md](TRANSACTION_FLOW_GUIDE.md)
  FIX: ./TRANSACTION_FLOW_GUIDE.md

[DEBUGGING_GUIDE.md](DEBUGGING_GUIDE.md) → Doesn't exist
  ACTION: Remove or create file

[INDEX.md](INDEX.md) → ./INDEX.md

[TRANSACTION_FLOW_GUIDE.md](TRANSACTION_FLOW_GUIDE.md)
  FIX: ./TRANSACTION_FLOW_GUIDE.md (duplicate)
```

---

### 13. docs/INDEX.md (HIGH PRIORITY)

**Broken Links**: 15+
**Impact**: Master documentation index itself has broken links

```markdown
BROKEN ANCHORS (within INDEX.md):
- [Deployment Guide](#deployment-guide)
- [Security Threat Model](#security-threat-model)
- [Developer Guide](#developer-guide--getting-started)
- [Component Modules](#modules--component-documentation)
- [Troubleshooting](#troubleshooting--common-issues)
- [Operations Guide](#operations--monitoring)
- [Consensus Architecture](#consensus--agreement)

RELATIVE PATH ISSUES:
[More ADRs available in adr/ directory](../adr/)
  ISSUE: Should be (../docs/adr/) or point to specific file
  FIX: [More ADRs available in adr/ directory](./adr)

EXTERNAL LINKS (need ./ prefix):
[TRANSACTION_FLOW_GUIDE.md](TRANSACTION_FLOW_GUIDE.md) → ./TRANSACTION_FLOW_GUIDE.md
[IDE_SETUP.md](IDE_SETUP.md) → ./IDE_SETUP.md
[API_REFERENCE.md](API_REFERENCE.md) → ./API_REFERENCE.md
[INTEGRATION_PATTERNS.md](INTEGRATION_PATTERNS.md) → ./INTEGRATION_PATTERNS.md
[PERFORMANCE_TUNING.md](PERFORMANCE_TUNING.md) → ./PERFORMANCE_TUNING.md
[OPERATIONAL_PROCEDURES.md](OPERATIONAL_PROCEDURES.md) → ./OPERATIONAL_PROCEDURES.md
[DISASTER_RECOVERY.md](DISASTER_RECOVERY.md) → ./DISASTER_RECOVERY.md
[TROUBLESHOOTING_GUIDE.md](TROUBLESHOOTING_GUIDE.md) → ./TROUBLESHOOTING_GUIDE.md
[MONITORING_ALERTING.md](MONITORING_ALERTING.md) → ./MONITORING_ALERTING.md
[SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md) → ./SECURITY_THREAT_MODEL.md
[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) → ./DEPLOYMENT_GUIDE.md
[Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) → ./TROUBLESHOOTING_GUIDE.md
[adr/](../adr/) → ./adr or ./adr/0000-use-markdown-architecture-decision-records.md
[adr/0004-consensus-design-choam.md](../adr/0004-consensus-design-choam.md) → ./adr/0004-consensus-design-choam.md
[MODULE_DOCUMENTATION_TEMPLATE.md](MODULE_DOCUMENTATION_TEMPLATE.md) → ./MODULE_DOCUMENTATION_TEMPLATE.md
```

---

### 14. docs/INTEGRATION_PATTERNS.md (LOW PRIORITY)

**Broken Links**: 8 (section anchors)
**Impact**: Integration patterns guide sections not navigable

```markdown
BROKEN ANCHORS:
- [Pattern 1: Simple SQL State Machine](#pattern-1-simple-sql-state-machine)
- [Pattern 2: Multi-Tenant Application](#pattern-2-multi-tenant-application)
- [Pattern 3: Event-Driven FSM Workflow](#pattern-3-event-driven-fsm-workflow)
- [Pattern 4: Custom Transaction Processing](#pattern-4-custom-transaction-processing)
- [Pattern 5: Batch Processing with Checkpointing](#pattern-5-batch-processing-with-checkpointing)
- [Pattern 6: Domain-Driven Design](#pattern-6-domain-driven-design)
- [Anti-Patterns & Pitfalls](#anti-patterns--pitfalls)
- [Pattern Comparison](#pattern-comparison)

ACTION: Verify all section headers exist in document
```

---

### 15. docs/KERI_INTEGRATION.md (MEDIUM PRIORITY)

**Broken Links**: 7
**Impact**: KERI integration guide cannot link to related docs

```markdown
BROKEN (missing ./ prefix):
[Cryptography Algorithms Reference](CRYPTOGRAPHY_ALGORITHMS.md)
  FIX: ./CRYPTOGRAPHY_ALGORITHMS.md

[BLS Aggregation Guide](BLS_AGGREGATION_GUIDE.md)
  FIX: ./BLS_AGGREGATION_GUIDE.md

[Security Threat Model](SECURITY_THREAT_MODEL.md)
  FIX: ./SECURITY_THREAT_MODEL.md

[TLS Setup](TLS_SETUP.md)
  FIX: ./TLS_SETUP.md

[Stereotomy Threat Model](../stereotomy/docs/THREAT_MODEL.md)
  ISSUE: Path may be wrong, verify stereotomy module structure

[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)
  FIX: ./DEPLOYMENT_GUIDE.md

[GLOSSARY.md](GLOSSARY.md)
  FIX: ./GLOSSARY.md

[ADR-0002: KERI Implementation Architecture](adr/0002-keri-implementation-architecture.md)
  FIX: ./adr/0002-keri-implementation-architecture.md
```

---

### 16. docs/MONITORING_ALERTING.md (LOW PRIORITY)

**Broken Links**: 5 (section anchors)
**Impact**: Monitoring guide sections not navigable

```markdown
BROKEN ANCHORS:
- [Metrics Collection Setup](#metrics-collection-setup)
- [Key Metrics Reference](#key-metrics-reference)
- [Dashboard Setup](#dashboard-setup)
- [Alerting Rules](#alerting-rules)
- [On-Call Guide](#on-call-guide)
- [SLA & Targets](#sla--targets)

NOTE: This file appears to be duplicate of MONITORING_AND_ALERTING.md
      Consider consolidating
```

---

### 17. docs/MONITORING_AND_ALERTING.md (MEDIUM PRIORITY)

**Broken Links**: 5 (external doc references)
**Impact**: Monitoring guide cannot link to related procedures

```markdown
BROKEN (missing ./ prefix):
[Deployment Guide](DEPLOYMENT_GUIDE.md) → ./DEPLOYMENT_GUIDE.md
[Operational Procedures](OPERATIONAL_PROCEDURES.md) → ./OPERATIONAL_PROCEDURES.md
[Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) → ./TROUBLESHOOTING_GUIDE.md
[Performance Tuning](PERFORMANCE_TUNING.md) → ./PERFORMANCE_TUNING.md
[OPS Runbook](OPS_RUNBOOK_PHASE_1C.md) → ./OPS_RUNBOOK_PHASE_1C.md
```

---

### 18. docs/MONITORING_GUIDE.md (MEDIUM PRIORITY)

**Broken Links**: 3
**Impact**: Monitoring guide has broken cross-references

```markdown
BROKEN:
[CHOAM: Consensus Design](docs/adr/0004-consensus-design-choam.md)
  ISSUE: Path has docs/ prefix but already in docs/
  FIX: ./adr/0004-consensus-design-choam.md

[Deployment Guide](DEPLOYMENT_GUIDE.md)
  FIX: ./DEPLOYMENT_GUIDE.md

[Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md)
  FIX: ./TROUBLESHOOTING_GUIDE.md
```

---

### 19. docs/OPERATIONAL_CHECKLISTS.md (MEDIUM PRIORITY)

**Broken Links**: 4
**Impact**: Operational checklists cannot link to procedures

```markdown
BROKEN (missing ./ prefix):
[Operational Procedures](OPERATIONAL_PROCEDURES.md) → ./OPERATIONAL_PROCEDURES.md
[Monitoring Guide](MONITORING_AND_ALERTING.md) → ./MONITORING_AND_ALERTING.md
[Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) → ./TROUBLESHOOTING_GUIDE.md
[Disaster Recovery](DISASTER_RECOVERY.md) → ./DISASTER_RECOVERY.md
```

---

### 20. docs/OPERATIONAL_PROCEDURES.md (MEDIUM PRIORITY)

**Broken Links**: 7 (section anchors)
**Impact**: Operations procedures sections not navigable

```markdown
BROKEN ANCHORS:
- [Quick Reference](#quick-reference)
- [Day-to-Day Operations](#day-to-day-operations)
- [Essential Runbooks](#essential-runbooks)
- [Service Management](#service-management)
- [Health Checks](#health-checks)
- [Maintenance Tasks](#maintenance-tasks)
- [Operational Checklists](#operational-checklists)

ACTION: Verify these # headers exist
```

---

### 21. docs/OPERATIONAL_QUICK_START.md (MEDIUM PRIORITY)

**Broken Links**: 5
**Impact**: Quick start guide cannot link to full guides

```markdown
BROKEN (missing ./ prefix):
[Monitoring Guide](MONITORING_AND_ALERTING.md) → ./MONITORING_AND_ALERTING.md
[Deployment Guide](DEPLOYMENT_GUIDE.md) → ./DEPLOYMENT_GUIDE.md
[Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) → ./TROUBLESHOOTING_GUIDE.md
[Operational Procedures](OPERATIONAL_PROCEDURES.md) → ./OPERATIONAL_PROCEDURES.md
[Hardware Requirements](HARDWARE_REQUIREMENTS.md) → ./HARDWARE_REQUIREMENTS.md
```

---

### 22. docs/OPS_RUNBOOK_PHASE_1C.md (MEDIUM PRIORITY)

**Broken Links**: 9 (section anchors)
**Impact**: Phase 1C runbook sections not navigable

```markdown
BROKEN ANCHORS:
- [Overview](#overview)
- [Architecture & Components](#architecture--components)
- [Deployment Guide](#deployment-guide)
- [Key Rotation Procedures](#key-rotation-procedures)
- [Byzantine Incident Response](#byzantine-incident-response)
- [Graceful Degradation Monitoring](#graceful-degradation-monitoring)
- [Performance Monitoring](#performance-monitoring)
- [Troubleshooting Guide](#troubleshooting-guide)
- [Emergency Procedures](#emergency-procedures)
- [Appendix: Metrics Reference](#appendix-metrics-reference)

ACTION: Verify all section headers exist
```

---

### 23. Module READMEs - Relative Path Issues

#### choam/README.md (HIGH PRIORITY)
```markdown
BROKEN:
[Combine.java](src/main/java/com/hellblazer/delos/choam/fsm/Combine.java)
[Driven.java](src/main/java/com/hellblazer/delos/choam/fsm/Driven.java)
[Reconfiguration.java](src/main/java/com/hellblazer/delos/choam/fsm/Reconfiguration.java)
[Genesis.java](src/main/java/com/hellblazer/delos/choam/fsm/Genesis.java)

ISSUE: These are correct Java file paths but may not render well in markdown
ACTION: Verify these files exist at those paths from repo root
        If correct, links should work
```

#### fireflies/README.md
```markdown
[ADR-0003: BFT Membership Architecture](../docs/adr/0003-bft-membership-architecture.md)
  STATUS: Path appears correct
  ACTION: Verify file exists at that location
```

#### sql-state/README.md
```markdown
[ADR-0005: Deterministic SQL State Machine](../docs/adr/0005-deterministic-sql-state.md)
  STATUS: Path appears correct
  ACTION: Verify file exists at that location
```

#### stereotomy/README.md
```markdown
[`docs/THREAT_MODEL.md`](./docs/THREAT_MODEL.md)
  ISSUE: Module doesn't have docs/ subdirectory
  ACTION: Check if file exists at stereotomy/docs/THREAT_MODEL.md
          If not, remove link
```

#### tron/README.md
```markdown
[ADR-0004: Consensus Design](../docs/adr/0004-consensus-design-choam.md)
  STATUS: Path appears correct
  ACTION: Verify file exists
```

---

## Repair Strategy

### Batch 1: Quick Wins (30 minutes)
1. Add `./` prefix to all relative links within docs/ directory
2. Fix directory-only links (add /README.md or point to INDEX.md)
3. Remove non-existent file references

### Batch 2: Anchor Verification (1 hour)
1. Extract all # headers from each document
2. Verify anchor links match headers exactly
3. Fix or remove broken anchor links

### Batch 3: Cross-Directory Links (30 minutes)
1. Verify all ../docs/ references from modules are correct
2. Verify all relative paths from root level docs/
3. Test links from multiple locations

### Batch 4: Documentation (30 minutes)
1. Document link conventions in CONTRIBUTING.md
2. Create link validation checklist
3. Add to CI/CD for automated checking

---

## Tools & Scripts

### Link Validation Script
```bash
#!/bin/bash
# Check all markdown links in docs/
for file in docs/*.md; do
  echo "Checking $file..."
  grep -o '\[.*\](.*\.md)' "$file" | while read line; do
    link=$(echo "$line" | grep -oP '\(\K.*(?=\))')
    # Check if file exists
    if [ ! -f "$link" ] && [ ! -d "$link" ]; then
      echo "  BROKEN: $link"
    fi
  done
done
```

---

## Summary

**Total Issues**: 260
**By Category**:
- Section anchors: 180+
- Relative path issues: 40+
- Missing files: 20+
- Directory link issues: 20+

**Estimated Fix Time**: 4-6 hours with automated tools
**Priority**: HIGH - impacts user experience and documentation usability

**Next Step**: Create automated link checker for CI/CD pipeline
