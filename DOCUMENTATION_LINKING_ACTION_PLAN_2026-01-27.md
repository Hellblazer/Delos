# Documentation Linking Action Plan
**Date**: January 27, 2026
**Status**: Ready for Implementation
**Total Tasks**: 45
**Estimated Time**: 6-8 hours

---

## Quick Stats

| Metric | Current | Target | Gap |
|--------|---------|--------|-----|
| Valid Internal Links | 130 | 250+ | +120 |
| Broken Links | 260 | <50 | -210 |
| Orphaned Docs | 37 | <10 | -27 |
| Link Validation Rate | 33% | 85%+ | +52% |
| Documentation Discoverability | Poor | Excellent | Transformed |

---

## Task Organization

### EPIC 1: Fix Critical Broken Links (Highest Priority)
**Effort**: 2-3 hours
**Impact**: Critical - Fixes navigation
**Target**: All broken links <50

#### Task 1.1: README.md Repairs (30 min)
**Files to Fix**: README.md
**Broken Links**: 10

```markdown
CHANGES:
1. Remove: [Knowledge Base](.pm/CONTINUATION.md)
   → Not needed if .pm/ doesn't exist

2. Fix: [**ADRs**](docs/adr/)
   → [**ADRs**](docs/adr/0000-use-markdown-architecture-decision-records.md)

3. Fix: [**Deployment Guides**](docs/)
   → [**Deployment Guides**](docs/INDEX.md)

4. Fix: [Stereotomy Services](stereotomy-services)
   → [Stereotomy Services](stereotomy-services/README.md)

5. Fix: [Modules section](#modules)
   → Verify this anchor exists, or link to docs/INDEX.md

6. Update "docs/" directory references to point to specific files
```

#### Task 1.2: CONTRIBUTING.md Repairs (30 min)
**Files to Fix**: CONTRIBUTING.md
**Broken Links**: 9

```markdown
CHANGES:
1. Either add all missing sections:
   - ## Getting Started
   - ## Development Setup
   - ## Code Style and Conventions
   - ## Testing Requirements
   - ## Commit Message Format
   - ## Branch Naming
   - ## Pull Request Process
   - ## Code Review
   - ## Issue Reporting

2. OR replace anchor links with cross-document links:
   - [Getting Started Guide](docs/DEVELOPER_QUICKSTART.md)
   - [Code Standards](docs/CODE_QUALITY_STANDARDS.md)
   - [Testing Requirements](docs/TESTING_GUIDE.md)

RECOMMENDATION: Add brief sections locally + cross-link to detailed docs
```

#### Task 1.3: docs/ Directory Path Standardization (45 min)
**Files to Fix**: 15 docs/ files
**Issue**: Missing `./` prefix for same-directory links

**Affected Files**:
- docs/ARCHITECTURE.md
- docs/BUILD.md
- docs/CODE_QUALITY_STANDARDS.md
- docs/CONFIGURATION_GUIDE.md
- docs/DEPLOYMENT_GUIDE.md
- docs/DEVELOPER_QUICKSTART.md
- docs/HARDWARE_REQUIREMENTS.md
- docs/IDE_SETUP.md
- docs/INDEX.md
- docs/KERI_INTEGRATION.md
- docs/MONITORING_AND_ALERTING.md
- docs/MONITORING_GUIDE.md
- docs/OPERATIONAL_CHECKLISTS.md
- docs/TLS_SETUP.md
- docs/UPGRADE_PROCEDURES.md

**Script to generate fixes**:
```bash
#!/bin/bash
# For each file in docs/, fix relative links

for file in docs/*.md; do
  # Replace pattern: ](FILENAME.md) with ](./FILENAME.md)
  sed -i '' 's/]\([^/]*\.md\)/](.\/\1/g' "$file"

  # Fix double prefixes: ](././filename) -> ](./filename)
  sed -i '' 's/](\.\.\//](.\/\.\.\//g' "$file"
done
```

#### Task 1.4: Section Anchor Verification (1 hour)
**Files to Fix**: 8 docs/ files
**Issue**: Section anchors don't match # headers

**Process**:
1. For each file, extract all `# Headers`
2. For each `[text](#anchor)` link, verify header exists
3. Fix mismatches or remove broken links

**Affected Files**:
- docs/ARCHITECTURE.md
- docs/BUILD.md
- docs/CODE_QUALITY_STANDARDS.md
- docs/DISASTER_RECOVERY.md
- docs/HARDWARE_REQUIREMENTS.md
- docs/INTEGRATION_PATTERNS.md
- docs/OPS_RUNBOOK_PHASE_1C.md
- docs/OPERATIONAL_PROCEDURES.md

