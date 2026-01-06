#!/bin/bash
#
# Delos Backup Restore Test Utility
#
# Tests that backups are recoverable by performing a restore
# to a test node without affecting production.
#
# USAGE:
#   ./tools/test-restore.sh --backup <backup-dir> --target test-node
#   ./tools/test-restore.sh --backup /mnt/backups/2026-01-06 --target test-node
#
# ENVIRONMENT:
#   DELOS_HOME (default: /opt/delos)
#   DELOS_USER (default: delos)
#

set -e

DELOS_HOME="${DELOS_HOME:-/opt/delos}"
DELOS_USER="${DELOS_USER:-delos}"

show_usage() {
  cat <<EOF
Delos Backup Restore Test Utility

USAGE:
  ./tools/test-restore.sh --backup <dir> --target <node>

REQUIRED PARAMETERS:
  --backup <dir>        Backup directory to test
  --target <node>       Target node (non-production only)

OPTIONS:
  --dry-run             Show what would be done
  --keep                Keep restored data for inspection
  --verbose             Show detailed output

PROCESS:
  1. Verify backup integrity (GPG signature, tar archive)
  2. Stop target node if running
  3. Restore backup to target node's data directory
  4. Verify restored files are readable
  5. Report results

CRITICAL:
  - Only run on non-production nodes
  - Requires SSH access to target node
  - Will overwrite existing data (unless --dry-run)

QUARTERLY TESTING:
  - Test restore at least quarterly
  - Alternate target nodes
  - Document results for audit trail

EXAMPLE:
  ./tools/test-restore.sh \\
    --backup /mnt/backups/2026-01-06 \\
    --target test-node \\
    --verbose

SEE ALSO:
  - DEPLOYMENT_GUIDE.md Section 5.2: Backup Strategy
  - TROUBLESHOOTING_GUIDE.md Section 4.2: Disaster Recovery

EOF
}

# Parse arguments
while [ $# -gt 0 ]; do
  case "$1" in
    --backup)
      BACKUP_DIR="$2"
      shift 2
      ;;
    --target)
      TARGET_NODE="$2"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --keep)
      KEEP_RESTORED=1
      shift
      ;;
    --verbose)
      VERBOSE=1
      shift
      ;;
    -h|--help)
      show_usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      show_usage
      exit 1
      ;;
  esac
done

if [ -z "$BACKUP_DIR" ] || [ -z "$TARGET_NODE" ]; then
  echo "ERROR: Missing required parameters"
  show_usage
  exit 1
fi

echo "=== Delos Backup Restore Test ==="
echo ""
echo "Configuration:"
echo "  Backup: $BACKUP_DIR"
echo "  Target Node: $TARGET_NODE"
[ -n "$DRY_RUN" ] && echo "  Mode: DRY-RUN (no changes)"
echo ""

# Verify backup exists
if [ ! -d "$BACKUP_DIR" ]; then
  echo "ERROR: Backup directory not found: $BACKUP_DIR"
  exit 1
fi

echo "Backup Contents:"
ls -lh "$BACKUP_DIR" | tail -10
echo ""

# TODO: Implement actual restore test logic
# This is a skeleton - customize for your environment

echo "IMPLEMENTATION REQUIRED:"
echo "  This is a skeleton script. Customize it for your deployment:"
echo ""
echo "  1. Verify backup integrity (GPG signature check)"
echo "  2. Verify tar archive is readable and not corrupted"
echo "  3. Stop target node's Delos service"
echo "  4. Extract backup to temporary directory"
echo "  5. Verify extracted files match expected structure"
echo "  6. Validate database schemas (run Liquibase)"
echo "  7. Check file permissions and ownership"
echo "  8. Clean up (unless --keep specified)"
echo "  9. Generate audit report"
echo ""
echo "BACKUP STRUCTURE:"
echo "  Expected directories:"
echo "    - keys/       (KERI identity keystores)"
echo "    - data/       (H2 databases)"
echo "    - config/     (delos.yaml)"
echo ""
echo "SEE ALSO:"
echo "  - DEPLOYMENT_GUIDE.md Section 5: Backup and Recovery"
echo "  - TROUBLESHOOTING_GUIDE.md Section 6: Database Issues"
echo ""

exit 1
