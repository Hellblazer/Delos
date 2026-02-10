# IDE Setup & Development Workflow

**Getting your development environment ready for Delos**

**Audience**: Java developers
**Estimated Setup Time**: 15-30 minutes
**Prerequisites**: Java 25+, Git, Maven 3.9+

---

## Quick Start

### macOS / Linux

```bash
# 1. Clone repository
git clone https://github.com/Hellblazer/Delos.git
cd Delos

# 2. Build (first time only - builds h2-deterministic)
./mvnw clean install -Ppre -DskipTests

# 3. Open in your IDE
# Jump to your IDE section below
```

### Windows (WSL Recommended)

```bash
# Use Windows Subsystem for Linux (WSL)
wsl
cd /mnt/c/Users/YourUsername/Projects
git clone https://github.com/Hellblazer/Delos.git
cd Delos
./mvnw clean install -Ppre -DskipTests
```

---

## IntelliJ IDEA Setup (Recommended)

IntelliJ has the best Delos support with pre-configured run configs.

### Step 1: Import Project

1. **File** → **Open** (or **File** → **New** → **Project from Version Control** if starting fresh)
2. Select the Delos repository root
3. IntelliJ will auto-detect Maven project
4. Click **Trust Project** if prompted
5. Wait for indexing to complete (5-10 minutes first time)

### Step 2: Configure JDK

1. **IntelliJ IDEA** → **Settings** → **Project Structure** → **Project**
2. Set **SDK** to Java 25+ (must be 25 or later)
3. Set **Language Level** to 25
4. Click **Apply** → **OK**

### Step 3: Import Code Style

IntelliJ includes `.idea/` directory with project settings:

1. Code style is automatically applied
2. Inspections are pre-configured
3. Run configurations are available (see below)

### Step 4: Configure Maven

1. **IntelliJ IDEA** → **Settings** → **Build, Execution, Deployment** → **Maven**
2. Verify **Maven home path** points to embedded Maven or local installation
3. Set **User settings file** (optional, uses default `~/.m2/settings.xml`)
4. Click **Apply** → **OK**

### Step 5: Run/Debug Configurations

Pre-configured run configurations are in `.run/` directory:

1. **Run** → **Edit Configurations**
2. You should see pre-built configs like:
   - `Fireflies 7-Node Cluster` - Start local 7-node test cluster
   - `Run Tests` - Execute test suite
   - `Debug Single Test` - Debug test with remote debugger
3. Select a config and click **Run** (Ctrl+Shift+F10 on macOS, Shift+F10 on Linux)

### Common Tasks

**Build the project:**
```
Ctrl+F9 (Build) or Cmd+B (macOS)
```

**Run tests:**
```
Right-click on test file/class/method → Run or Debug
Or use pre-configured "Run Tests" config
```

**Search for class:**
```
Cmd+O (macOS) or Ctrl+N (Windows/Linux)
Type class name: "Ethereal", "CHOAM", "SqlStateMachine", etc.
```

**Search for symbol in code:**
```
Cmd+Shift+O (macOS) or Ctrl+Shift+Alt+N (Windows/Linux)
Type symbol: "consensus", "transaction", etc.
```

**Navigate between related files:**
```
Cmd+Ctrl+Up (macOS) - Go to test file from main
Cmd+Ctrl+Down (macOS) - Go to main file from test
```

**Reformat code:**
```
Cmd+Alt+L (macOS) or Ctrl+Alt+L (Windows/Linux)
```

### Troubleshooting IntelliJ

**"Cannot resolve symbol" errors:**
```
File → Invalidate Caches → Invalidate and Restart
```

**Tests not running:**
```
File → Project Structure → Project
Verify JDK is set to Java 25+
```

**IDE very slow:**
```
Settings → Project Structure → Modules
Mark "target/" directories as Excluded (right-click → Mark as Excluded)
```

**Module import issues:**
```
File → Project Structure → Modules
Remove module if errors, then:
View → Tool Windows → Maven → Reimport All Maven Projects
```

---

## VS Code Setup

VS Code is lightweight and good for quick edits and testing.

### Step 1: Install Extensions

1. Open VS Code
2. **View** → **Extensions** or **Cmd+Shift+X**
3. Search and install:
   - **Extension Pack for Java** (Microsoft) - Complete Java support
   - **Maven for Java** (Microsoft) - Maven integration
   - **Protocol Buffers** (pbkit) - Protobuf syntax highlighting

### Step 2: Open Folder

1. **File** → **Open Folder**
2. Select Delos repository root
3. Click **Trust** if prompted
4. Wait for Java Language Server to initialize (3-5 minutes)

### Step 3: Configure JDK

1. **Cmd+Shift+P** (Command Palette)
2. Type: **Java: Configure Java Runtime**
3. Select Java 25+ (or download if not available)
4. Verify by clicking **Java** in bottom status bar

### Step 4: Maven Configuration

VS Code automatically detects Maven:

1. **View** → **Explorer** (or **Cmd+Shift+E**)
2. You should see **MAVEN PROJECTS** panel
3. Expand to see modules

### Common Tasks