---

### EPIC 2: Link Orphaned Critical Documents (High Priority)
**Effort**: 1-2 hours
**Impact**: High - Makes critical docs discoverable
**Target**: 10 critical docs with 2+ references each

#### Task 2.1: Update README.md with Key Links (45 min)
**File**: README.md
**Add Sections**:

```markdown
## Documentation Quick Links

### Getting Started
- [Developer Quick Start](docs/DEVELOPER_QUICKSTART.md) - Setup and first steps
- [System Architecture](docs/ARCHITECTURE.md) - Design overview
- [Code Quality Standards](docs/CODE_QUALITY_STANDARDS.md) - Coding guidelines

### For Operations & Deployment
- [Deployment Guide](docs/DEPLOYMENT_GUIDE.md) - Production deployment
- [Security Threat Model](docs/SECURITY_THREAT_MODEL.md) - Security overview
- [Operational Procedures](docs/OPERATIONAL_PROCEDURES.md) - Daily operations

### For Testing & Quality
- [Testing Guide](docs/TESTING_GUIDE.md) - Test requirements and patterns
- [Contributing Guide](CONTRIBUTING.md) - How to contribute

### Full Documentation
- [Documentation Index](docs/INDEX.md) - Complete guide to all documentation
```

#### Task 2.2: Update docs/INDEX.md Structure (45 min)
**File**: docs/INDEX.md
**Actions**:
1. Ensure all 46 docs/ files are listed
2. Organize by 6-8 main categories
3. Add 1-sentence description for each
4. Add back-links to README.md at top

**Structure**:
```markdown
# Documentation Index

[← Back to README](../README.md)

## Getting Started
- [Developer Quick Start](./DEVELOPER_QUICKSTART.md) - ...

## Architecture & Design
- [System Architecture](./ARCHITECTURE.md) - ...
- [Architecture Decision Records](./adr/) - ...

## Development
- [Code Quality Standards](./CODE_QUALITY_STANDARDS.md) - ...
- [Testing Guide](./TESTING_GUIDE.md) - ...
- [Build Guide](./BUILD.md) - ...

## Security
- [Security Threat Model](./SECURITY_THREAT_MODEL.md) - ...
- [TLS Setup](./TLS_SETUP.md) - ...
- [KERI Integration](./KERI_INTEGRATION.md) - ...

## Operations & Deployment
- [Deployment Guide](./DEPLOYMENT_GUIDE.md) - ...
- [Operational Procedures](./OPERATIONAL_PROCEDURES.md) - ...
- [Configuration Guide](./CONFIGURATION_GUIDE.md) - ...
- [Hardware Requirements](./HARDWARE_REQUIREMENTS.md) - ...
- [Monitoring & Alerting](./MONITORING_AND_ALERTING.md) - ...
- [Performance Tuning](./PERFORMANCE_TUNING.md) - ...
- [Troubleshooting Guide](./TROUBLESHOOTING_GUIDE.md) - ...
- [Disaster Recovery](./DISASTER_RECOVERY.md) - ...
- [Upgrade Procedures](./UPGRADE_PROCEDURES.md) - ...

## Reference & Glossary
- [Glossary](./GLOSSARY.md) - ...
- [API Reference](./API_REFERENCE.md) - ...

## Integration & Extension
- [Integration Patterns](./INTEGRATION_PATTERNS.md) - ...
- [Transaction Flow Guide](./TRANSACTION_FLOW_GUIDE.md) - ...
```

#### Task 2.3: Add Cross-Document Links in Deployment Stack (30 min)
**Files to Update**:
- docs/DEPLOYMENT_GUIDE.md
- docs/OPERATIONAL_PROCEDURES.md
- docs/CONFIGURATION_GUIDE.md

**Pattern**:
```markdown
# At top of each file:
[← Documentation Index](./INDEX.md)

# At relevant sections:
See [Code Quality Standards](./CODE_QUALITY_STANDARDS.md) for ...
See [Security Threat Model](./SECURITY_THREAT_MODEL.md) for ...
```

#### Task 2.4: Add Cross-Document Links in Architecture Stack (15 min)
**Files to Update**:
- docs/ARCHITECTURE.md
- docs/DEVELOPER_QUICKSTART.md

