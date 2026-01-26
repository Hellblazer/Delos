# Delos Node Container Image
#
# Multi-stage build:
# 1. Build stage uses Maven to create fat JAR
# 2. Runtime stage uses minimal JRE image
#
# Usage:
#   Build:  mvn package -Pdocker
#   Or:     docker build -t delos-node .
#
# Ports:
#   9999 - gRPC endpoint
#   9090 - Prometheus metrics

# =============================================================================
# Runtime Stage
# =============================================================================
FROM eclipse-temurin:24-jre-alpine

LABEL maintainer="hal.hildebrand@gmail.com"
LABEL description="Delos distributed system node"
LABEL version="0.2.3-SNAPSHOT"

# Install required tools
RUN apk add --no-cache \
    curl \
    jq \
    dumb-init

# Create non-root user for security
RUN addgroup -S delos && adduser -S delos -G delos

# Create directories
RUN mkdir -p /app /data/checkpoints && \
    chown -R delos:delos /app /data

# Set working directory
WORKDIR /app

# Copy the fat JAR from Maven build
COPY --chown=delos:delos target/delos-node.jar /app/delos-node.jar

# Copy logback configuration if present
COPY --chown=delos:delos src/main/resources/logback*.xml /app/ 2>/dev/null || true

# Switch to non-root user
USER delos

# Environment variables with defaults
ENV DELOS_NODE_TYPE=member \
    DELOS_BOOTSTRAP_HOST=bootstrap \
    DELOS_BOOTSTRAP_PORT=9999 \
    DELOS_GRPC_PORT=9999 \
    DELOS_METRICS_PORT=9090 \
    DELOS_CARDINALITY=10 \
    DELOS_BIAS=3 \
    DELOS_PBYZ=0.1 \
    DELOS_GOSSIP_DURATION_MS=100 \
    DELOS_DRAIN_PERIOD_MS=500 \
    DELOS_SEEDING_TIMEOUT_S=30 \
    DELOS_DATA_DIR=/data \
    DELOS_CHECKPOINT_DIR=/data/checkpoints \
    DELOS_MAX_HEAP_MB=512 \
    JAVA_OPTS=""

# Expose ports
EXPOSE 9999 9090

# Health check - verify node is responding
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:${DELOS_METRICS_PORT}/metrics || exit 1

# Volume for persistent data
VOLUME ["/data"]

# Use dumb-init as PID 1 for proper signal handling
ENTRYPOINT ["/usr/bin/dumb-init", "--"]

# Run the Delos node
CMD ["sh", "-c", "exec java \
    -Xmx${DELOS_MAX_HEAP_MB}m \
    -Xms$((DELOS_MAX_HEAP_MB / 2))m \
    -XX:+UseZGC \
    -XX:+ZGenerational \
    -Djava.net.preferIPv4Stack=true \
    -Dlogback.configurationFile=/app/logback.xml \
    ${JAVA_OPTS} \
    -jar /app/delos-node.jar"]