**Build project:**
```
Cmd+Shift+P → Maven: Build
```

**Run test:**
```
Right-click on test file → Run Test
Or click "Run" link above @Test method
```

**Debug test:**
```
Right-click on test file → Debug Test
```

**Search files:**
```
Cmd+P - Quick file open
Cmd+Shift+F - Search in files
```

**Format code:**
```
Shift+Alt+F (Windows/Linux) or Cmd+Shift+P → Format
```

### VS Code Settings

Create/edit `.vscode/settings.json` in Delos root:

```json
{
    "java.runtime.name": "openjdk-25",
    "java.home": "/path/to/java25",
    "maven.excludedFolders": [
        "**/node_modules/**",
        "**/.git/**",
        "**/h2-deterministic/**"
    ],
    "maven.executable.preferMavenWrapper": true,
    "[java]": {
        "editor.defaultFormatter": "redhat.java",
        "editor.formatOnSave": true,
        "editor.codeActionsOnSave": {
            "source.fixAll": true,
            "source.organizeImports": true
        }
    }
}
```

### Launch Configuration

Create `.vscode/launch.json` for debugging (see [Debugging Guide](DEBUGGING_GUIDE.md)):

```json
{
    "version": "0.2.0",
    "configurations": [
        {
            "type": "java",
            "name": "Launch Main Class",
            "request": "launch",
            "mainClass": "${selectedText}",
            "cwd": "${workspaceFolder}",
            "console": "integratedTerminal"
        },
        {
            "type": "java",
            "name": "Launch Test",
            "request": "launch",
            "mainClass": "",
            "cwd": "${workspaceFolder}",
            "console": "integratedTerminal",
            "args": "-m junit"
        }
    ]
}
```

### Troubleshooting VS Code

**Java Language Server not starting:**
```
Cmd+Shift+P → Java: Clean Language Server Workspace
```

**Maven builds failing:**
```
View → Terminal
./mvnw clean install -Ppre -DskipTests
```

**Intellisense not working:**
```
Cmd+Shift+P → Java: Project Configuration Update
Or restart VS Code
```

---

## Eclipse Setup (Not Recommended for Delos)

If you must use Eclipse, follow these steps:

### Step 1: Install M2Eclipse

1. **Help** → **Install New Software**
2. Add site: `http://download.eclipse.org/technology/m2e/releases/latest`
3. Install **M2E Core**

### Step 2: Import Maven Project

1. **File** → **Import** → **Maven** → **Existing Maven Projects**
2. Select Delos root directory
3. Click **Finish**

### Step 3: Configure JDK

