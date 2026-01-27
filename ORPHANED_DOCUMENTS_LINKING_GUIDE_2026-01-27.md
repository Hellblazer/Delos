# Orphaned Documents Linking Guide
**Date**: January 27, 2026
**Total Orphaned**: 37 documents with zero incoming references
**Critical Issues**: 10 high-value docs with no incoming links
**Estimated Impact**: Users missing 60% of documentation content

---

## What Are Orphaned Documents?

Documents with **zero incoming references** from other documentation files. This means:
- Users cannot discover them through standard documentation navigation
- Search engines may index them, but internal navigation is broken
- Critical guides are isolated from the rest of the documentation

**Note**: External links, GitHub links, and web search count as discovery paths, but documents should be discoverable within the documentation itself.

---

## Critical Orphaned Documents (Must Fix)

### Tier 1: Architecture & Design Foundation

#### 1. docs/ARCHITECTURE.md
**Status**: CRITICAL - No incoming references
**Topics**: System design, layers, abstractions, key components
**Should Be Referenced From**:
- `README.md` - Link in "Getting Started" section
- `docs/INDEX.md` - Main architecture section
- `CONTRIBUTING.md` - Required reading for contributors
- All module READMEs - For context on their role

**Current State**:
- Contains essential system architecture documentation
- Referenced externally but not from main documentation

**Recommended Links**:
```markdown
# In README.md (Getting Started section)
See [System Architecture](docs/ARCHITECTURE.md) for an overview of Delos'
distributed systems design, consensus protocols, and component architecture.

# In docs/INDEX.md
## Architecture & Design Foundation
- [System Architecture](./ARCHITECTURE.md) - High-level design, layers, abstractions
- [Architecture Decision Records](./adr/) - Design decisions and their rationale

# In each module README.md
[Back to Architecture Overview](../../docs/ARCHITECTURE.md)
```

---

#### 2. docs/DEVELOPER_QUICKSTART.md
**Status**: CRITICAL - No incoming references
**Topics**: Getting started, first steps, development environment
**Should Be Referenced From**:
- `README.md` - Link at top under "Quick Start" or "Getting Started"
- `docs/INDEX.md` - Developer Guide section
- `CONTRIBUTING.md` - For new contributors

**Current State**:
- Complete getting-started guide exists
- New developers have no way to discover it

**Recommended Links**:
```markdown
# In README.md (prominent position, near top)
## Quick Start
New to Delos? [Start here](docs/DEVELOPER_QUICKSTART.md) to set up your
development environment and run your first example.

# In docs/INDEX.md
## Developer Guide & Getting Started
- [Developer Quick Start](./DEVELOPER_QUICKSTART.md) - Setup, first steps, examples
```

---

#### 3. docs/SECURITY_THREAT_MODEL.md
**Status**: CRITICAL - No incoming references
**Topics**: Security threats, threat analysis, security assumptions
**Should Be Referenced From**:
- `docs/DEPLOYMENT_GUIDE.md` - Security considerations section
- `docs/OPERATIONAL_PROCEDURES.md` - Security operations
- `docs/CONFIGURATION_GUIDE.md` - Security configuration
- `docs/INDEX.md` - Security section

**Current State**:
- Critical security documentation exists
- Operations teams may not know about threat model

**Recommended Links**:
```markdown
# In docs/DEPLOYMENT_GUIDE.md (Security section)
See [Security Threat Model](./SECURITY_THREAT_MODEL.md) for threat analysis,
threat assumptions, and security implications of the deployment.

# In docs/OPERATIONAL_PROCEDURES.md
See [Security Threat Model](./SECURITY_THREAT_MODEL.md) for understanding
security assumptions behind operational procedures.
```

---

#### 4. docs/TESTING_GUIDE.md
**Status**: CRITICAL - No incoming references
**Topics**: Test strategies, Byzantine fault testing, patterns
**Should Be Referenced From**:
- `README.md` - Quality assurance section
- `CONTRIBUTING.md` - Required reading for contributors
- `docs/CODE_QUALITY_STANDARDS.md` - Testing requirements
- `docs/DEVELOPER_QUICKSTART.md` - Testing section

