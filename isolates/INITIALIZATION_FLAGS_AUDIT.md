# GraalVM Initialization Flags Audit

**Date**: 2026-01-15
**Context**: Comprehensive review of all `--initialize-at-build-time` flags in isolates/pom.xml
**Trigger**: Static initialization timing mismatch causing DemesneImpl to freeze platform-native transport

## Current Configuration

### macOS Profile (os-x)
| Flag | Class | Type | Lines |
|------|-------|------|-------|
| `--initialize-at-build-time` | `com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor` | Build-time | 151 |
| `--initialize-at-build-time` | `javax.sql.rowset.RowSetProvider` | Build-time | 152 |
| `--initialize-at-build-time` | `com.hellblazer.delos.comm.grpc.DomainSocketsOSX` | Build-time | 153 |
| `--initialize-at-run-time` | `org.h2.store.fs.FilePath` | Runtime | 154 |

### Linux Profile
| Flag | Class | Type | Lines |
|------|-------|------|-------|
| `--initialize-at-build-time` | `com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor` | Build-time | 214 |
| `--initialize-at-build-time` | `javax.sql.rowset.RowSetProvider` | Build-time | 215 |
| `--initialize-at-build-time` | `com.hellblazer.delos.comm.grpc.DomainSocketsLinux` | Build-time | 216 |
| `--initialize-at-run-time` | `org.h2.store.fs.FilePath` | Runtime | 217 |

## Historical Context

**Origin**: Added in commit `b4f4a176` by Constantine on Jan 12, 2025 ("aot work")
**Recent Change**: Linux profile added in commit `325aec0f` on Jan 14, 2026 (Phase 1: Isolates restoration)

**Note**: These flags were likely added during initial GraalVM native-image setup without considering the hybrid architecture requirement (platform-native for main JVM, NIO for isolates).

## Flag-by-Flag Analysis

### 1. DomainSocketServerInterceptor

**Purpose**: Server interceptor that extracts peer credentials from domain socket connections

**Static Initialization**:
```java
private static final String OS = System.getProperty("os.name").toLowerCase();
public static final DomainSockets IMPL = configure();

static DomainSockets configure() {
    if (isMac()) {
        return configureMac();  // Returns new DomainSocketsOSX()
    } else {
        return configureLunux(); // Returns new DomainSocketsLinux()
    }
}
```

**Problem**:
- Creates static `IMPL` field with platform-native transport at build time
- DemesneImpl references this via `import static ...DomainSocketServerInterceptor.IMPL`
- Triggers transitive initialization of DemesneImpl's static block
- At build time, `delos.isolate.mode` property is not set
- Therefore freezes platform-native transport into native image

**Usage**:
- Used in DemesneImpl.java (static import)
- Used as server interceptor in GRPC server setup

**Recommendation**: **REMOVE** - Move to runtime initialization
- **Why**: The OS detection and transport selection should happen at runtime
- **Impact**: Minimal - interceptor initialization is fast
- **Benefit**: Allows DemesneImpl to make correct runtime decision about transport

**Risk**: LOW - Runtime initialization is the GraalVM default and safer

---

### 2. javax.sql.rowset.RowSetProvider

**Purpose**: Java SQL standard class for loading RowSet implementations via ServiceLoader

**Static Initialization**: Uses `java.util.ServiceLoader` to discover implementations

**Problem**: None - this is a legitimate use case

**Usage**:
- `SqlStateMachine.java`: `factory = RowSetProvider.newFactory();`
- ServiceLoader pattern requires build-time initialization for native images

**Recommendation**: **KEEP** - Legitimate build-time initialization
- **Why**: ServiceLoader discovery must happen during native-image build
- **Impact**: Required for SQL state machine functionality
- **GraalVM Docs**: ServiceLoader-based classes should be initialized at build time

**Risk**: NONE - Correct usage per GraalVM guidelines

---

### 3. DomainSocketsOSX / DomainSocketsLinux

**Purpose**: Platform-native implementations of Unix domain socket abstractions

**Static Initialization**:
```java
// DomainSocketsOSX.java
private static final KQueueEventLoopGroup serverBossGroup = new KQueueEventLoopGroup(1);
private static final KQueueEventLoopGroup serverWorkerGroup = new KQueueEventLoopGroup();
```

**Problem**: **CRITICAL ARCHITECTURAL VIOLATION**
- These are marked `<scope>provided</scope>` in isolates/pom.xml (lines 27, 31)
- They represent platform-native transports (KQueue/Epoll)
- **But the isolates module is supposed to use NIO transport only!**
- Build-time initialization forces these native implementations into the image
- Contradicts the hybrid architecture: native for main JVM, NIO for isolates

**Usage**:
- Indirectly via DomainSocketServerInterceptor.IMPL
- Should NOT be in the isolates native image at all

**Recommendation**: **REMOVE** - Must NOT be build-time initialized
- **Why**: Defeats the purpose of NIO-only isolates
- **Impact**: Critical - this is the root cause of the issue
- **Benefit**: Isolates will correctly use NIO, main JVM will use platform-native
- **Additional**: Consider removing these dependencies entirely from isolates module

**Risk**: NONE - Removing incorrect configuration

---

### 4. org.h2.store.fs.FilePath

**Purpose**: H2 Database file system abstraction

**Configuration**: `--initialize-at-run-time` (CORRECT)

**Rationale**: H2 file paths must be initialized at runtime to:
- Respect runtime file system state
- Allow different paths per isolate
- Prevent build-time paths from being frozen

