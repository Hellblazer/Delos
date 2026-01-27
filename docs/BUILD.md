# Delos Build Guide

**Document Version**: 2.0
**Date**: 2026-01-27
**Status**: Production
**Audience**: Developers, DevOps engineers, CI/CD operators

---

## Quick Start

```bash
# First-time setup (builds deterministic SQL module)
./mvnw clean install -Ppre -DskipTests

# Standard build (all modules)
./mvnw clean install

# Single module with dependencies
./mvnw install -amd -pl delos-fireflies

# Run all tests
./mvnw test

# Full test suite (resource-intensive, 8+ GB RAM)
./mvnw test -Dlarge_tests=true
```

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Build Profiles](#build-profiles)
3. [Standard Build Procedures](#standard-build-procedures)
4. [Advanced Builds](#advanced-builds)
5. [Test Execution](#test-execution)
6. [Code Generation](#code-generation)
7. [Troubleshooting Build Issues](#troubleshooting-build-issues)
8. [CI/CD Integration](#cicd-integration)

---

## Prerequisites

### Required Tools

| Tool | Version | Purpose | Install |
|------|---------|---------|---------|
| **Java** | 25+ | Primary language (REQUIRED for production) | `java -version` |
| **Maven** | 3.9.3+ | Build automation | `mvn --version` |
| **Git** | 2.0+ | Version control | `git --version` |
| **Protobuf Compiler** | 4.28.2+ | gRPC definitions (optional, Maven-generated) | `protoc --version` |

### Optional Tools

- **GraalVM** (for isolates profile): Required for `-Pisolates` builds
- **Docker/Docker Compose**: For local cluster testing
- **Prometheus/Grafana**: For monitoring builds

### Java Version Requirements

**Production Requirement**: Java 25+ is REQUIRED. Do not deploy with earlier versions.

```bash
# Check Java version
java -version

# Set JAVA_HOME if needed
export JAVA_HOME=/path/to/java25
export PATH=$JAVA_HOME/bin:$PATH
```

### Maven Configuration

Maven uses a wrapper script (`mvnw`) for consistent builds. No separate Maven installation needed.

```bash
# Check Maven version via wrapper
./mvnw --version

# Maven requires 512MB+ heap for large builds
export MAVEN_OPTS="-Xmx2g -Xms1g"
```

---

## Build Profiles

Delos uses Maven profiles to control which components are built. Select the appropriate profile for your use case.

### Profile: `default` (Recommended for Development)

**When to use**: Local development, testing, pre-deployment validation

**What builds**:
- Core Delos modules (fireflies, ethereal, choam, etc.)
- Cryptography (BLS, signatures)
- Identity (stereotomy, KERI)
- State machines (SQL-state)
- Testing utilities
- Documentation

**What doesn't build**:
- GraalVM isolates (requires GraalVM)
- Native image builds

**Build command**:
```bash
./mvnw clean install
```

**Build time**: 8-15 minutes (depends on machine specs)

**Example use**: Pre-deployment validation, local development, CI/CD test runs

---

### Profile: `pre` (First-Time Setup)

**When to use**: ONLY on first build after cloning repository

**What builds**:
- Deterministic H2 database module (`h2-deterministic`)
- Liquibase deterministic module
- All default profile components

**Why needed**:
- The `h2-deterministic` module uses package shading
- Must be built and installed to local Maven repository before other modules can reference it
- Failure to run this profile on first clone will cause "h2-deterministic not found" errors

**Build command**:
```bash
./mvnw clean install -Ppre -DskipTests
```

**Build time**: 15-25 minutes (includes deterministic module compilation)

**Post-build verification**:
```bash
# Check that deterministic module was installed
ls ~/.m2/repository/com/hellblazer/h2-deterministic/

# Should show version directories (e.g., 0.2.3-SNAPSHOT)
```

**Important Notes**:
- Should NOT import `h2-deterministic` module into IDEs (package shading incompatible)
- Do NOT run `-Ppre` repeatedly; once per clone is sufficient
- If you see "h2-deterministic not found" error, run this profile

---

### Profile: `isolates` (GraalVM Isolates)

**When to use**: Multi-tenant deployments requiring isolation, production with strict resource constraints

**What builds**:
- All default profile components
- GraalVM isolate infrastructure
- Native image agent configuration
- Isolate-based multi-tenant enclaves

**Requirements**:
- GraalVM distribution (not standard OpenJDK)
- GRAALVM_HOME environment variable set
- 6+ GB disk space (native image compilation)
- 8+ GB RAM during build

**Build command**:
```bash
# Set GraalVM environment
export GRAALVM_HOME=/path/to/graalvm
export PATH=$GRAALVM_HOME/bin:$PATH

# Build with isolates profile
./mvnw clean install -Pisolates

# Or with pre profile for first build
./mvnw clean install -Ppre -Pisolates -DskipTests
```

**Build time**: 20-40 minutes (includes native image compilation)

**Use cases**:
- Secure multi-tenant environments
- Reduced memory footprint (native images)
- Cold-start time optimization

**Verification**:
```bash
# Check that isolate module was built
ls isolates/target/isolates-*.jar

# Run isolates tests
./mvnw test -Pisolates -pl isolates
```

---

### Profile: `agent` (Native Image Agent Configuration)

**When to use**: Building configuration for GraalVM native image generation

**What builds**:
- Native image agent instrumentation
- Reflection configuration collection
- Resource discovery

**Build command**:
```bash
./mvnw -Pnative-agent test -pl <module>

# Example: Run agent on fireflies tests
./mvnw -Pnative-agent test -pl fireflies
```

**Output**:
- Generated config files in `src/main/resources/META-INF/native-image/`
- Includes `reflect-config.json`, `resource-config.json`, etc.

---

## Standard Build Procedures

### Procedure 1: Initial Project Setup

**Use case**: First-time clone and build setup

**Steps**:

```bash
# 1. Clone repository
git clone https://github.com/Hellblazer/delos.git
cd delos

# 2. Set Maven memory
export MAVEN_OPTS="-Xmx2g -Xms1g"

# 3. Run first-time setup (builds deterministic SQL module)
./mvnw clean install -Ppre -DskipTests

# 4. Verify build succeeded
echo "h2-deterministic module installed: $(test -d ~/.m2/repository/com/hellblazer/h2-deterministic && echo YES || echo NO)"

# 5. Run full test suite to validate
./mvnw test -DskipIntegrationTests=true
```

**Success criteria**:
- All modules compile
- No "h2-deterministic not found" errors
- Test suite passes

**Troubleshooting**:
- If step 3 fails, see "Troubleshooting Build Issues"
- If tests fail, check network connectivity and disk space

---

### Procedure 2: Clean Development Build

**Use case**: Regular development workflow

**Steps**:

```bash
# 1. Clean previous build artifacts
./mvnw clean

# 2. Build all modules (incremental compilation)
./mvnw install

# 3. Run specific module tests
./mvnw test -pl fireflies -DskipIntegrationTests=true
```

**Build time**: 8-15 minutes (full) or 3-5 minutes (incremental after changes)

**Tips**:
- Use `./mvnw install` instead of `./mvnw compile` (modules depend on each other)
- Use `-amd` flag to build dependencies: `./mvnw install -amd -pl <module>`
- Use `./mvnw -T 1C` for single-threaded builds if you encounter race conditions

---

### Procedure 3: Build Single Module

**Use case**: Developing in one module, avoid full rebuild

**Steps**:

```bash
# Build one module and its dependencies
./mvnw install -amd -pl choam

# Run only that module's tests
./mvnw test -pl choam

# Run specific test class
./mvnw test -pl choam -Dtest=CHOAMTest

# Run specific test method
./mvnw test -pl choam -Dtest=CHOAMTest#shouldCommitBlocks
```

**Flags explained**:
- `-amd`: Also-make-dependents (builds dependencies and dependents)
- `-pl <module>`: Project list (specify which module)
- `-Dtest=ClassName`: Target test class

---

### Procedure 4: Generate Protocol Buffers

**Use case**: After modifying `.proto` files in `src/main/proto/`

**Steps**:

```bash
# Generate all proto sources
./mvnw generate-sources

# Or for single module
./mvnw generate-sources -pl grpc
```

**Output location**: `target/generated-sources/protobuf/`

**When needed**:
- After editing `.proto` files
- After importing new proto definitions
- If IDE shows "cannot resolve symbol" for proto classes

**Verification**:
```bash
# Check generated Java files
find target/generated-sources -name "*.java" | head -5
```

---

### Procedure 5: Generate JOOQ Database Classes

**Use case**: After modifying Liquibase database schemas

**Steps**:

```bash
# Generate JOOQ classes from database schema
./mvnw generate-sources -pl sql-state

# Or regenerate with clean
./mvnw clean generate-sources -pl sql-state
```

**Output location**: `sql-state/target/generated-sources/jooq/`

**When needed**:
- After modifying `src/main/resources/db/` changesets
- After schema migrations
- If IDE shows "cannot find symbol" for table/column classes

---

## Advanced Builds

### Build with Custom Properties

```bash
# Override Java version requirement (not recommended for production)
./mvnw install -Djava.version=21

# Set custom artifact repository
./mvnw install -DaltReleaseDeploymentRepository=myrepo::default::https://myrepo.com/releases

# Build without running pre-integration test checks
./mvnw install -Denforcer.skip=true
```

### Build Performance Optimization

**For slower machines or large clusters (13+ nodes)**:

```bash
# Single-threaded build (avoids concurrency issues)
./mvnw -T 1 clean install

# Build with reduced memory for slower systems
MAVEN_OPTS="-Xmx1g -Xms512m" ./mvnw clean install

# Skip tests for faster builds (verify separately)
./mvnw clean install -DskipTests
```

**For fast CI/CD pipelines**:

```bash
# Multi-threaded build with 2 threads per core
./mvnw -T 2C clean install

# Parallel test execution
./mvnw test -P parallel-tests

# Use offline mode (if dependencies already cached)
./mvnw install -o
```

### Build with Local Repository

```bash
# Use alternative local repository
mvn install -Dmaven.repo.local=/custom/repo/path

# List artifact with custom repo
find /custom/repo/path -name "*.jar" | grep delos
```

---

## Test Execution

### Test Categories

**Unit Tests**: Run by default with `./mvnw test`

```bash
# Run all unit tests
./mvnw test

# Run tests for single module
./mvnw test -pl fireflies

# Run specific test class
./mvnw test -Dtest=ViewTest

# Run tests matching pattern
./mvnw test -Dtest="*Integration*"
```

**Large/Integration Tests**: Resource-intensive, opt-in

```bash
# Full test suite (requires 8+ GB RAM, 30-60 minutes)
./mvnw test -Dlarge_tests=true

# Only large tests
./mvnw test -Dtest="*Large*" -Dlarge_tests=true
```

**Test with Custom Heap**:

```bash
# Increase heap for memory-intensive tests
./mvnw test -DargLine="-Xmx10G -Xms4G"

# Run with GC logging
./mvnw test -DargLine="-Xmx8G -XX:+PrintGCDetails -XX:+PrintGCDateStamps"
```

### Test Configuration

**Dynamic Port Allocation** (built-in):
- Tests use port 0 (OS assigns random port)
- Avoids port conflicts between test runs
- No special configuration needed

**Test Isolation**:
- Each test runs independently
- Can run in any order (no dependencies)
- Parallel execution supported

---

## Code Generation

### Automatic Generation

These are automatically generated during `mvn compile`:

- **Protobuf/gRPC**: From `.proto` files in `src/main/proto/`
- **JOOQ**: From Liquibase schemas and database
- **Source shading**: For deterministic modules

**Clean generated sources**:

```bash
# Remove all generated code
./mvnw clean

# Regenerate on next build
./mvnw compile
```

### Manual Generation

**Generate only (without compiling)**:

```bash
./mvnw generate-sources
```

**Output directories** (for inspection):
- Proto: `target/generated-sources/protobuf/`
- JOOQ: `target/generated-sources/jooq/`

---

## Troubleshooting Build Issues

### Issue: "h2-deterministic not found"

**Symptom**:
```
[ERROR] Could not find artifact com.hellblazer:h2-deterministic:jar:0.2.3-SNAPSHOT
```

**Solution**:

```bash
# Run first-time setup profile
./mvnw clean install -Ppre -DskipTests

# Verify installed
ls ~/.m2/repository/com/hellblazer/h2-deterministic/
```

**Root cause**: Deterministic module must be installed to local Maven repo before others can reference it.

---

### Issue: "Cannot resolve symbol" for proto classes

**Symptom**: IDE shows "cannot resolve" for classes in proto packages

**Solution**:

```bash
# Generate proto sources
./mvnw generate-sources

# Refresh IDE (IntelliJ: File → Invalidate Caches → Restart)
```

**Root cause**: IDE hasn't discovered generated sources.

---

### Issue: "dependencyConvergence" build failure

**Symptom**:
```
[ERROR] Dependency convergence error for ...
[ERROR] Multiple different versions of dependency X
```

**Solution**:

```bash
# View dependency tree
./mvnw dependency:tree -DoutputFile=deps.txt

# Add <dependencyManagement> entry to parent pom.xml for conflicting versions
# See: <parent>/pom.xml in project root
```

**Root cause**: Transitive dependencies pulled in different versions.

---

### Issue: "Port already in use" during tests

**Symptom**:
```
[ERROR] Address already in use: :50051
```

**Solution**:

```bash
# Tests use dynamic ports, but previous test may not have released
# Wait 10-30 seconds and retry

./mvnw test -Dtest=ClassName

# Or find and kill hanging processes
lsof -i :50051 | grep -v PID | awk '{print $2}' | xargs kill -9
```

**Prevention**: Ensure previous test batch completed.

---

### Issue: OutOfMemory during build

**Symptom**:
```
[ERROR] OutOfMemoryError: Java heap space
```

**Solution**:

```bash
# Increase heap
export MAVEN_OPTS="-Xmx4g -Xms2g"

# Rebuild
./mvnw clean install
```

**For different systems**:
- Slow machines: 2GB heap (`-Xmx2g`)
- Standard machines: 4GB heap (`-Xmx4g`)
- Large tests: 10GB heap (`-Xmx10g`)

---

### Issue: Network errors downloading dependencies

**Symptom**:
```
[ERROR] Could not transfer artifact ... Failed to transfer file
```

**Solution**:

```bash
# Verify network connectivity
ping maven.apache.org

# Try offline mode (if dependencies cached)
./mvnw install -o

# Clear Maven cache and retry
rm -rf ~/.m2/repository
./mvnw clean install
```

---

### Issue: Git conflicts in generated files

**Symptom**: Merge conflicts in `target/generated-sources/`

**Solution**:

```bash
# Add generated sources to .gitignore (should already be there)
echo "target/generated-sources/" >> .gitignore

# Clean and regenerate after merge
./mvnw clean generate-sources
```

---

## CI/CD Integration

### GitHub Actions Workflow Example

```yaml
name: Maven Build

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        java-version: [21, 25]
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: ${{ matrix.java-version }}
          distribution: 'temurin'

      # First-time setup
      - name: Setup (Deterministic Modules)
        run: ./mvnw clean install -Ppre -DskipTests

      # Build
      - name: Build
        run: ./mvnw clean install

      # Tests
      - name: Run Tests
        run: ./mvnw test
        if: success()
```

### Maven Build Cache (CI Optimization)

```bash
# Save Maven cache between runs
- name: Cache Maven packages
  uses: actions/cache@v3
  with:
    path: ~/.m2/repository
    key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
    restore-keys: |
      ${{ runner.os }}-maven-
```

### Local Development with Maven Wrapper

```bash
# Maven wrapper automatically downloads correct Maven version
./mvnw --version

# Ensures everyone uses same Maven version
# Update wrapper with: ./mvnw wrapper:wrapper -Dmaven.wrapper.version=3.9.3
```

---

## Summary

| Use Case | Command | Duration |
|----------|---------|----------|
| First clone | `./mvnw clean install -Ppre -DskipTests` | 15-25 min |
| Daily development | `./mvnw clean install` | 8-15 min |
| Single module | `./mvnw install -amd -pl <module>` | 2-5 min |
| Run tests | `./mvnw test` | 10-20 min |
| Full test suite | `./mvnw test -Dlarge_tests=true` | 30-60 min |
| GraalVM isolates | `./mvnw clean install -Pisolates` | 20-40 min |
| Pre-deployment | `./mvnw clean install && ./mvnw test` | 25-35 min |

---

## Related Documentation

- [Deployment Guide](DEPLOYMENT_GUIDE.md) - Running built artifacts
- [Architecture Overview](ARCHITECTURE.md) - Module structure
- [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) - Runtime issues
- [CLAUDE.md](../CLAUDE.md) - Project-specific build directives