**Links to Add**:
```markdown
# docs/ARCHITECTURE.md - Add at end:
## See Also
- [Developer Quick Start](./DEVELOPER_QUICKSTART.md)
- [Code Quality Standards](./CODE_QUALITY_STANDARDS.md)
- [Testing Guide](./TESTING_GUIDE.md)
- [Deployment Guide](./DEPLOYMENT_GUIDE.md)

# docs/DEVELOPER_QUICKSTART.md - Add at top:
[← Back to Index](./INDEX.md)
```

---

### EPIC 3: Link Secondary Documents (Medium Priority)
**Effort**: 1 hour
**Impact**: Medium - Improves discoverability
**Target**: 15+ secondary docs discoverable

#### Task 3.1: Update TESTING_GUIDE.md Cross-References (20 min)
**File**: docs/TESTING_GUIDE.md

```markdown
# Add at top:
[← Documentation Index](./INDEX.md)

# Add at bottom:
## Related Documentation
- [Code Quality Standards](./CODE_QUALITY_STANDARDS.md)
- [Developer Quick Start](./DEVELOPER_QUICKSTART.md)
- [Build Guide](./BUILD.md)
```

#### Task 3.2: Update CODE_QUALITY_STANDARDS.md Cross-References (20 min)
**File**: docs/CODE_QUALITY_STANDARDS.md

```markdown
# Add at top:
[← Documentation Index](./INDEX.md)

# Add in relevant sections:
See [Testing Guide](./TESTING_GUIDE.md) for test requirements
```

#### Task 3.3: Link Operations Documentation Bundle (20 min)
**Files**: MONITORING_GUIDE.md, PERFORMANCE_TUNING.md, TROUBLESHOOTING_GUIDE.md, etc.

**Add to each**:
```markdown
[← Back to Index](./INDEX.md) | [← Deployment Guide](./DEPLOYMENT_GUIDE.md)
```

---

### EPIC 4: Module Cross-References (Lower Priority)
**Effort**: 1-2 hours
**Impact**: Medium - Helps module navigation
**Target**: Module READMEs cross-link related modules

#### Task 4.1: Map Module Dependencies (30 min)

**Module Relationship Map**:
```
Foundation Layer:
- cryptography → protocols, grpc
- grpc → everything (centralized definitions)
- protocols → (depends on grpc)

Identity & Security:
- stereotomy → gorgoneion, stereotomy-services, thoth
- gorgoneion → stereotomy, gorgoneion-client
- gorgoneion-client → gorgoneion
- thoth → stereotomy (DHT for keys)

Consensus & Membership:
- memberships → fireflies, choam
- fireflies → ethereal, choam, memberships
- ethereal → choam, fireflies

State Management:
- choam → sql-state, schemas
- sql-state → choam, schemas
- schemas → h2-deterministic, liquibase-deterministic

Application Layer:
- model → delphinius, tron
- delphinius → model
- tron → model
```

#### Task 4.2: Add Module Cross-References (1 hour)

**For each module README.md, add**:
```markdown
## Related Modules

### Depends On
- [Module X](../moduleX/README.md) - Description

### Used By
- [Module Y](../moduleY/README.md) - Description

## See Also
- [System Architecture](../docs/ARCHITECTURE.md)
- [Architecture Decision Records](../docs/adr/)
```

**Example for choam/README.md**:
```markdown
## Related Modules
### Depends On
- [Ethereal](../ethereal/README.md) - Byzantine fault tolerance consensus
- [SQL State](../sql-state/README.md) - Replicated state machine persistence
- [Schemas](../schemas/README.md) - Database schema definitions

### Used By
- [Model](../model/README.md) - Multi-tenant application framework
- Applications - Example: witness-service

## See Also
- [System Architecture](../docs/ARCHITECTURE.md#consensus-state-replication)
- [ADR-0004: Consensus Design](../docs/adr/0004-consensus-design-choam.md)
```

---

### EPIC 5: Consolidate Duplicates & Cleanup (Low Priority)
**Effort**: 30 min
**Impact**: Low - Reduces confusion
**Target**: Identify and consolidate duplicate docs

#### Task 5.1: Consolidate Monitoring Documentation (20 min)

**Issue**: Two very similar monitoring files:
- docs/MONITORING_GUIDE.md
- docs/MONITORING_AND_ALERTING.md
- docs/MONITORING_ALERTING.md (very similar to AND_ALERTING)