**Current State**:
- Comprehensive testing guide exists
- Contributors don't know what tests are required

**Recommended Links**:
```markdown
# In CONTRIBUTING.md (Testing section)
All contributions must include appropriate tests. See
[Testing Guide](docs/TESTING_GUIDE.md) for test categories, patterns,
and requirements.

# In docs/CODE_QUALITY_STANDARDS.md
[Testing Requirements](./TESTING_GUIDE.md) - Test categories, patterns, coverage requirements
```

---

#### 5. docs/CODE_QUALITY_STANDARDS.md
**Status**: CRITICAL - No incoming references
**Topics**: Code standards, patterns, conventions, guidelines
**Should Be Referenced From**:
- `README.md` - Development guidelines
- `CONTRIBUTING.md` - Code style requirements
- `docs/DEVELOPER_QUICKSTART.md` - Code standards section
- `docs/INDEX.md` - Developer resources

**Current State**:
- Complete code standards guide exists
- New developers don't know these standards

**Recommended Links**:
```markdown
# In CONTRIBUTING.md (Code Style section)
All code must follow [Code Quality Standards](docs/CODE_QUALITY_STANDARDS.md).
These standards cover design patterns, concurrency practices, error handling,
and code organization.

# In README.md (Development section)
### Code Quality
Follow our [Code Quality Standards](docs/CODE_QUALITY_STANDARDS.md) for
design patterns, concurrency practices, and error handling.
```

---

#### 6. docs/DEPLOYMENT_GUIDE.md
**Status**: CRITICAL - No incoming references
**Topics**: Deployment, production setup, operations
**Should Be Referenced From**:
- `README.md` - Operations section
- `docs/INDEX.md` - Operations guide main entry
- `docs/OPERATIONAL_PROCEDURES.md` - Pre-requisite reading
- `docs/CONFIGURATION_GUIDE.md` - Configuration for deployment

**Current State**:
- Critical deployment guide exists
- Operations teams can't find it from main docs

**Recommended Links**:
```markdown
# In README.md (Operations section)
## Deploying to Production
See [Deployment Guide](docs/DEPLOYMENT_GUIDE.md) for production deployment,
configuration, security hardening, and operational procedures.

# In docs/INDEX.md
## Operations & Deployment
- [Deployment Guide](./DEPLOYMENT_GUIDE.md) - Production deployment, security, operations

# In docs/OPERATIONAL_PROCEDURES.md
[See Deployment Guide](./DEPLOYMENT_GUIDE.md) for initial deployment,
configuration, and security hardening before starting operations.
```

---

#### 7. docs/INDEX.md
**Status**: CRITICAL - The index itself is orphaned!
**Topics**: Documentation index and overview
**Should Be Referenced From**:
- `README.md` - Link to full documentation
- `docs/` - Front page of docs directory
- Every docs/ file - Link back to index

**Current State**:
- Master documentation index exists
- No link from README to INDEX

**Recommended Links**:
```markdown
# In README.md (at top or in navigation)
## Documentation
Start with [Documentation Index](docs/INDEX.md) to browse all guides,
tutorials, and reference documentation.

# In every docs/ file (at top)
[← Back to Documentation Index](./INDEX.md)
```

---

### Tier 2: Operations & Deployment Guides

#### 8. docs/CONFIGURATION_GUIDE.md
**Should Be Referenced From**: DEPLOYMENT_GUIDE.md, OPERATIONAL_PROCEDURES.md
```markdown
# In DEPLOYMENT_GUIDE.md
See [Configuration Guide](./CONFIGURATION_GUIDE.md) for system configuration,
parameters, and common configuration scenarios.
```

---

#### 9. docs/OPERATIONAL_PROCEDURES.md
**Should Be Referenced From**: README.md, INDEX.md, DEPLOYMENT_GUIDE.md
```markdown
# In DEPLOYMENT_GUIDE.md
See [Operational Procedures](./OPERATIONAL_PROCEDURES.md) for day-to-day
operations, maintenance tasks, and health checks.
```

