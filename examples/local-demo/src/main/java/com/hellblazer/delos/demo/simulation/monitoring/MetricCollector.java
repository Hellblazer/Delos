/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.monitoring;

import com.hellblazer.delos.demo.simulation.SimulationPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Collects metrics from Prometheus for degradation detection.
 * <p>
 * Queries Prometheus HTTP API to collect key performance metrics at regular intervals.
 * Stores snapshots locally for baseline calculation and trend analysis.
 *
 * @author hal.hildebrand
 */
public class MetricCollector {
    private static final Logger log = LoggerFactory.getLogger(MetricCollector.class);

    private static final List<String> KEY_METRICS = List.of(
        "choam_transactions_submitted_total",
        "choam_transactions_completed_total",
        "jvm_memory_used_bytes",
        "jvm_gc_pause_seconds_sum",
        "ff_accusations_total",
        "process_resident_memory_bytes",
        "jvm_threads_live"
    );

    private final String prometheusUrl;
    private final HttpClient httpClient;
    private final Path snapshotsDir;
    private final List<MetricsSnapshot> snapshots;

    public MetricCollector(String prometheusUrl, Path snapshotsDir) {
        this.prometheusUrl = prometheusUrl;
        this.httpClient = HttpClient.newBuilder()
                                    .connectTimeout(Duration.ofSeconds(10))
                                    .build();
        this.snapshotsDir = snapshotsDir;
        this.snapshots = new CopyOnWriteArrayList<>();

        try {
            Files.createDirectories(snapshotsDir);
        } catch (IOException e) {
            log.error("Failed to create snapshots directory", e);
        }
    }

    /**
     * Collect metrics from Prometheus and store as snapshot.
     *
     * @param phase current simulation phase
     * @return the collected snapshot
     */
    public MetricsSnapshot collectMetrics(SimulationPhase phase) {
        var timestamp = Instant.now();
        var values = new HashMap<String, Double>();

        for (var metric : KEY_METRICS) {
            try {
                var query = buildPromQLQuery(metric);
                var value = queryPrometheus(query);
                values.put(metric, value);
                log.debug("Collected metric {}: {}", metric, value);
            } catch (Exception e) {
                log.warn("Failed to collect metric {}: {}", metric, e.getMessage());
                values.put(metric, 0.0);
            }
        }

        var snapshot = createSnapshot(timestamp, values, phase);
        snapshots.add(snapshot);

        // Store snapshot to disk
        storeSnapshot(snapshot);

        return snapshot;
    }

    /**
     * Query Prometheus for a specific metric.
     *
     * @param query PromQL query
     * @return metric value
     */
    public double queryPrometheus(String query) throws IOException, InterruptedException {
        var encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        var uri = URI.create(prometheusUrl + "/api/v1/query?query=" + encodedQuery);

        var request = HttpRequest.newBuilder()
                                 .uri(uri)
                                 .timeout(Duration.ofSeconds(10))
                                 .GET()
                                 .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Prometheus query failed with status: " + response.statusCode());
        }

        return parsePrometheusValue(response.body());
    }

    /**
     * Get the latest snapshot.
     *
     * @return latest snapshot, or null if none collected yet
     */
    public MetricsSnapshot getLatestSnapshot() {
        return snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
    }

    /**
     * Get all snapshots within a time window.
     *
     * @param window time window to look back
     * @return list of snapshots within window
     */
    public List<MetricsSnapshot> getSnapshotsInWindow(Duration window) {
        var cutoff = Instant.now().minus(window);
        return snapshots.stream()
                       .filter(s -> s.timestamp().isAfter(cutoff))
                       .toList();
    }

    /**
     * Get all collected snapshots.
     *
     * @return all snapshots
     */
    public List<MetricsSnapshot> getAllSnapshots() {
        return new ArrayList<>(snapshots);
    }

    /**
     * Parse Prometheus JSON response to extract metric value.
     * <p>
     * Uses simple regex parsing to avoid external JSON dependencies.
     *
     * @param json Prometheus API response JSON
     * @return metric value
     */
    static double parsePrometheusValue(String json) {
        try {
            // Look for "result":[] - empty results
            if (json.contains("\"result\":[]") || json.contains("\"result\": []")) {
                return 0.0;
            }

            // Extract value using regex: "value":[timestamp,"123.45"]
            var pattern = Pattern.compile("\"value\":\\s*\\[\\s*[0-9.]+\\s*,\\s*\"([0-9.]+)\"\\s*\\]");
            var matcher = pattern.matcher(json);

            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }

            log.debug("No value found in Prometheus response");
            return 0.0;
        } catch (Exception e) {
            log.warn("Failed to parse Prometheus response: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Build PromQL query for a metric.
     * <p>
     * For counter metrics, use rate(). For gauge metrics, use as-is or max_over_time.
     *
     * @param metric metric name
     * @return PromQL query
     */
    static String buildPromQLQuery(String metric) {
        // Counter metrics - use rate
        if (metric.endsWith("_total") || metric.contains("transactions")) {
            return String.format("rate(%s[5m])", metric);
        }

        // Gauge metrics - use as-is
        return metric;
    }

    /**
     * Get the list of key metrics to collect.
     *
     * @return list of metric names
     */
    static List<String> getKeyMetrics() {
        return KEY_METRICS;
    }

    /**
     * Create a snapshot from collected values.
     *
     * @param timestamp when metrics were collected
     * @param values    metric values
     * @param phase     simulation phase
     * @return metrics snapshot
     */
    static MetricsSnapshot createSnapshot(Instant timestamp, Map<String, Double> values, SimulationPhase phase) {
        return new MetricsSnapshot(timestamp, Map.copyOf(values), phase);
    }

    /**
     * Store snapshot to disk as JSON.
     *
     * @param snapshot snapshot to store
     */
    private void storeSnapshot(MetricsSnapshot snapshot) {
        try {
            var filename = String.format("snapshot_%d.json", snapshot.timestamp().toEpochMilli());
            var path = snapshotsDir.resolve(filename);

            var json = formatSnapshotAsJson(snapshot);
            Files.writeString(path, json);

            log.debug("Stored snapshot to {}", path);
        } catch (IOException e) {
            log.error("Failed to store snapshot", e);
        }
    }

    /**
     * Format snapshot as JSON string.
     *
     * @param snapshot snapshot to format
     * @return JSON string
     */
    private String formatSnapshotAsJson(MetricsSnapshot snapshot) {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"timestamp\": \"").append(snapshot.timestamp()).append("\",\n");
        sb.append("  \"phase\": \"").append(snapshot.phase()).append("\",\n");
        sb.append("  \"metrics\": {\n");

        var entries = snapshot.values().entrySet().stream().toList();
        for (var i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            sb.append("    \"").append(entry.getKey()).append("\": ").append(entry.getValue());
            if (i < entries.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }
}
