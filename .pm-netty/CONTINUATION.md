# Netty Native Library Elimination - Continuation Guide

**Project**: Netty Native Library Elimination
**Duration**: 3 weeks (2026-01-15 to 2026-02-05)
**Branch**: `feature/netty-elimination`
**Epic Bead**: Delos-1b9y
**Last Updated**: 2026-01-15
**Status**: Infrastructure Setup Complete - Ready to Start Phase 1

## Quick Start

### Before Starting Session
1. Pull latest `feature/netty-elimination` branch
2. Review `.pm-netty/execution_state.json` for current phase and blockers
3. Read relevant checkpoint files in `.pm-netty/checkpoints/`
4. Check blockers in `.pm-netty/RISK_REGISTER.md`

### Key Context
- **Problem**: Netty native libraries (libnetty_transport_native_kqueue/epoll) crash when loaded in both main JVM and GraalVM isolates (double-loading)
- **Solution**: Use JEP 380 pure-Java NIO domain sockets (Java 16+, Netty 4.1.110+)
- **Impact**: Delete 3 modules, update 5 modules, simplify build, fix isolates
- **Risk**: Terminal hangs from previous isolate testing - requires meticulous approach

## Current Phase Status

### Phase 1: Code Migration (READY TO START)
**Goal**: Replace all domain socket channel creation with NioEventLoopGroup/NioDomainSocketChannel

**Modules to Update**:
1. **protocols** - DomainSocketServerInterceptor initialization
2. **model** - DemesneImpl transport selection
3. **isolates** - Isolate-specific transport handling
4. **isolate-ftesting** - Test infrastructure
5. **memberships** - Enclave/Portal socket creation

**Test Strategy**:
- Run protocol tests first (no isolate involvement)
- Then test isolates with NIO transport
- Watch carefully for terminal hangs

**Success Gate**: Code compiles, all protocol tests pass, no terminal hangs

## Key Files and Locations

### Configuration
- **Execution State**: `.pm-netty/execution_state.json` (project metadata, phases, beads, blockers)
- **Risk Register**: `.pm-netty/RISK_REGISTER.md` (terminal hang risk, performance targets)
- **Methodology**: `.pm-netty/METHODOLOGY.md` (engineering discipline, testing approach)

### Knowledge Base
- **ChromaDB**: Search for `debug::isolates::netty-native-library-conflict` and `research::isolates::nio-domain-sockets-solution`
- **Memory Bank**: Check `delos_active/netty-elimination-findings.md`

### Checkpoints
- Created after completing major milestone
- Location: `.pm-netty/checkpoints/`
- Template: `.pm-netty/checkpoints/TEMPLATE-checkpoint.md`

### Progress Tracking
- **Beads**: All tasks tracked via `bd` (beads database)
- **Metrics**: `.pm-netty/metrics/progress.md` updated after each bead completion
- **Dependencies**: `.pm-netty/BEAD_DEPENDENCY_GRAPH.md` (task dependencies)

## Session Handoff Checklist

### Before Ending Session
- [ ] Update active bead status: `bd update <id> --status in_progress` or `bd close <id>`
- [ ] Create checkpoint: Copy `.pm-netty/checkpoints/TEMPLATE-checkpoint.md`, document progress
- [ ] Update metrics: Edit `.pm-netty/metrics/progress.md` with completion percentage
- [ ] Commit progress: `git add .pm-netty/checkpoints/, .pm-netty/metrics/, git commit`
- [ ] Document blockers: Update `.pm-netty/RISK_REGISTER.md` if new blockers discovered
- [ ] Save findings: Add to `delos_active/netty-elimination-findings.md` via Memory Bank

### Critical Context to Preserve
- **Current Phase**: Always note phase number in checkpoint
- **Last Successful Test**: Document which tests passed last
- **Terminal Hang Status**: Note any hang incidents and recovery
- **Performance Baseline**: Record before/after metrics for Phase 4

## Bead Workflow

### Check Ready Work
```bash
bd list --status=ready
```

### Start Work on a Bead
```bash
bd update <id> --status in_progress
```

### Complete a Bead
```bash
bd close <id>
```

### Add Dependencies
```bash
bd dep add <id> <blocker-id>
```

## Common Workflows

### Phase 1: Updating a Module
1. Select module (e.g., `protocols`)
2. Locate socket channel creation code
3. Replace `KQueueDomainSocketChannel` or `EpollDomainSocketChannel` with `NioDomainSocketChannel`
4. Replace transport selection with `NioEventLoopGroup`
5. Run: `./mvnw test -pl protocols -DskipTests=false`
6. If tests pass: Create checkpoint, update metrics
7. If tests fail: Debug, document findings, iterate

### Phase 2: Deleting Modules
1. Remove from parent pom.xml `<modules>` section
2. Verify parent compiles: `./mvnw compile -amd`
3. Delete directory: `rm -rf domain-kqueue domain-epoll domain-sockets`
4. Final verification: Full build `./mvnw clean install`

### Phase 3: Metadata Cleanup
1. Delete isolates `resource-config.json` if present
2. Review isolates `pom.xml` - remove native library references
3. Update GraalVM build (may require re-profiling)

### Phase 4: Testing
1. Run protocol tests (low risk): `./mvnw test -pl protocols`
2. Run integration tests: `./mvnw test -pl model`
3. Run isolate tests (high risk): `./mvnw test -pl isolate-ftesting -Pisolates` (with extreme care)
4. Benchmark before/after (record in metrics)

## Critical Risk: Terminal Hangs

**What Happened Before**: Isolate testing caused terminal to hang (unresponsive, requires kill -9)

**Prevention Strategy**:
- Test with timeout: `timeout 30s ./mvnw test -pl isolate-ftesting`
- Use minimal reproducers, not full test suite
- Log to file only (no console output during risky tests)
- Have recovery script ready (if terminal hangs: Ctrl+C, then `killall -9 java`)

**If Terminal Hangs**:
1. In another terminal: `killall -9 java`
2. Wait 5 seconds for cleanup
3. Restart session
4. Review what test caused hang
5. Document in checkpoint

## Performance Expectations

- **Expected Degradation**: < 10% (acceptable: < 20%)
- **Benchmark Tool**: Use `ChurnTest` or similar under time profiler
- **Before Baseline**: Record before starting Phase 1
- **After Baseline**: Record after Phase 4

## Success Criteria Summary

**Phase 1**: All 5 modules updated, code compiles, protocol tests pass
**Phase 2**: 3 modules deleted, parent pom.xml cleaned
**Phase 3**: Native metadata removed, GraalVM config updated
**Phase 4**: ALL tests pass, terminal stable, < 20% perf degradation, isolates work

## Next Steps (From Here)

1. Review Phase 1 modules: protocols, model, memberships
2. Locate socket channel creation code in each module
3. Create initial checkpoints documenting baseline
4. Start with protocols (safest module)
5. Progress to model, memberships, isolates
6. Watch carefully for terminal hangs

## Emergency Contacts / Recovery

**If Stuck**: Check `.pm-netty/RISK_REGISTER.md` for known blockers and mitigations
**If Terminal Hangs**: See "Critical Risk: Terminal Hangs" section above
**If Major Blocker**: Document in `.pm-netty/RISK_REGISTER.md` and escalate via ChromaDB

## Session Duration Estimate
- **Phase 1 per module**: 2-4 hours
- **Phase 2**: 1-2 hours
- **Phase 3**: 1 hour
- **Phase 4**: 2-4 hours (test execution time)

**Total**: 3 weeks for one engineer