**Recommendation**: **KEEP** - Correct runtime initialization

**Risk**: NONE - Already correct

---

## Summary of Recommendations

| Class | Current | Recommended | Action | Priority |
|-------|---------|-------------|--------|----------|
| `DomainSocketServerInterceptor` | Build-time | Runtime | REMOVE flag | P0 - Critical |
| `RowSetProvider` | Build-time | Build-time | KEEP flag | - |
| `DomainSocketsOSX/Linux` | Build-time | Runtime | REMOVE flag | P0 - Critical |
| `FilePath` | Runtime | Runtime | KEEP flag | - |

## Root Cause Chain

```
1. DomainSocketsOSX/Linux marked --initialize-at-build-time
   ↓
2. DomainSocketServerInterceptor marked --initialize-at-build-time
   ↓
3. DomainSocketServerInterceptor.IMPL = new DomainSocketsOSX() [frozen at build]
   ↓
4. DemesneImpl static imports IMPL
   ↓
5. DemesneImpl.static { ... } runs during native-image analysis
   ↓
6. boolean inIsolate = Boolean.getBoolean("delos.isolate.mode") [FALSE at build time]
   ↓
7. domainSockets = IMPL [frozen platform-native transport]
   ↓
8. channelFactory = domainSockets.getChannelFactory() [frozen platform-native factory]
   ↓
9. GraalVM heap snapshot captures all these values
   ↓
10. At runtime: System property set to true (TOO LATE - static init already done)
   ↓
11. Result: NIO code never runs, crashes with native library conflicts
```

## Implementation Plan

### Phase 1: Remove Problematic Flags

**Edit**: `isolates/pom.xml`

**macOS profile changes (lines 151-153)**:
```xml
<!-- REMOVE THIS LINE:
<buildArg>--initialize-at-build-time=com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor</buildArg>
-->

<!-- KEEP THIS LINE: -->
<buildArg>--initialize-at-build-time=javax.sql.rowset.RowSetProvider</buildArg>

<!-- REMOVE THIS LINE:
<buildArg>--initialize-at-build-time=com.hellblazer.delos.comm.grpc.DomainSocketsOSX</buildArg>
-->

<!-- KEEP THIS LINE: -->
<buildArg>--initialize-at-run-time=org.h2.store.fs.FilePath</buildArg>
```

**Linux profile changes (lines 214-216)**:
```xml
<!-- REMOVE THIS LINE:
<buildArg>--initialize-at-build-time=com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor</buildArg>
-->

<!-- KEEP THIS LINE: -->
<buildArg>--initialize-at-build-time=javax.sql.rowset.RowSetProvider</buildArg>

<!-- REMOVE THIS LINE:
<buildArg>--initialize-at-build-time=com.hellblazer.delos.comm.grpc.DomainSocketsLinux</buildArg>
-->

<!-- KEEP THIS LINE: -->
<buildArg>--initialize-at-run-time=org.h2.store.fs.FilePath</buildArg>
```

### Phase 2: Add Explanatory Comments

Add comments above the remaining flags explaining why they're needed:

```xml
<!-- ServiceLoader-based class requires build-time initialization for native-image -->
<buildArg>--initialize-at-build-time=javax.sql.rowset.RowSetProvider</buildArg>

<!-- H2 file paths must be initialized at runtime to respect runtime filesystem state -->
<buildArg>--initialize-at-run-time=org.h2.store.fs.FilePath</buildArg>
```

### Phase 3: Verify Dependencies

Consider whether `domain-kqueue` and `domain-epoll` should remain as `<scope>provided</scope>` or be removed entirely from isolates module, since:
- Isolates use NIO transport exclusively
- Native implementations should not be in the native image
- Current configuration is contradictory

### Phase 4: Test and Validate

1. Clean rebuild: `./mvnw clean package -Pisolates`
2. Run DemesneIsolateTest
3. Verify log shows: "Initializing DemesneImpl in isolate mode with NIO transport (reflection-free)"
4. Run full isolate-ftesting suite
5. Verify main JVM still uses platform-native (test without `-Dde los.isolate.mode`)

## Expected Outcomes

**After removing problematic flags**:
1. ✅ DemesneImpl static initializer runs at runtime (not build time)
2. ✅ System property `delos.isolate.mode` is correctly read at runtime
3. ✅ Isolates use NIO transport (no native library conflicts)
4. ✅ Main JVM continues using platform-native transport (KQueue/Epoll)
5. ✅ No MissingReflectionRegistrationError
6. ✅ Reflection-free ChannelFactory approach works correctly

## Alternative Considered: Lazy Initialization

If runtime initialization causes issues, fallback to lazy initialization:

```java
// In DemesneImpl.java
private static volatile DomainSockets domainSockets;

private static DomainSockets getDomainSockets() {
    if (domainSockets == null) {
        synchronized (DemesneImpl.class) {
            if (domainSockets == null) {
                boolean inIsolate = Boolean.getBoolean("delos.isolate.mode");
                domainSockets = inIsolate ? new DomainSocketsNIO() : IMPL;
            }
        }
    }
    return domainSockets;
}
```

**Only use if**: Runtime initialization breaks DomainSocketServerInterceptor functionality
**Risk**: More complex, requires code changes, adds synchronization overhead

## References

- GraalVM Docs: [Class Initialization](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/ClassInitialization/)
- Commit b4f4a176: "aot work" - Initial GraalVM setup
- Commit 325aec0f: "Phase 1: Isolates module restoration"
- Delos Hybrid Architecture: Platform-native for main JVM, NIO for isolates