---

#### 10. docs/HARDWARE_REQUIREMENTS.md
**Should Be Referenced From**: DEPLOYMENT_GUIDE.md, CONFIGURATION_GUIDE.md
```markdown
# In DEPLOYMENT_GUIDE.md
[Hardware Requirements](./HARDWARE_REQUIREMENTS.md) - Sizing, capacity,
performance planning
```

---

## Secondary Orphaned Documents (Important)

### APIs & Integration

#### docs/API_REFERENCE.md
**Purpose**: API documentation and integration reference
**Should Link From**:
- docs/DEVELOPER_QUICKSTART.md - API reference section
- docs/INTEGRATION_PATTERNS.md - API usage

#### docs/INTEGRATION_PATTERNS.md
**Purpose**: Common integration patterns
**Should Link From**:
- docs/ARCHITECTURE.md - Application layer section
- docs/DEVELOPER_QUICKSTART.md - Integration examples

#### docs/TRANSACTION_FLOW_GUIDE.md
**Purpose**: Transaction processing walkthrough
**Should Link From**:
- docs/ARCHITECTURE.md - Data flow section
- docs/API_REFERENCE.md - Example flows

---

### Cryptography & Security

#### docs/CRYPTOGRAPHY_ALGORITHMS.md
**Purpose**: Cryptographic algorithms reference
**Should Link From**:
- docs/SECURITY_THREAT_MODEL.md - Cryptographic assumptions
- docs/KERI_INTEGRATION.md - KERI cryptography

#### docs/BLS_AGGREGATION_GUIDE.md
**Purpose**: BLS signature aggregation details
**Should Link From**:
- docs/CRYPTOGRAPHY_ALGORITHMS.md - BLS section
- docs/KERI_INTEGRATION.md - KERI key aggregation

#### docs/KERI_INTEGRATION.md
**Purpose**: KERI identity system integration
**Should Link From**:
- docs/ARCHITECTURE.md - Identity management section
- docs/SECURITY_THREAT_MODEL.md - Identity assumptions

#### docs/TLS_SETUP.md
**Purpose**: TLS/mTLS configuration
**Should Link From**:
- docs/DEPLOYMENT_GUIDE.md - Security section
- docs/SECURITY_THREAT_MODEL.md - TLS assumptions

---

### Operations & Monitoring

#### docs/MONITORING_GUIDE.md
**Purpose**: Monitoring and observability
**Should Link From**:
- docs/DEPLOYMENT_GUIDE.md - Monitoring section
- docs/OPERATIONAL_PROCEDURES.md - Monitoring tasks

#### docs/MONITORING_AND_ALERTING.md
**Purpose**: Metrics, alerts, dashboards
**Should Link From**:
- docs/DEPLOYMENT_GUIDE.md - Observability
- docs/OPERATIONAL_PROCEDURES.md - Health checks

#### docs/MONITORING_ALERTING.md
**Purpose**: Metrics collection and alerting
**Status**: DUPLICATE - Choose one to be canonical
**Action**: Consolidate with MONITORING_AND_ALERTING.md

#### docs/PERFORMANCE_TUNING.md
**Purpose**: Performance optimization guide
**Should Link From**:
- docs/DEPLOYMENT_GUIDE.md - Performance section
- docs/HARDWARE_REQUIREMENTS.md - Capacity planning

#### docs/TROUBLESHOOTING_GUIDE.md
**Purpose**: Incident response and troubleshooting
**Should Link From**:
- docs/DEPLOYMENT_GUIDE.md - Troubleshooting section
- docs/OPERATIONAL_PROCEDURES.md - Incident response

#### docs/DISASTER_RECOVERY.md
**Purpose**: Backup, restore, RTO/RPO planning
**Should Link From**:
- docs/DEPLOYMENT_GUIDE.md - Backup section
- docs/OPERATIONAL_PROCEDURES.md - Disaster recovery

#### docs/UPGRADE_PROCEDURES.md
**Purpose**: System upgrades, rolling updates
**Should Link From**:
- docs/DEPLOYMENT_GUIDE.md - Upgrade section
- docs/OPERATIONAL_PROCEDURES.md - Upgrade tasks

