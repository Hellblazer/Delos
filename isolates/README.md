# Delos Isolates

This module produces the Demesne GraalVM native shared library for the Delos stack.

## Hybrid Transport Architecture

Delos uses a **hybrid transport strategy** to maximize performance while supporting GraalVM isolates:

### Main JVM: Native Transports (Best Performance)
- Uses platform-native socket implementations (KQueue on macOS, Epoll on Linux)
- Provides optimal performance for the main JVM runtime
- Modules: `domain-kqueue`, `domain-epoll`
- Benefits: Hardware-accelerated I/O, minimal overhead

### Isolates: NIO Transport (Pure Java)
- Uses JEP 380 Java NIO for Unix domain sockets (pure Java implementation)
- Module: `domain-nio`
- **Why NIO for isolates?** Native libraries cannot be safely shared across multiple GraalVM isolates due to symbol table conflicts and state management issues. The pure Java NIO implementation eliminates this problem.
- Performance: Slightly slower than native (~5-10% overhead) but acceptable and safe
- Benefits: Eliminates native library conflicts, enables multi-isolate deployments

### Configuration
The `DemesneImpl` (produced by this module) is explicitly configured to use the NIO transport. This is set via:
- `System.getProperty("delos.isolate.transport", "nio")`
- Default: NIO transport for isolates
- Main JVM: Uses native transports for optimal performance

## Performance Characteristics

| Environment | Transport | Performance | Notes |
|-------------|-----------|-------------|-------|
| Main JVM | Native (KQueue/Epoll) | 100% baseline | Hardware-accelerated, optimal |
| GraalVM Isolates | NIO | ~90-95% of main JVM | Pure Java, no native conflicts |

The hybrid approach trades ~5-10% performance for the safety and correctness of multi-isolate deployments.
