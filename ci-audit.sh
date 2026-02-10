#!/bin/bash
# ci-audit.sh - Audit CI test coverage to ensure all tests run
# Usage: ./ci-audit.sh [--verbose]

set -e

VERBOSE=false
if [ "$1" = "--verbose" ]; then
    VERBOSE=true
fi

echo "============================================"
echo "   CI Test Coverage Audit"
echo "============================================"
echo

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 1. Find all modules with test directories
echo "1. Discovering modules with tests..."
ALL_MODULES_WITH_TESTS=$(find . -type d -path "*/src/test/java" | sed 's|/src/test/java||' | sed 's|^\./||' | sort)
MODULE_COUNT=$(echo "$ALL_MODULES_WITH_TESTS" | wc -l | tr -d ' ')
echo "   Found ${MODULE_COUNT} modules with test directories"
echo

# 2. Extract modules mentioned in maven.yml
echo "2. Extracting modules from CI configuration..."
CI_WORKFLOW=".github/workflows/maven.yml"
NIGHTLY_WORKFLOW=".github/workflows/comprehensive-tests.yml"

# Extract all -pl arguments from maven.yml
CI_MODULES=$(grep -E "surefire:test -pl|test -pl" "$CI_WORKFLOW" | \
    sed 's/.*-pl //' | \
    sed 's/ .*//' | \
    tr ',' '\n' | \
    sed 's/--file.*//' | \
    sort -u | \
    grep -v '^$')

CI_MODULE_COUNT=$(echo "$CI_MODULES" | wc -l | tr -d ' ')
echo "   Found ${CI_MODULE_COUNT} modules referenced in fast CI"

# Extract modules from nightly workflow
if [ -f "$NIGHTLY_WORKFLOW" ]; then
    NIGHTLY_MODULES=$(grep -E "surefire:test -pl|test -pl" "$NIGHTLY_WORKFLOW" | \
        sed 's/.*-pl //' | \
        sed 's/ .*//' | \
        tr ',' '\n' | \
        sed 's/--file.*//' | \
        sort -u | \
        grep -v '^$')
    NIGHTLY_MODULE_COUNT=$(echo "$NIGHTLY_MODULES" | wc -l | tr -d ' ')
    echo "   Found ${NIGHTLY_MODULE_COUNT} modules referenced in nightly CI"
fi
echo

# 3. Find missing modules
echo "3. Checking for missing modules..."

# Known exclusions (modules that should NOT be in CI)
# - h2-deterministic: Shaded SQL module, built once with -Ppre
# - liquibase-modified: Forked source, not for regular builds
# - isolates: Requires GraalVM -Pisolates profile
# - isolate-ftesting: Tests isolates, requires -Pisolates profile
# - comm-simulation: Simulation/testing utilities, not production code
EXPECTED_EXCLUSIONS="h2-deterministic liquibase-modified isolates isolate-ftesting comm-simulation"

MISSING_MODULES=""
MISSING_COUNT=0
EXCLUDED_MODULES=""
EXCLUDED_COUNT=0

for module in $ALL_MODULES_WITH_TESTS; do
    if ! echo "$CI_MODULES" | grep -q "^${module}$" && \
       ! echo "$NIGHTLY_MODULES" | grep -q "^${module}$"; then

        # Check if this is an expected exclusion
        if echo "$EXPECTED_EXCLUSIONS" | grep -qw "$module"; then
            EXCLUDED_MODULES="${EXCLUDED_MODULES}${module}\n"
            EXCLUDED_COUNT=$((EXCLUDED_COUNT + 1))
        else
            MISSING_MODULES="${MISSING_MODULES}${module}\n"
            MISSING_COUNT=$((MISSING_COUNT + 1))
        fi
    fi
done

if [ $EXCLUDED_COUNT -gt 0 ]; then
    echo -e "   ${GREEN}✓ ${EXCLUDED_COUNT} modules excluded (expected):${NC}"
    echo -e "      ${EXCLUDED_MODULES}" | sed 's/\\n/\n      /g' | grep -v '^$'
fi

if [ $MISSING_COUNT -eq 0 ]; then
    echo -e "   ${GREEN}✓ All non-excluded modules are in CI${NC}"
else
    echo -e "   ${RED}✗ Found ${MISSING_COUNT} modules with tests NOT in CI:${NC}"
    echo -e "${RED}${MISSING_MODULES}${NC}" | sed 's/\\n/\n   /g' | grep -v '^$'
fi
echo

# 4. Count test classes
echo "4. Analyzing test class coverage..."
ALL_TEST_CLASSES=$(find . -name "*Test.java" -o -name "*Tests.java" | wc -l | tr -d ' ')
echo "   Total test classes found: ${ALL_TEST_CLASSES}"