#### docs/OPS_RUNBOOK_PHASE_1C.md
**Purpose**: Phase 1C specific operations runbook
**Should Link From**:
- docs/INDEX.md - Phase 1C operations section
- docs/OPERATIONAL_PROCEDURES.md - Phase 1C reference

#### docs/PHASE_1C-3-A_KEY_ROTATION_OPERATIONS.md
**Purpose**: Key rotation procedures for Phase 1C
**Should Link From**:
- docs/OPS_RUNBOOK_PHASE_1C.md - Key rotation section
- docs/SECURITY_THREAT_MODEL.md - Key management

#### docs/OPERATIONAL_QUICK_START.md
**Purpose**: Quick start for operators
**Should Link From**:
- docs/DEPLOYMENT_GUIDE.md - Quick start section
- docs/INDEX.md - Operations entry point

#### docs/OPERATIONAL_CHECKLISTS.md
**Purpose**: Pre-flight, deployment, maintenance checklists
**Should Link From**:
- docs/DEPLOYMENT_GUIDE.md - Checklists section
- docs/OPERATIONAL_PROCEDURES.md - Checklist reference

---

### Documentation Maintenance

#### docs/GLOSSARY.md
**Purpose**: Term definitions and acronyms
**Should Link From**:
- docs/INDEX.md - Reference section
- docs/ARCHITECTURE.md - Key terms
- All other docs - Link when defining terms

#### docs/IDE_SETUP.md
**Purpose**: IDE configuration for development
**Should Link From**:
- docs/DEVELOPER_QUICKSTART.md - Development setup
- docs/INDEX.md - Developer tools section

#### docs/BUILD.md
**Purpose**: Build procedures and profiles
**Should Link From**:
- docs/DEVELOPER_QUICKSTART.md - Building section
- CLAUDE.md - Build commands

#### docs/KNOWLEDGE_CONSOLIDATION.md
**Purpose**: Documentation consolidation and knowledge base
**Should Link From**:
- docs/INDEX.md - Maintenance section

#### docs/MODULE_DOCUMENTATION_TEMPLATE.md
**Purpose**: Template for module README files
**Should Link From**:
- CONTRIBUTING.md - Documentation section
- docs/INDEX.md - Maintenance section

#### docs/MODULE_STANDARDIZATION_STATUS.md
**Purpose**: Module documentation standardization tracker
**Should Link From**:
- docs/INDEX.md - Project status section

#### docs/SECURITY_DOCUMENTATION_CONSOLIDATION.md
**Purpose**: Security documentation consolidation
**Should Link From**:
- docs/SECURITY_THREAT_MODEL.md - Related documentation

#### docs/TESTING_DOCUMENTATION_UPDATE_SUMMARY.md
**Purpose**: Testing documentation updates
**Should Link From**:
- docs/TESTING_GUIDE.md - Updates section

#### docs/ARCHITECTURE_UPDATE_SUMMARY.md (ROOT LEVEL)
**Purpose**: Architecture documentation updates
**Should Link From**:
- README.md - Documentation updates
- docs/ARCHITECTURE.md - Update history

---

## Root-Level Orphaned Documents

### AGENTS.md
**Purpose**: Agent definitions and roles
**Should Link From**:
- README.md - Development section
- CONTRIBUTING.md - Agent communication section

### BEADS.md
**Purpose**: Task tracking and bead management
**Should Link From**:
- README.md - Project management section
- CONTRIBUTING.md - Task tracking section

### CODE_QUALITY_UPDATE_SUMMARY.md
**Purpose**: Code quality standards updates
**Should Link From**:
- docs/CODE_QUALITY_STANDARDS.md - Update history
- CONTRIBUTING.md - Code standards link

---

## Implementation Plan

### Phase 1: Critical Links (Priority 1 - High Impact)

Create links for the 10 critical orphaned documents:

