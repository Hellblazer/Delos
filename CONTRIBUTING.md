# Contributing to Delos

Thank you for your interest in contributing to Delos! This document provides guidelines for contributing to the project.

## Table of Contents

- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Code Style and Conventions](#code-style-and-conventions)
- [Testing Requirements](#testing-requirements)
- [Commit Message Format](#commit-message-format)
- [Branch Naming](#branch-naming)
- [Pull Request Process](#pull-request-process)
- [Code Review](#code-review)
- [Issue Reporting](#issue-reporting)

## Getting Started

### Prerequisites

- **JDK 25+** (Java 25 or later required)
- **Maven 3.9.3+** (Maven wrapper included, no separate installation needed)
- **Git** for version control
- **Docker** (optional, for end-to-end testing)
- **GraalVM 24.0.2+** (optional, for isolates profile)

### Fork and Clone

1. Fork the repository on GitHub
2. Clone your fork locally:
   ```bash
   git clone https://github.com/YOUR_USERNAME/Delos.git
   cd Delos
   ```
3. Add the upstream repository:
   ```bash
   git remote add upstream https://github.com/Hellblazer/Delos.git
   ```

### Initial Build

Run the first-time setup (required once):
```bash
./mvnw clean install -Ppre -DskipTests
```

Then build the project:
```bash
./mvnw clean install
```

Verify tests pass:
```bash
./mvnw test
```

## Development Setup

### IDE Configuration

- **IntelliJ IDEA**: Import as Maven project. The repository includes `.idea` run configurations.
- **Eclipse**: Use M2Eclipse plugin for Maven integration.

**Important**: Do NOT import the `h2-deterministic` module into your IDE. This module uses package shading and should only be built via Maven.

### Building a Single Module

```bash
./mvnw install -amd -pl <module-name>
```

The `-amd` (also-make-dependents) flag ensures dependencies are built.

### Generating Sources

After modifying `.proto` files or database schemas:
```bash
./mvnw generate-sources
```

## Code Style and Conventions

### General Guidelines

- **Java 25**: Use modern Java features including records, pattern matching, and virtual threads
- **Variable declaration**: Use `var` for local variables where type is obvious
- **Concurrency**: Use concurrent collections and virtual threads; avoid `synchronized` blocks
- **Imports**: Organize imports; remove unused imports
- **Comments**: Write self-documenting code; add comments only for non-obvious logic
- **Line length**: Soft limit of 120 characters

### Naming Conventions

- **Classes**: PascalCase (e.g., `ByzantineMember`)
- **Methods**: camelCase (e.g., `processConsensus()`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `MAX_RETRIES`)
- **Packages**: lowercase (e.g., `com.hellblazer.delos.fireflies`)

### Module-Specific Patterns

- **Fireflies**: Use ring-based gossip patterns
- **CHOAM**: Follow state machine conventions with immutable state transitions
- **SQL-State**: Use Liquibase for schema migrations
- **Cryptography**: Prefer self-describing types (Digest, Signature, Identifier)

### Logging

Use SLF4J with parameterized logging:
```java
log.debug("Processing transaction: {}", txId);  // CORRECT
log.debug("Processing transaction: " + txId);   // INCORRECT (string concat)
```

## Testing Requirements

### Before Submitting a PR

All tests must pass:
```bash
./mvnw clean test
```

### Writing Tests

- **Framework**: JUnit 5 with AssertJ assertions and Mockito for mocking
- **Dynamic ports**: Use port 0 to let the OS assign ports (avoid conflicts)
- **Test isolation**: Each test should be independent and idempotent
- **Test naming**: Use descriptive method names (e.g., `shouldRejectInvalidSignature`)

### Test Categories

- **Unit tests**: Test individual classes in isolation
- **Integration tests**: Test module interactions (may require multi-node setup)
- **Large tests**: Resource-intensive tests requiring 8+ GB RAM
  ```bash
  ./mvnw test -Dlarge_tests=true
  ```

### Test Coverage

Aim for:
- Critical paths: 90%+ coverage
- Public APIs: 80%+ coverage
- Internal utilities: 60%+ coverage

## Commit Message Format

Follow this format for commit messages:

```
<type>: <subject>

<body>

<footer>
```

### Types

- **feat**: New feature
- **fix**: Bug fix
- **docs**: Documentation changes
- **refactor**: Code restructuring without behavior change
- **test**: Adding or updating tests
- **chore**: Build scripts, dependencies, tooling

### Subject

- Use imperative mood ("Add feature" not "Added feature")
- Capitalize first letter
- No period at the end
- Limit to 50 characters

### Body (optional)

- Explain **why** the change was made, not **what** (code shows what)
- Wrap at 72 characters
- Separate from subject with blank line

### Footer (optional)

- Reference issues: `Fixes #123` or `Closes #456`
- Breaking changes: `BREAKING CHANGE: description`

### Examples

**Good**:
```
fix: Prevent consensus deadlock in committee rotation

The rotation logic could deadlock when receiving conflicting
proposals during view change. Now uses CAS-based coordination
to ensure progress.

Fixes #789
```

**Bad**:
```
fixed bug
```

## Branch Naming

Use descriptive branch names with prefixes:

- `feature/short-description` - New features
- `fix/issue-number-description` - Bug fixes
- `docs/description` - Documentation updates
- `refactor/description` - Code refactoring
- `test/description` - Test additions/improvements

**Examples**:
- `feature/add-prometheus-metrics`
- `fix/123-consensus-timeout`
- `docs/update-fireflies-readme`

## Pull Request Process

### Before Submitting

1. **Sync with upstream**:
   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

2. **Run full build**:
   ```bash
   ./mvnw clean install
   ```

3. **Run tests**:
   ```bash
   ./mvnw test
   ```

4. **Check for warnings**: Ensure no new compiler or linter warnings

### Submitting the PR

1. Push your branch to your fork
2. Open a pull request against `main` branch
3. Fill out the PR template completely
4. Link related issues using keywords (Fixes #, Closes #, Relates to #)

### PR Title

Follow commit message format for PR titles:
```
<type>: <description>
```

### PR Description

Include:
- **Summary**: What does this PR do?
- **Motivation**: Why is this change needed?
- **Testing**: How was this tested?
- **Screenshots** (if UI changes)
- **Checklist** (see template)

### PR Checklist

- [ ] Code follows project style guidelines
- [ ] Self-review completed
- [ ] Comments added for complex logic
- [ ] Documentation updated (if needed)
- [ ] Tests added/updated
- [ ] All tests pass locally
- [ ] No new warnings introduced
- [ ] Commit messages follow format

## Code Review

### What Reviewers Look For

- **Correctness**: Does the code work as intended?
- **Tests**: Are there adequate tests?
- **Design**: Is the approach sound?
- **Readability**: Is the code clear and maintainable?
- **Performance**: Are there obvious inefficiencies?
- **Security**: Are there security concerns?

### Responding to Feedback

- Address all comments (or explain why not)
- Mark resolved comments as resolved
- Push additional commits (don't force-push until approved)
- Request re-review when ready

### After Approval

- Squash commits if requested
- Maintainer will merge when ready

## Issue Reporting

### Before Filing an Issue

1. Search existing issues (open and closed)
2. Check documentation and [CLAUDE.md](CLAUDE.md#troubleshooting) (Troubleshooting section)
3. Verify you're using supported versions (JDK 25+, Maven 3.9.3+)

### Issue Template

- **Bug reports**: Describe expected vs actual behavior, steps to reproduce, environment details
- **Feature requests**: Describe use case and proposed solution
- **Questions**: Check docs first, then ask in Discussions

### Good Issue Examples

**Bug Report**:
```
Title: Consensus timeout during high-load scenarios

Environment:
- Delos version: 0.0.11-SNAPSHOT
- JDK: 25.0.1
- OS: Ubuntu 22.04

Steps to Reproduce:
1. Start 7-node cluster
2. Submit 1000 transactions/second
3. Observe consensus timeout after ~30 seconds

Expected: All transactions processed
Actual: Cluster halts with TimeoutException

Logs: [attach relevant logs]
```

**Feature Request**:
```
Title: Add Prometheus metrics for transaction throughput

Use Case:
Operators need visibility into transaction processing rates
for capacity planning and alerting.

Proposed Solution:
Add Counter metric "delos_transactions_total" with labels
for success/failure and transaction type.
```

## Developer Certificate of Origin (DCO)

By contributing to this project, you certify that:

1. The contribution was created in whole or in part by you and you have the right to submit it under the project's license.
2. The contribution is based upon previous work that, to the best of your knowledge, is covered under an appropriate license and you have the right to submit that work with modifications.
3. The contribution was provided directly to you by some other person who certified (1) or (2) and you have not modified it.

You indicate acceptance of the DCO by including a `Signed-off-by` line in your commit messages:

```
Signed-off-by: Your Name <your.email@example.com>
```

Git can add this automatically:
```bash
git commit -s -m "Your commit message"
```

## Questions?

- **Documentation**: See README.md, CLAUDE.md, and docs/ directory
- **Discussions**: Use GitHub Discussions for questions
- **Bugs**: File an issue with the bug report template

## License

By contributing to Delos, you agree that your contributions will be licensed under the project's license (see LICENSE file).

---

Thank you for contributing to Delos!
