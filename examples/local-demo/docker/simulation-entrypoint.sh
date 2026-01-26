#!/bin/bash
#
# Copyright (c) 2026, Hal Hildebrand.
# All rights reserved.
# GNU Affero General Public License
# For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
# This file is part of the Delos Distributed Systems Framework.
#
# Simulation Cluster Entrypoint Script
#
# This script prepares and starts a Delos cluster for extended simulation testing.
# It handles Docker daemon verification, cluster scaling, health checks, and validation.
#
# Usage:
#   ./simulation-entrypoint.sh <compose-file> <node-count>
#
# Example:
#   ./simulation-entrypoint.sh compose-simulation.yaml 100
#

set -euo pipefail

# Configuration
COMPOSE_FILE="${1:-compose-simulation.yaml}"
NODE_COUNT="${2:-100}"
HEALTH_CHECK_RETRIES=30
HEALTH_CHECK_INTERVAL=10

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Docker is available
check_docker() {
    log_info "Checking Docker availability..."

    if ! command -v docker &> /dev/null; then
        log_error "Docker is not installed or not in PATH"
        return 1
    fi

    if ! docker info &> /dev/null; then
        log_error "Docker daemon is not running"
        return 1
    fi

    log_info "Docker is available: $(docker --version)"
    return 0
}

# Check if Docker Compose is available
check_docker_compose() {
    log_info "Checking Docker Compose availability..."

    if ! docker compose version &> /dev/null; then
        log_error "Docker Compose is not available"
        return 1
    fi

    log_info "Docker Compose is available: $(docker compose version)"
    return 0
}

# Validate compose file exists
validate_compose_file() {
    log_info "Validating compose file: ${COMPOSE_FILE}"

    if [[ ! -f "${COMPOSE_FILE}" ]]; then
        log_error "Compose file not found: ${COMPOSE_FILE}"
        return 1
    fi

    if ! docker compose -f "${COMPOSE_FILE}" config > /dev/null 2>&1; then
        log_error "Invalid compose file: ${COMPOSE_FILE}"
        docker compose -f "${COMPOSE_FILE}" config
        return 1
    fi

    log_info "Compose file is valid"
    return 0
}

# Calculate member node count
calculate_member_count() {
    local total_nodes="$1"
    local genesis_nodes=4  # bootstrap + 3 kernels
    local member_count=$((total_nodes - genesis_nodes))

    if [[ ${member_count} -lt 0 ]]; then
        log_error "Node count must be at least 4 (for genesis nodes)"
        return 1
    fi

    echo "${member_count}"
    return 0
}

# Start cluster
start_cluster() {
    local member_count="$1"

    log_info "Starting Delos cluster: ${NODE_COUNT} total nodes (4 genesis + ${member_count} members)"

    docker compose -f "${COMPOSE_FILE}" up -d --scale node="${member_count}"

    if [[ $? -ne 0 ]]; then
        log_error "Failed to start cluster"
        return 1
    fi

    log_info "Cluster started successfully"
    return 0
}

# Wait for service health
wait_for_service() {
    local service_name="$1"
    local health_url="$2"
    local retries="${HEALTH_CHECK_RETRIES}"

    log_info "Waiting for ${service_name} to become healthy..."

    while [[ ${retries} -gt 0 ]]; do
        if curl -f -s "${health_url}" > /dev/null 2>&1; then
            log_info "${service_name} is healthy"
            return 0
        fi

        retries=$((retries - 1))
        log_warn "${service_name} not ready yet. Retries remaining: ${retries}"
        sleep "${HEALTH_CHECK_INTERVAL}"
    done

    log_error "${service_name} did not become healthy within timeout"
    return 1
}

# Check cluster health
check_cluster_health() {
    log_info "Checking cluster health..."

    # Check bootstrap node
    if ! wait_for_service "bootstrap" "http://localhost:8080/health"; then
        log_error "Bootstrap node health check failed"
        docker compose -f "${COMPOSE_FILE}" logs bootstrap
        return 1
    fi

    # Check Prometheus
    if ! wait_for_service "prometheus" "http://localhost:9091/-/healthy"; then
        log_error "Prometheus health check failed"
        docker compose -f "${COMPOSE_FILE}" logs prometheus
        return 1
    fi

    # Check Grafana
    if ! wait_for_service "grafana" "http://localhost:3000/api/health"; then
        log_warn "Grafana health check failed (non-critical)"
    fi

    log_info "Cluster health checks passed"
    return 0
}

# Verify container count
verify_container_count() {
    local expected_containers=$((NODE_COUNT + 2))  # nodes + prometheus + grafana

    log_info "Verifying container count..."

    local running_containers
    running_containers=$(docker compose -f "${COMPOSE_FILE}" ps --format json | jq -r 'select(.State == "running")' | wc -l | tr -d ' ')

    log_info "Running containers: ${running_containers} (expected: ~${expected_containers})"

    if [[ ${running_containers} -lt ${NODE_COUNT} ]]; then
        log_warn "Not all expected containers are running"
        docker compose -f "${COMPOSE_FILE}" ps
    fi

    return 0
}

# Show cluster status
show_cluster_status() {
    log_info "Cluster Status:"
    docker compose -f "${COMPOSE_FILE}" ps
}

# Main execution
main() {
    log_info "=== Delos Simulation Cluster Entrypoint ==="
    log_info "Compose File: ${COMPOSE_FILE}"
    log_info "Node Count: ${NODE_COUNT}"

    # Validate environment
    check_docker || exit 1
    check_docker_compose || exit 1
    validate_compose_file || exit 1

    # Calculate member count
    MEMBER_COUNT=$(calculate_member_count "${NODE_COUNT}")
    if [[ $? -ne 0 ]]; then
        exit 1
    fi

    # Start cluster
    start_cluster "${MEMBER_COUNT}" || exit 1

    # Wait for health
    check_cluster_health || exit 1

    # Verify containers
    verify_container_count || exit 1

    # Show status
    show_cluster_status

    log_info "=== Cluster ready for simulation ==="
    exit 0
}

# Run main
main "$@"
