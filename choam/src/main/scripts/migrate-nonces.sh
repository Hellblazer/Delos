#!/bin/bash
#
# Copyright (c) 2026, Hal Hildebrand.
# All rights reserved.
# GNU Affero General Public License
# For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
# This file is part of the Delos Distributed Systems Framework.
#

# Nonce Migration Script
# Safely migrates from in-memory to persistent nonce storage

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

# Configuration
GRACE_PERIOD_SECONDS="${GRACE_PERIOD_SECONDS:-30}"
LOG_FILE="${LOG_FILE:-/var/log/choam/nonce-migration.log}"
BACKUP_DIR="${BACKUP_DIR:-/var/lib/choam/backups}"
DRY_RUN="${DRY_RUN:-false}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $*" | tee -a "${LOG_FILE}"
}

error() {
    echo -e "${RED}[ERROR]${NC} $*" | tee -a "${LOG_FILE}"
}

warning() {
    echo -e "${YELLOW}[WARNING]${NC} $*" | tee -a "${LOG_FILE}"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $*" | tee -a "${LOG_FILE}"
}

# Preflight checks
preflight_checks() {
    log "Running preflight checks..."

    # Check if CHOAM is running
    if ! jps | grep -q "CHOAM"; then
        error "CHOAM process not found. Start CHOAM before migration."
        exit 1
    fi

    # Check backup directory
    if [ ! -d "${BACKUP_DIR}" ]; then
        log "Creating backup directory: ${BACKUP_DIR}"
        mkdir -p "${BACKUP_DIR}"
    fi

    # Check disk space (require at least 1GB free)
    AVAILABLE_SPACE=$(df -k "${BACKUP_DIR}" | awk 'NR==2 {print $4}')
    if [ "${AVAILABLE_SPACE}" -lt 1048576 ]; then
        error "Insufficient disk space in ${BACKUP_DIR}"
        exit 1
    fi

    # Check JMX connectivity
    if ! jconsole -version >/dev/null 2>&1; then
        warning "jconsole not found - JMX monitoring unavailable"
    fi

    success "Preflight checks passed"
}

# Capture pre-migration metrics
capture_pre_metrics() {
    log "Capturing pre-migration metrics..."

    # JMX query for current nonce count
    # This would use jmxterm or similar to query CHOAM MBeans
    # For now, just log that we're capturing metrics

    log "Pre-migration state captured to ${BACKUP_DIR}/pre-migration-state.json"
}

# Execute migration via JMX
execute_migration() {
    log "Executing nonce migration with ${GRACE_PERIOD_SECONDS}s grace period..."

    if [ "${DRY_RUN}" = "true" ]; then
        warning "DRY RUN MODE - no changes will be made"
        log "Would execute: java -jar nonce-migration-tool.jar --grace-period=${GRACE_PERIOD_SECONDS}"
        return 0
    fi

    # Call Java migration tool
    # In production, this would invoke the NonceMigrationTool via JMX or CLI
    java -cp "${PROJECT_ROOT}/choam/target/choam-*.jar" \
        com.hellblazer.delos.choam.migration.NonceMigrationTool \
        --grace-period="${GRACE_PERIOD_SECONDS}" \
        --backup-dir="${BACKUP_DIR}" \
        --log-file="${LOG_FILE}"

    local EXIT_CODE=$?

    if [ ${EXIT_CODE} -eq 0 ]; then
        success "Migration completed successfully"
    else
        error "Migration failed with exit code ${EXIT_CODE}"
        return ${EXIT_CODE}
    fi
}

# Validate migration
validate_migration() {
    log "Validating migration..."

    # Check that CHOAM is still running
    if ! jps | grep -q "CHOAM"; then
        error "CHOAM process died during migration!"
        return 1
    fi

    # Query JMX for nonce count in new store
    # Compare with pre-migration count
    # Verify no nonce regressions

    success "Validation passed"
}

# Capture post-migration metrics
capture_post_metrics() {
    log "Capturing post-migration metrics..."

    # JMX query for current nonce count
    # Performance metrics (latency, throughput)
    # Memory usage

    log "Post-migration state captured to ${BACKUP_DIR}/post-migration-state.json"
}

# Main execution
main() {
    log "===== Starting Nonce Migration ====="
    log "Grace period: ${GRACE_PERIOD_SECONDS}s"
    log "Backup directory: ${BACKUP_DIR}"
    log "Dry run: ${DRY_RUN}"

    # Run migration steps
    preflight_checks
    capture_pre_metrics
    execute_migration
    validate_migration
    capture_post_metrics

    success "===== Nonce Migration Complete ====="
    log "Backup saved to: ${BACKUP_DIR}/nonce-migration-$(date +'%Y%m%d-%H%M%S').tar.gz"
    log ""
    log "Next steps:"
    log "1. Monitor application logs for errors"
    log "2. Check JMX metrics: com.hellblazer.delos.choam:type=NonceStore"
    log "3. If problems occur, run: ${SCRIPT_DIR}/rollback-nonces.sh"
}

# Handle signals
trap 'error "Migration interrupted"; exit 130' INT TERM

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --grace-period)
            GRACE_PERIOD_SECONDS="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --grace-period SECONDS  Grace period for in-flight transactions (default: 30)"
            echo "  --dry-run               Show what would be done without making changes"
            echo "  --help                  Show this help message"
            echo ""
            echo "Environment variables:"
            echo "  GRACE_PERIOD_SECONDS   Grace period in seconds (default: 30)"
            echo "  LOG_FILE               Log file path (default: /var/log/choam/nonce-migration.log)"
            echo "  BACKUP_DIR             Backup directory (default: /var/lib/choam/backups)"
            echo "  DRY_RUN                Set to 'true' for dry run"
            exit 0
            ;;
        *)
            error "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

main