1. **Eclipse** → **Preferences** → **Installed JREs**
2. Add Java 25 (or verify it's listed)
3. **Java** → **Compiler** → Set **Compiler Compliance** to 25

### Known Issues

- Eclipse is significantly slower than IntelliJ for Delos
- Protobuf compilation may require manual configuration
- Large multi-module projects are sluggish in Eclipse
- **Recommendation**: Use IntelliJ or VS Code instead

---

## Command Line Development

If you prefer terminal-based development:

### Building

```bash
# Build all modules
./mvnw clean install

# Build single module
./mvnw install -amd -pl choam

# Skip tests
./mvnw install -DskipTests

# Full test suite
./mvnw clean install -Dlarge_tests=true
```

### Testing

```bash
# Run all tests
./mvnw test

# Run single test class
./mvnw test -pl fireflies -Dtest=ViewTest

# Run single test method
./mvnw test -Dtest=ViewTest#shouldFormQuorum

# Run with debugging
./mvnw test -pl choam -Dtest=ChoamTest#testBasicConsensus \
    -DargLine="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
# Then attach debugger to port 5005
```

### Running Code Generation

```bash
# Generate GRPC/Protobuf sources
./mvnw generate-sources

# Generate JOOQ database classes
./mvnw generate-sources -pl sql-state
```

### Code Analysis

```bash
# Check code style
./mvnw checkstyle:check

# Find bugs
./mvnw findbugs:check

# Dependency analysis
./mvnw dependency:tree -pl ethereal
```

---

## Useful IDE Shortcuts

### IntelliJ IDEA

| Action | macOS | Linux/Windows |
|--------|-------|---------------|
| Quick Open Class | Cmd+O | Ctrl+N |
| Quick Open File | Cmd+Shift+O | Ctrl+Shift+N |
| Go to Definition | Cmd+B | Ctrl+B |
| Find Usages | Cmd+Alt+F7 | Ctrl+Alt+F7 |
| Rename | Cmd+Alt+R | Ctrl+Alt+R |
| Run | Ctrl+Shift+R | Shift+F10 |
| Debug | Ctrl+Shift+D | Shift+F9 |
| Build | Cmd+B | Ctrl+F9 |
| Format Code | Cmd+Alt+L | Ctrl+Alt+L |
| Search in Files | Cmd+Shift+F | Ctrl+Shift+F |
| Terminal | Ctrl+` | Ctrl+` |

### VS Code

| Action | Shortcut |
|--------|----------|
| Quick Open File | Cmd+P |
| Search in Files | Cmd+Shift+F |
| Go to Definition | F12 |
| Format Code | Shift+Alt+F |
| Terminal | Ctrl+` |
| Command Palette | Cmd+Shift+P |

---

## Development Workflow

### Feature Development Workflow

```
1. Create feature branch
   git checkout -b feature/my-feature

2. Write tests first (TDD)
   - Create test file in src/test/java
   - Test fails initially

3. Implement feature
   - Modify source in src/main/java
   - Run tests: ./mvnw test -pl <module>

4. Verify tests pass
   - All tests green
   - No warnings or style issues

5. Code review locally
   - Run formatter: ./mvnw spotless:apply
   - Check: ./mvnw checkstyle:check

6. Commit and push
   - git add .
   - git commit -m "Feature: ..."
   - git push origin feature/my-feature

7. Create PR on GitHub
   - GitHub actions run full test suite
   - Request review
```

### Running Tests Locally

```bash
# Before pushing, run full test suite
./mvnw clean test -pl <modified-module>

# Or run all tests to be sure
./mvnw clean test

# For large tests (resource-intensive)
./mvnw test -Dlarge_tests=true -Dtest=<TestClass>
```

### Debugging a Failing Test

```bash
# 1. Run test with debugging
./mvnw test -pl fireflies -Dtest=ViewTest \
    -DargLine="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"

# 2. In IDE: Run → Debug → Remote Debug (port 5005)

# 3. Set breakpoints in IDE and step through

# Or use IDE directly:
# Right-click test → Debug
```

---

## Productivity Tips

### Faster Builds

```bash
# Skip tests for faster builds during development
./mvnw install -DskipTests

# Parallel builds (if your system has cores)
./mvnw -T 1C clean install

# Skip both tests and javadoc
./mvnw install -DskipTests -Dskip=true
```

### Smart IDE Usage

**IntelliJ:**
- **Inspections**: Code → Run Inspections by Name
- **Intentions**: Alt+Enter when light bulb appears
- **Postfix completion**: Type `.var` and press Tab
- **Live templates**: Type `ifn` for null check template

**VS Code:**
- **Command palette**: Cmd+Shift+P
- **Outline**: Cmd+Shift+O within file
- **Problems**: Cmd+Shift+M to see all errors

### Profile & Monitor

```bash
# Use JProfiler or similar with IDE remote connection
java -agentpath:/path/to/jprofiler/bin/macos/jprofilerti.jnilib=port=8849 ...
```

---

## Environment Variables

Useful env vars for Delos development:

```bash
# Java options (increase heap for large tests)
export JAVA_OPTS="-Xmx4g -Xms2g -XX:+UseG1GC"

# Maven options
export MAVEN_OPTS="-Xmx2g"

# Enable detailed logging
export LOG_LEVEL="DEBUG"

# Skip some modules for faster builds
export MAVEN_SKIP="h2-deterministic,liquibase-modified"
```

---

## Pre-Commit Hooks (Optional)

Set up local git hooks to auto-format and verify before commits:

```bash
#!/bin/bash
# .git/hooks/pre-commit

# Format code
./mvnw spotless:apply > /dev/null 2>&1

# Run tests
./mvnw test -DskipTests=false -q
if [ $? -ne 0 ]; then
    echo "Tests failed - commit aborted"
    exit 1
fi

# Stage formatted files
git add .
```

Install:
```bash
chmod +x .git/hooks/pre-commit
```

---

## Getting Help

### Documentation

- **Transaction Flow**: [TRANSACTION_FLOW_GUIDE.md](TRANSACTION_FLOW_GUIDE.md)
- **Debugging**: [DEBUGGING_GUIDE.md](DEBUGGING_GUIDE.md)
- **Module Docs**: Start at [INDEX.md](INDEX.md)
- **Build System**: [CLAUDE.md](../CLAUDE.md)

### Common Questions

**Q: Tests timeout**
```
A: Some tests are slow. Try:
   ./mvnw test -Dtest.timeout=60000
   Or run specific test
```

**Q: Cannot find Delos classes**
```
A: Run: ./mvnw clean install -Ppre -DskipTests
   Then refresh IDE
```

**Q: Build fails with "cannot find h2-deterministic"**
```
A: First time setup - run: ./mvnw install -Ppre -DskipTests
   This builds and installs h2-deterministic
```

**Q: What's the entry point for a new module?**
```
A: Check the module's README.md for "Core Classes" section
   Also see ./examples/ for usage patterns
```

---

## Next Steps

1. **Set up your IDE** using the guide for your choice above
2. **Clone and build**: `./mvnw clean install -Ppre -DskipTests`
3. **Run tests**: `./mvnw test -pl fireflies` (quick smoke test)
4. **Read** [TRANSACTION_FLOW_GUIDE.md](TRANSACTION_FLOW_GUIDE.md) to understand how Delos works
5. **Explore examples** in `./examples/`
6. **Start developing** your feature!

---

Last Updated: 2026-01-09
Status: Phase 2.4 (Developer Enablement)
Epic: Delos-aj2 (Documentation Improvement)
