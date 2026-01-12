# Dependency Security Updates

## Completed Updates (2026-01-12)

### Fixed Vulnerabilities

| Package | Old Version | New Version | Vulnerabilities Fixed | Severity |
|---------|-------------|-------------|----------------------|----------|
| **Netty** | 4.1.110.Final | 4.1.118.Final | CVE-2025-24970, CVE-2025-25193 | 2× Medium |
| **Logback** | 1.5.8 | 1.5.24 | Multiple security patches | 1× Low, 2× Medium |
| **Bouncy Castle** | 1.78.1 | 1.79 | Excessive allocation vulnerability | 1× Medium |

### Technical Details

**Netty Update**:
- Added explicit dependency management for all Netty modules to override grpc-netty 1.68.0 transitive dependencies
- grpc-netty 1.68.0 depends on Netty 4.1.110.Final, requiring dependency management overrides
- Fixed CVE-2025-24970 (SSL handler validation) and CVE-2025-25193 (Windows DoS)
- Reference: [Netty 4.1.118.Final Release](https://netty.io/news/2025/02/10/4-1-118-Final.html)

**Logback Update**:
- Patch release with no breaking changes
- Addresses arbitrary code execution and SSRF vulnerabilities

**Bouncy Castle Update**:
- Minor version update from 1.78.1 → 1.79
- Fixes excessive memory allocation vulnerability (CVE pending assignment)

## Remaining Vulnerabilities (Deferred)

### SnakeYAML (4× High Severity)

**Current Version**: 1.33
**Required**: > 1.33
**Latest Available**: 2.5
**Status**: **Deferred - Breaking Changes Required**

#### Why Deferred

SnakeYAML 2.0 introduced major breaking changes that require code modifications:

1. **Constructor API Changed**:
   ```java
   // Version 1.x
   Yaml yaml = new Yaml(new Constructor(Person.class));

   // Version 2.0+ (REQUIRED)
   Yaml yaml = new Yaml(new Constructor(Person.class, new LoaderOptions()));
   ```

2. **Global Tags Blocked by Default**:
   - Custom type tags (`!!mypackage.Person`) are no longer allowed by default
   - SafeConstructor is now the default behavior
   - Only a limited set of types can be parsed without configuration

3. **Framework Compatibility Issues**:
   - Many frameworks (Spring Boot, Liquibase, etc.) have compatibility issues with SnakeYAML 2.x
   - May require waiting for framework updates

#### Migration Path

When upgrading to SnakeYAML 2.x:

1. **Audit YAML usage across codebase**:
   ```bash
   grep -r "org.yaml.snakeyaml" --include="*.java" .
   ```

2. **Update all Constructor instantiations**:
   - Add `LoaderOptions` parameter to all Constructor calls
   - Configure `TagInspector` for custom classes

3. **Update Dumper code**:
   ```java
   Representer customRepresenter = new Representer(new DumperOptions());
   customRepresenter.addClassTag(Person.class, Tag.MAP);
   Yaml yaml = new Yaml(new Constructor(Person.class, new LoaderOptions()), customRepresenter);
   ```

4. **Test extensively**:
   - YAML parsing in configuration files
   - YAML serialization/deserialization in tests
   - Integration with frameworks (Liquibase, etc.)

#### References

- [SnakeYAML 2.0 Security Fix (Snyk)](https://snyk.io/blog/snakeyaml-unsafe-deserialization-vulnerability/)
- [CVE-2022-1471 Resolution Guide (Veracode)](https://www.veracode.com/blog/research/resolving-cve-2022-1471-snakeyaml-20-release-0)
- [SnakeYAML 2.0 Unsafe Deserialization (Medium)](https://medium.com/@snyksec/snakeyaml-2-0-solving-the-unsafe-deserialization-vulnerability-c29a0f08f152)

#### Workaround

Until SnakeYAML 2.x migration is complete, vulnerability exposure is limited:
- SnakeYAML is typically used for trusted configuration files, not untrusted user input
- Main risk is in Liquibase (database migrations) where YAML is under project control

### H2 Database

**Status**: **Deferred - Complexity**
- Per project maintainer decision, H2 updates require careful planning
- h2-deterministic module uses package shading and must be updated cautiously

## Dependency Management Strategy

### grpc-netty Compatibility

grpc-netty 1.68.0 depends on Netty 4.1.110.Final. To use newer Netty versions:

```xml
<dependencyManagement>
    <dependencies>
        <!-- Force all Netty modules to override grpc-netty transitive deps -->
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-transport</artifactId>
            <version>${netty.version}</version>
        </dependency>
        <!-- ... (all other netty modules) ... -->
    </dependencies>
</dependencyManagement>
```

This pattern allows using Netty 4.1.118.Final while grpc-netty still depends on 4.1.110.Final.

### Verification Commands

After dependency updates:

```bash
# Build h2-deterministic first (required)
./mvnw clean install -Ppre -DskipTests

# Full build with tests
./mvnw clean install

# Check for new dependency updates
./mvnw versions:display-dependency-updates

# Check for new property-based updates
./mvnw versions:display-property-updates

# View Dependabot alerts
gh api /repos/Hellblazer/Delos/dependabot/alerts --jq '.[] | select(.state == "open") | {number, severity: .security_advisory.severity, package: .security_vulnerability.package.name}'
```

## Update History

- **2026-01-12**: Updated Netty (4.1.118.Final), Logback (1.5.24), Bouncy Castle (1.79)
- **Future**: SnakeYAML 2.x migration (pending breaking change resolution)