# Count explicitly excluded tests
EXCLUDED_TESTS=$(grep -E "Dtest=" "$CI_WORKFLOW" "$NIGHTLY_WORKFLOW" 2>/dev/null | \
    grep -o "![A-Za-z0-9]*Test" | \
    sort -u | \
    wc -l | tr -d ' ')
echo "   Explicitly excluded test classes: ${EXCLUDED_TESTS}"

# Count explicitly included tests
INCLUDED_TESTS=$(grep -E "Dtest=" "$CI_WORKFLOW" "$NIGHTLY_WORKFLOW" 2>/dev/null | \
    grep -o "[A-Za-z0-9]*Test[^!]*" | \
    grep -v "^!" | \
    sort -u | \
    wc -l | tr -d ' ')
echo "   Explicitly included test classes: ${INCLUDED_TESTS}"

IMPLICIT_TESTS=$((ALL_TEST_CLASSES - EXCLUDED_TESTS - INCLUDED_TESTS))
echo "   Running by default (no -Dtest flag): ${IMPLICIT_TESTS}"
echo

# 5. Check for performance/stress tests in fast CI
echo "5. Checking for performance tests in fast CI..."
PERF_TESTS_IN_FAST_CI=$(grep -E "surefire:test" "$CI_WORKFLOW" | \
    grep -E "Performance|Stress|Benchmark|Simulation" || true)

if [ -z "$PERF_TESTS_IN_FAST_CI" ]; then
    echo -e "   ${GREEN}✓ No performance tests detected in fast CI${NC}"
else
    echo -e "   ${YELLOW}⚠ Possible performance tests in fast CI:${NC}"
    echo "$PERF_TESTS_IN_FAST_CI"
fi
echo

# 6. Verify nightly workflow exists
echo "6. Checking nightly workflow configuration..."
if [ ! -f "$NIGHTLY_WORKFLOW" ]; then
    echo -e "   ${RED}✗ Nightly workflow not found at ${NIGHTLY_WORKFLOW}${NC}"
else
    echo -e "   ${GREEN}✓ Nightly workflow exists${NC}"

    # Check if it has cron schedule
    if grep -q "schedule:" "$NIGHTLY_WORKFLOW" && grep -q "cron:" "$NIGHTLY_WORKFLOW"; then
        CRON_SCHEDULE=$(grep -A 1 "schedule:" "$NIGHTLY_WORKFLOW" | grep "cron:" | sed "s/.*cron: '\(.*\)'.*/\1/")
        echo "   Scheduled to run: ${CRON_SCHEDULE}"
    else
        echo -e "   ${YELLOW}⚠ No cron schedule found (manual trigger only)${NC}"
    fi
fi
echo

# 7. Summary and recommendations
echo "============================================"
echo "   Summary"
echo "============================================"
echo
echo "Coverage Statistics:"
echo "  • Modules with tests: ${MODULE_COUNT}"
echo "  • Modules in fast CI: ${CI_MODULE_COUNT}"
echo "  • Modules in nightly: ${NIGHTLY_MODULE_COUNT}"
echo "  • Expected exclusions: ${EXCLUDED_COUNT} (h2-deterministic, liquibase-modified, isolates, isolate-ftesting, comm-simulation)"
echo "  • Missing from CI: ${MISSING_COUNT}"
echo
echo "Test Class Statistics:"
echo "  • Total test classes: ${ALL_TEST_CLASSES}"
echo "  • Explicitly excluded: ${EXCLUDED_TESTS}"
echo "  • Explicitly included: ${INCLUDED_TESTS}"
echo "  • Running by default: ${IMPLICIT_TESTS}"
echo

if [ $MISSING_COUNT -gt 0 ]; then
    echo -e "${YELLOW}Recommendations:${NC}"
    echo "  1. Add missing modules to .github/workflows/maven.yml"
    echo "  2. Consider if any should go to nightly instead (performance tests)"
    echo "  3. Verify modules actually need CI coverage (some may be utilities)"
    echo
    exit 1
else
    echo -e "${GREEN}✓ All modules with tests are covered by CI${NC}"
    echo
    exit 0
fi

# Verbose mode: show detailed module lists
if [ "$VERBOSE" = true ]; then
    echo
    echo "============================================"
    echo "   Detailed Module Lists (--verbose)"
    echo "============================================"
    echo
    echo "All modules with tests:"
    echo "$ALL_MODULES_WITH_TESTS"
    echo
    echo "Modules in fast CI:"
    echo "$CI_MODULES"
    echo
    if [ -n "$NIGHTLY_MODULES" ]; then
        echo "Modules in nightly CI:"
        echo "$NIGHTLY_MODULES"
        echo
    fi
fi
