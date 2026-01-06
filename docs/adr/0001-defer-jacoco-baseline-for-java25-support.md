# ADR-0001: Defer JaCoCo Baseline Measurement Until Java 25 Support Available

**Status**: ACCEPTED

**Date**: 2026-01-06

**Context**

During Phase 0 setup of the Delos Quality Initiative, we discovered that JaCoCo 0.8.12 (the latest stable version) does not support Java 25 bytecode format (major version 69). This was discovered while attempting to establish a coverage baseline as part of our comprehensive testing initiative.

**Available Options**:

1. **Temporary Java 21 Downgrade** (4-6 hours effort)
   - Modify pom.xml to compile/test with Java 21
   - Generate baseline metrics
   - Restore Java 25 for production work
   - Pros: Immediate baseline available; can start Phase 1a testing
   - Cons: Requires recompilation; introduces compilation variance between baseline and production

2. **Wait for JaCoCo 0.8.13+** (Expected Q1/Q2 2026)
   - JaCoCo team is actively working on Java 25 support
   - No workarounds or version changes needed
   - Pros: Clean solution; consistent bytecode baseline
   - Cons: Delays testing phase start by several months; baseline deferred

3. **Use Alternative Coverage Tool** (PIT, EMMA)
   - Evaluate alternative coverage tools with Java 25 support
   - Migrate JaCoCo configuration to alternative
   - Pros: Immediate coverage available
   - Cons: Tool switch increases maintenance burden; may introduce feature gaps

**Decision**

**We choose Option 2: Wait for JaCoCo 0.8.13+ to be released.**

**Rationale**

1. **Code Integrity**: Baseline metrics will reflect production bytecode (Java 25) rather than a temporary downgrade (Java 21)
2. **Tool Consistency**: JaCoCo is the industry standard; waiting for native support maintains consistency
3. **Timeline Flexibility**: Q1/Q2 2026 release is a reasonable timeline; Phase 1b (documentation/ADR work) proceeds in parallel
4. **Minimal Disruption**: No need to maintain a Java 21 variant or switch coverage tools
5. **Future-Proofing**: Other tools may have the same compatibility issue; waiting for native support is safer

**Consequences**

**Positive**:
- Clean technical decision without workarounds
- Baseline metrics reflect actual production bytecode (Java 25)
- No maintenance burden of Java version variants
- Phase 1b (Delos-pb1 documentation work) proceeds immediately

**Negative**:
- Phase 1a (Delos-6v5 testing work) deferred until JaCoCo 0.8.13 available
- Coverage baseline measurement delayed by ~3-4 months
- Testing phase start pushed to Q1/Q2 2026
- Must monitor JaCoCo releases and perform manual update when available

**Mitigation Strategies**:

1. **Parallel Progress**: Phase 1b (ADR creation, API documentation) proceeds independently
   - ADRs for Delos-pb1 can be created now
   - Production module documentation (fireflies, sql-state) can start immediately
   - No dependency on JaCoCo baseline

2. **Monitoring Plan**:
   - Check JaCoCo GitHub releases monthly
   - When 0.8.13+ released, immediately update pom.xml
   - Run baseline measurement in <1 day
   - Transition to Phase 1a (testing) without delay

3. **Alternative If Needed**:
   - If JaCoCo 0.8.13 delayed beyond Q2 2026, evaluate alternative tools
   - This ADR remains open for reconsideration if timeline slips significantly

**Related Decisions**

- ADR-0000: Use Markdown Architecture Decision Records (framework for this decision)
- QUALITY_INITIATIVE_PLAN.md: Phase 0 Contingency Procedures document three options

**Implementation**

1. Update CONTINUATION.md with decision (DONE)
2. Document in JACOCO_BASELINE_2026-01-06.md (DONE)
3. Monitor JaCoCo releases
4. When JaCoCo 0.8.13 released:
   - Update pom.xml: `<version>0.8.13</version>`
   - Run baseline: `./mvnw clean test && ./mvnw jacoco:report`
   - Generate JACOCO_BASELINE_2026-QX.md
   - Create sub-beads for Phase 1a testing work

**References**

- JaCoCo GitHub: https://github.com/jacoco/jacoco/releases
- Java 25 Bytecode Format: Major version 69
- JaCoCo 0.8.12 Release: March 2024 (before Java 25 release in September 2024)
- Quality Initiative Plan: `/Users/hal.hildebrand/git/Delos/.pm/plans/QUALITY_INITIATIVE_PLAN.md`

---

**Decision Made By**: Quality Initiative Phase 0 Team
**Last Updated**: 2026-01-06
**Status**: ACCEPTED - Proceeding with ADR creation in Phase 1b