**Actions**:
1. Compare the three files
2. Choose authoritative version (likely MONITORING_AND_ALERTING.md)
3. Archive others with deprecation notice
4. Update all links to point to authoritative version

**Deprecation Template**:
```markdown
# DEPRECATED - Use [MONITORING_AND_ALERTING.md](./MONITORING_AND_ALERTING.md) instead

This document has been consolidated into MONITORING_AND_ALERTING.md
for maintainability. Please refer to that document for current information.

Last Updated: January 2026
```

#### Task 5.2: Archive Update Summary Documents (10 min)

**Files**:
- docs/ARCHITECTURE_UPDATE_SUMMARY.md
- docs/CODE_QUALITY_UPDATE_SUMMARY.md
- docs/TESTING_DOCUMENTATION_UPDATE_SUMMARY.md
- docs/SECURITY_DOCUMENTATION_CONSOLIDATION.md

**Actions**:
1. Verify information is in main documentation
2. Move to `docs/_archive/` subdirectory
3. Add link from relevant docs to archive if needed for reference

---

### EPIC 6: Validation & Documentation (Medium Priority)
**Effort**: 1 hour
**Impact**: High - Prevents future link issues

#### Task 6.1: Create Link Validation Script (30 min)

```bash
#!/bin/bash
# docs-validate-links.sh

echo "Validating markdown links..."
broken=0

for file in README.md CONTRIBUTING.md docs/*.md */README.md; do
  [ -f "$file" ] || continue

  # Extract markdown links
  while IFS= read -r line; do
    # Pattern: [text](path)
    if [[ $line =~ \[([^\]]+)\]\(([^\)]+)\) ]]; then
      url="${BASH_REMATCH[2]}"

      # Skip external links
      [[ $url =~ ^https?:// ]] && continue
      [[ $url =~ ^mailto: ]] && continue

      # Extract file path (remove anchor)
      filepath="${url%#*}"

      # Check if file exists
      if [ ! -z "$filepath" ] && [ ! -f "$filepath" ]; then
        echo "BROKEN: $file → [$url]"
        ((broken++))
      fi
    fi
  done < "$file"
done

echo "Found $broken broken links"
exit $((broken > 0 ? 1 : 0))
```

#### Task 6.2: Update CONTRIBUTING.md with Link Guidelines (20 min)

