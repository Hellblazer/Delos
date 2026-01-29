/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Main reporting orchestrator for simulation results.
 * <p>
 * This class aggregates all simulation data and generates comprehensive reports
 * in multiple formats (HTML, JSON, Markdown) for different audiences and purposes.
 *
 * @author hal.hildebrand
 */
public class SimulationReporter {
    private static final Logger log = LoggerFactory.getLogger(SimulationReporter.class);

    private final SimulationContext context;
    private final ReportGenerator generator;
    private Path lastReportPath;

    public SimulationReporter(SimulationContext context) {
        this.context = context;
        this.generator = new ReportGenerator(context);
    }

    /**
     * Generate all report formats.
     * <p>
     * Creates HTML, JSON, and Markdown reports in the configured reports directory.
     */
    public void generateReport() {
        log.info("Generating simulation reports...");

        try {
            var reportsDir = context.config().getReportsDir();
            ensureDirectoryExists(reportsDir);

            // Generate timestamp for unique filenames
            var timestamp = Instant.now().toString().replace(":", "-");

            // Generate HTML report
            var htmlPath = reportsDir.resolve("simulation-report-" + timestamp + ".html");
            exportMetricsAsHtml(htmlPath);

            // Generate JSON report for CI/CD
            var jsonPath = reportsDir.resolve("simulation-metrics-" + timestamp + ".json");
            exportMetricsAsJson(jsonPath);

            // Generate Markdown report for GitHub
            var mdPath = reportsDir.resolve("simulation-report-" + timestamp + ".md");
            exportMetricsAsMarkdown(mdPath);

            // Create symlinks to latest reports
            createLatestSymlink(htmlPath, reportsDir.resolve("simulation-report-latest.html"));
            createLatestSymlink(jsonPath, reportsDir.resolve("simulation-metrics-latest.json"));
            createLatestSymlink(mdPath, reportsDir.resolve("simulation-report-latest.md"));

            this.lastReportPath = htmlPath;

            log.info("Reports generated successfully:");
            log.info("  HTML: {}", htmlPath);
            log.info("  JSON: {}", jsonPath);
            log.info("  Markdown: {}", mdPath);

            // Print executive summary to log
            logExecutiveSummary();

        } catch (Exception e) {
            log.error("Failed to generate reports", e);
            throw new RuntimeException("Report generation failed", e);
        }
    }

    /**
     * Export metrics as JSON for CI/CD integration.
     */
    public void exportMetricsAsJson(Path outputPath) throws IOException {
        log.info("Exporting metrics as JSON to: {}", outputPath);
        generator.writeJsonReport(outputPath);
    }

    /**
     * Export metrics as HTML with embedded charts.
     */
    public void exportMetricsAsHtml(Path outputPath) throws IOException {
        log.info("Exporting metrics as HTML to: {}", outputPath);
        generator.writeHtmlReport(outputPath);
    }

    /**
     * Export metrics as Markdown for GitHub.
     */
    public void exportMetricsAsMarkdown(Path outputPath) throws IOException {
        log.info("Exporting metrics as Markdown to: {}", outputPath);
        generator.writeMarkdownReport(outputPath);
    }

    /**
     * Generate and return an executive summary.
     */
    public ExecutiveSummary generateExecutiveSummary() {
        return ExecutiveSummary.from(context);
    }

    /**
     * Get the path to the most recently generated HTML report.
     */
    public Path getReportPath() {
        return lastReportPath;
    }

    /**
     * Get the simulation context.
     */
    public SimulationContext getContext() {
        return context;
    }

    /**
     * Log executive summary to console.
     */
    private void logExecutiveSummary() {
        var summary = generateExecutiveSummary();

        log.info("=== SIMULATION EXECUTIVE SUMMARY ===");
        log.info("Status: {} (Grade: {})", summary.getStatusString(), summary.getGrade());
        log.info("Duration: {}", formatDuration(summary.duration()));
        log.info("Uptime: {}%", String.format("%.2f", summary.uptimePercent()));
        log.info("Verifications: {} passed, {} failed",
                 summary.verificationsPassed(), summary.verificationsFailed());
        log.info("Chaos Scenarios: {} total, {}% recovery rate",
                 summary.chaosScenarioCount(), String.format("%.1f", summary.chaosRecoveryRate()));
        log.info("Major Incidents: {}", summary.majorIncidentsCount());

        if (!summary.slaViolations().isEmpty()) {
            log.warn("SLA Violations:");
            summary.slaViolations().forEach(v -> log.warn("  - {}", v));
        }

        summary.failureReason().ifPresent(reason -> log.error("Failure Reason: {}", reason));

        log.info("Score: {}/100", String.format("%.2f", summary.calculateScore()));
        log.info("=====================================");
    }

    /**
     * Ensure directory exists, create if necessary.
     */
    private void ensureDirectoryExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.debug("Created directory: {}", dir);
        }
    }

    /**
     * Create symlink to latest report (best effort).
     */
    private void createLatestSymlink(Path target, Path linkPath) {
        try {
            // Delete existing symlink if present
            Files.deleteIfExists(linkPath);

            // Create new symlink
            Files.createSymbolicLink(linkPath, target.getFileName());
            log.debug("Created symlink: {} -> {}", linkPath, target.getFileName());
        } catch (IOException | UnsupportedOperationException e) {
            // Symlinks may not be supported on all platforms
            log.debug("Could not create symlink (not critical): {}", e.getMessage());

            // Fallback: copy file instead
            try {
                Files.copy(target, linkPath);
                log.debug("Copied file instead of symlink: {}", linkPath);
            } catch (IOException copyError) {
                log.debug("Could not copy file either: {}", copyError.getMessage());
            }
        }
    }

    /**
     * Format duration as human-readable string.
     */
    private String formatDuration(java.time.Duration duration) {
        var hours = duration.toHours();
        var minutes = duration.toMinutesPart();
        var seconds = duration.toSecondsPart();
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }
}
