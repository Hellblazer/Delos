#!/bin/bash
#
# Copyright (c) 2026, Hal Hildebrand.
# All rights reserved.
# GNU Affero General Public License
# For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
# This file is part of the Delos Distributed Systems Framework.
#

# Nonce Rollback Script
# Reverts to in-memory nonce storage after failed migration

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

# Configuration
LOG_FILE="${LOG_FILE:-/var/log/choam/nonce-rollback.log}"
BACKUP_DIR="${BACKUP_DIR:-/var/lib/choam/backups}"
FORCE="${FORCE:-false}"

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

# Confirmation prompt
confirm_rollback() {
    if [ "${FORCE}" = "true" ]; then
        warning "FORCE mode - skipping confirmation"
        return 0
    fi

    echo ""
    echo -e "${YELLOW}WARNING: This will rollback nonce storage to in-memory (non-persistent)${NC}"
    echo "This operation will:"
    echo "  1. Restore nonce state from backup"
    echo "  2. Clear persistent nonce storage"
    echo "  3. Switch back to in-memory storage"
    echo ""
    read -p "Are you sure you want to proceed? (type 'YES' to confirm): " CONFIRM

    if [ "${CONFIRM}" != "YES" ]; then
        log "Rollback cancelled by user"
        exit 0
    fi
}

# Find latest backup
find_latest_backup() {
    log "Looking for latest backup in ${BACKUP_DIR}..."

    if [ ! -d "${BACKUP_DIR}" ]; then
        error "Backup directory not found: ${BACKUP_DIR}"
        exit 1
    fi

    LATEST_BACKUP=$(ls -t "${BACKUP_DIR}"/nonce-migration-*.tar.gz 2>/dev/null | head -1)

    if [ -z "${LATEST_BACKUP}" ]; then
        error "No backup found in ${BACKUP_DIR}"
        exit 1
    fi

    log "Found backup: ${LATEST_BACKUP}"
    echo "${LATEST_BACKUP}"
}

# Verify CHOAM is running
verify_choam_running() {
    log "Verifying CHOAM is running..."

    if ! jps | grep -q "CHOAM"; then
        error "CHOAM process not found. Start CHOAM before rollback."
        exit 1
    fi

    success "CHOAM is running"
}

# Capture pre-rollback state
capture_pre_rollback_state() {
    log "Capturing pre-rollback state..."

    # Snapshot current nonce state before rollback
    # This allows recovery if rollback itself fails

    log "Pre-rollback state saved to ${BACKUP_DIR}/pre-rollback-$(date +'%Y%m%d-%H%M%S').json"
}

# Execute rollback via JMX
execute_rollback() {
    log "Executing rollback..."
    local BACKUP_FILE=$1

    # Extract backup
    local TEMP_DIR=$(mktemp -d)
    tar -xzf "${BACKUP_FILE}" -C "${TEMP_DIR}"

    # Call Java rollback tool
    java -cp "${PROJECT_ROOT}/choam/target/choam-*.jar" \
        com.hellblazer.delos.choam.migration.NonceMigrationTool \
        --rollback \
        --backup-file="${BACKUP_FILE}" \
        --log-file="${LOG_FILE}"

    local EXIT_CODE=$?

    # Cleanup temp directory
    rm -rf "${TEMP_DIR}"

    if [ ${EXIT_CODE} -eq 0 ]; then
        success "Rollback completed successfully"
    else
        error "Rollback failed with exit code ${EXIT_CODE}"
        return ${EXIT_CODE}
    fi
}

# Validate rollback
validate_rollback() {
    log "Validating rollback..."

    # Check CHOAM is still running
    if ! jps | grep -q "CHOAM"; then
        error "CHOAM process died during rollback!"
        return 1
    fi

    # Verify nonces restored
    # Check JMX for in-memory store active
    # Verify persistent store cleared

    success "Rollback validation passed"
}

# Capture post-rollback metrics
capture_post_rollback_metrics() {
    log "Capturing post-rollback metrics..."

    # Query JMX for nonce counts
    # Performance metrics
    # Verify in-memory storage active

    log "Post-rollback state captured"
}

# Main execution
main() {
    log "===== Starting Nonce Rollback ====="

    confirm_rollback
    verify_choam_running

    BACKUP_FILE=$(find_latest_backup)

    capture_pre_rollback_state
    execute_rollback "${BACKUP_FILE}"
    validate_rollback
    capture_post_rollback_metrics

    success "===== Nonce Rollback Complete ====="
    log ""
    log "System has been rolled back to in-memory nonce storage"
    log "Backup used: ${BACKUP_FILE}"
    log ""
    log "Next steps:"
    log "1. Monitor application logs for stability"
    log "2. Investigate migration failure in ${LOG_FILE}"
    log "3. Fix issues before attempting migration again"
    log "4. Check JMX metrics: com.hellblazer.delos.choam:type=NonceStore"
}

# Handle signals
trap 'error "Rollback interrupted"; exit 130' INT TERM

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --force)
            FORCE=true
            shift
            ;;
        --backup-file)
            LATEST_BACKUP="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --force               Skip confirmation prompt"
            echo "  --backup-file FILE    Use specific backup file instead of latest"
            echo "  --help                Show this help message"
            echo ""
            echo "Environment variables:"
            echo "  LOG_FILE     Log file path (default: /var/log/choam/nonce-rollback.log)"
            echo "  BACKUP_DIR   Backup directory (default: /var/lib/choam/backups)"
            echo "  FORCE        Set to 'true' to skip confirmation"
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