```markdown
# Add to CONTRIBUTING.md

## Documentation Link Guidelines

When linking between documents:

### Within docs/ directory
Use relative paths without `../`:
```markdown
[Reference](./OTHER_DOCUMENT.md)
[Section](#section-anchor)
```

### From root level to docs/
Use relative path:
```markdown
[Guide](docs/GUIDE.md)
```

### From modules to docs/
Use `../docs/`:
```markdown
[Architecture](../docs/ARCHITECTURE.md)
```

### Section Anchors
- Convert to lowercase
- Replace spaces with hyphens
- Remove special characters
- Example: `## My Section` → `#my-section`

### Adding Cross-References
Always add back-link:
```markdown
[← Back to Index](./INDEX.md)
```

### Verify Links
Run before committing:
```bash
./docs-validate-links.sh
```
```

#### Task 6.3: Add Link Validation to CI/CD (10 min)

**Create `.github/workflows/docs-links.yml`**:
```yaml
name: Validate Documentation Links

on: [pull_request]

jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Validate markdown links
        run: ./docs-validate-links.sh
```

---

## Task Execution Order

### Day 1: Critical Fixes (3-4 hours)
1. Task 1.1: README.md fixes (30 min)
2. Task 1.2: CONTRIBUTING.md fixes (30 min)
3. Task 1.3: docs/ path standardization (45 min)
4. Task 1.4: Section anchor verification (1 hour)

### Day 2: Link Critical Orphaned Docs (2-3 hours)
1. Task 2.1: README.md key links (45 min)
2. Task 2.2: INDEX.md structure (45 min)
3. Task 2.3: Deployment stack cross-refs (30 min)
4. Task 2.4: Architecture stack cross-refs (15 min)

### Day 3: Finish & Validate (1-2 hours)
1. Task 3.1-3.3: Secondary document links (1 hour)
2. Task 5.1-5.2: Consolidation & cleanup (30 min)
3. Task 6.1-6.3: Validation setup (1 hour)

---

## File-by-File Checklist

### ROOT LEVEL

- [ ] README.md - Fix 10 broken links, add key cross-refs
- [ ] CONTRIBUTING.md - Add sections or cross-links, fix anchors
- [ ] CLAUDE.md - No changes needed
- [ ] AGENTS.md - Add link from README
- [ ] BEADS.md - Add link from README
- [ ] ARCHITECTURE_UPDATE_SUMMARY.md - Archive to _archive/
- [ ] CODE_QUALITY_UPDATE_SUMMARY.md - Archive to _archive/

### docs/ DIRECTORY (46 files)

**Critical (Must Fix)**:
- [ ] docs/INDEX.md - Complete reorg, fix all anchors
- [ ] docs/ARCHITECTURE.md - Add cross-refs, fix anchors
- [ ] docs/DEPLOYMENT_GUIDE.md - Add cross-refs, fix links
- [ ] docs/DEVELOPER_QUICKSTART.md - Add cross-refs, fix links
- [ ] docs/TESTING_GUIDE.md - Add back-links
- [ ] docs/CODE_QUALITY_STANDARDS.md - Add back-links
- [ ] docs/SECURITY_THREAT_MODEL.md - Add cross-refs

**High Priority**:
- [ ] docs/BUILD.md - Fix path prefixes, verify anchors
- [ ] docs/CONFIGURATION_GUIDE.md - Fix path prefixes
- [ ] docs/HARDWARE_REQUIREMENTS.md - Fix path prefixes
- [ ] docs/OPERATIONAL_PROCEDURES.md - Fix path prefixes, anchors
- [ ] docs/MONITORING_AND_ALERTING.md - Fix path prefixes
- [ ] docs/OPS_RUNBOOK_PHASE_1C.md - Fix path prefixes, anchors

**Medium Priority** (40+ more docs):
- [ ] All other docs/ files - Add `./` prefixes, verify anchors

### MODULES (18 total)

- [ ] choam/README.md - Add module cross-refs
- [ ] ethereal/README.md - Add module cross-refs
- [ ] fireflies/README.md - Add module cross-refs
- [ ] stereotomy/README.md - Add module cross-refs
- [ ] sql-state/README.md - Add module cross-refs
- [ ] cryptography/README.md - Add module cross-refs
- [ ] delphinius/README.md - Add module cross-refs
- [ ] tron/README.md - Add module cross-refs
- [ ] (12 more modules) - Similar changes

---

## Success Metrics

After completing all tasks:

### Link Health
- [ ] Broken links: < 50 (from 260)
- [ ] Orphaned docs: < 10 (from 37)
- [ ] Validation rate: > 85% (from 33%)

### Discoverability
- [ ] README.md links to 15+ key docs
- [ ] INDEX.md lists all 46 docs with descriptions
- [ ] Every module has back-link to ARCHITECTURE.md
- [ ] All critical docs have 2+ incoming links

### Navigation
- [ ] New dev can reach all getting-started content from README
- [ ] Ops can reach all operational docs from DEPLOYMENT_GUIDE
- [ ] Security team can reach threat model from README
- [ ] Architect can reach all design docs from ARCHITECTURE

### Quality
- [ ] All anchor links verified to exist
- [ ] No missing relative path prefixes
- [ ] Duplicate docs consolidated or archived
- [ ] CI/CD validates links on PR

---

## Risk Mitigation

**Risk**: Broken links during refactoring
**Mitigation**:
- Make all changes in separate branch
- Validate after each EPIC
- Run link validator before merge

**Risk**: Accidental removal of important links
**Mitigation**:
- Keep old links while adding new ones
- Create deprecation guide for obsolete links
- Test reading paths after changes

**Risk**: Inconsistent link format
**Mitigation**:
- Establish link guidelines in CONTRIBUTING.md
- Add link validation to CI/CD
- Document patterns in CLAUDE.md

---

## Long-Term Maintenance

### Quarterly Tasks
- Run link validation script
- Check for new orphaned documents
- Verify README still reflects major changes
- Update INDEX.md descriptions if needed

### With Each Pull Request
- Validate new links with script
- Check cross-links when adding new docs
- Ensure links use consistent format

### Annual Review
- Compare docs to actual codebase
- Archive outdated documentation
- Refresh reading paths if architecture changes
- Consolidate related documents

---

## Summary

**Total Effort**: 6-8 hours
**Impact**: Transforms documentation from 33% discoverability to 85%+
**Audience**: All developers, operators, security teams
**Status**: Ready to implement
**Next Step**: Create PR with Phase 1 changes

---

**Report Generated**: January 27, 2026
**Prepared By**: Knowledge Tidier Agent
**Review Status**: Ready for Execution