1. **README.md Updates**
   ```markdown
   ## Getting Started
   - [Developer Quick Start](docs/DEVELOPER_QUICKSTART.md)
   - [System Architecture](docs/ARCHITECTURE.md)

   ## Development
   - [Code Quality Standards](docs/CODE_QUALITY_STANDARDS.md)
   - [Contributing Guide](CONTRIBUTING.md)

   ## Testing
   - [Testing Guide](docs/TESTING_GUIDE.md)

   ## Operations
   - [Deployment Guide](docs/DEPLOYMENT_GUIDE.md)
   - [Security Threat Model](docs/SECURITY_THREAT_MODEL.md)

   ## Documentation
   - [Full Documentation Index](docs/INDEX.md)
   ```

2. **Update CONTRIBUTING.md**
   ```markdown
   Before contributing, please review:
   - [Code Quality Standards](docs/CODE_QUALITY_STANDARDS.md)
   - [Testing Guide](docs/TESTING_GUIDE.md)
   ```

3. **Update docs/INDEX.md**
   - Ensure all 46 docs/ files are listed
   - Organize by category
   - Add brief descriptions

### Phase 2: Secondary Links (Priority 2 - Medium Impact)

Link the secondary orphaned documents into INDEX.md and relevant primary documents.

### Phase 3: Module Cross-References (Priority 3 - Lower Impact)

Add cross-references between module READMEs:
- Each module should link to related modules
- Each module should link back to docs/ARCHITECTURE.md

---

## Link Statistics After Fixes

**Current State**:
- Orphaned documents: 37
- Well-referenced documents: 10

**After Phase 1 Fixes**:
- Orphaned documents: 15-20 (secondary docs)
- Well-referenced documents: 25-30

**After Phase 2 Fixes**:
- Orphaned documents: 5-10 (documentation maintenance only)
- Well-referenced documents: 35-40

**After Phase 3 Fixes**:
- Well-cross-referenced module ecosystem
- All critical docs discoverable from multiple paths

---

## Navigation Structure After Fixes

```
README.md (Entry Point)
├── docs/DEVELOPER_QUICKSTART.md
├── docs/ARCHITECTURE.md
├── CONTRIBUTING.md
├── docs/TESTING_GUIDE.md
├── docs/CODE_QUALITY_STANDARDS.md
├── docs/DEPLOYMENT_GUIDE.md
├── docs/SECURITY_THREAT_MODEL.md
└── docs/INDEX.md (Master Index)
    ├── [All 46 docs/ files organized by category]
    ├── Module READMEs (with cross-references)
    └── docs/GLOSSARY.md
```

---

## Testing Navigation Paths

After implementing links, verify these user journeys work:

1. **New Developer Path**:
   - README.md → DEVELOPER_QUICKSTART.md → ARCHITECTURE.md → Specific module

2. **Operations Path**:
   - README.md → DEPLOYMENT_GUIDE.md → OPERATIONAL_PROCEDURES.md → Specific procedures

3. **Contributor Path**:
   - README.md → CONTRIBUTING.md → CODE_QUALITY_STANDARDS.md + TESTING_GUIDE.md

4. **Security/Audit Path**:
   - README.md → SECURITY_THREAT_MODEL.md → Related configs

5. **Searcher Path**:
   - README.md → docs/INDEX.md → Topic of interest

---

## Recommended Markdown Pattern

Use this pattern for linking to orphaned documents:

```markdown
[Document Title](./path/to/document.md) - One-line description of content.
```

Example:
```markdown
See [Code Quality Standards](docs/CODE_QUALITY_STANDARDS.md) for design patterns,
concurrency practices, and error handling requirements.
```

---

## Success Criteria

Documentation is properly linked when:
- [ ] All critical docs (top 10) have 2+ incoming references
- [ ] README.md links to at least 10 different docs/ files
- [ ] docs/INDEX.md lists all 46 docs/ files
- [ ] Every module README links to related modules
- [ ] No user should need to manually navigate file systems to find docs
- [ ] All major topics are discoverable from README → INDEX path

---

**Status**: Ready for implementation
**Estimated Time**: 2-3 hours to implement all links
**Priority**: HIGH - Users cannot discover critical documentation
