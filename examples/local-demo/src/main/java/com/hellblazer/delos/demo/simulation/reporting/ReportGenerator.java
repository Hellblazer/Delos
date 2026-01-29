/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates simulation reports in various formats (HTML, JSON, Markdown).
 * <p>
 * This class handles the actual content generation and formatting for each
 * report type, using templates where appropriate.
 *
 * @author hal.hildebrand
 */
public class ReportGenerator {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON_MAPPER = createJsonMapper();

    private final SimulationContext context;
    private final ExecutiveSummary summary;

    public ReportGenerator(SimulationContext context) {
        this.context = context;
        this.summary = ExecutiveSummary.from(context);
    }

    /**
     * Generate a complete HTML report with embedded charts.
     */
    public String generateHtmlReport() {
        var template = loadHtmlTemplate();
        return template
            .replace("{{TITLE}}", "Simulation Report: " + formatTimestamp(context.startTime()))
            .replace("{{EXECUTIVE_SUMMARY}}", renderExecutiveSummary())
            .replace("{{KEY_METRICS}}", renderKeyMetrics())
            .replace("{{TIMELINE}}", renderTimeline())
            .replace("{{VERIFICATION_RESULTS}}", renderVerificationResults())
            .replace("{{CHAOS_RESULTS}}", renderChaosResults())
            .replace("{{SLA_COMPLIANCE}}", renderSlaCompliance())
            .replace("{{CHART_DATA}}", generateChartData())
            .replace("{{RECOMMENDATIONS}}", renderRecommendations());
    }

    /**
     * Generate a JSON report suitable for CI/CD integration.
     */
    public String generateJsonReport() throws IOException {
        var report = new HashMap<String, Object>();

        var simulation = new HashMap<String, Object>();
        simulation.put("startTime", context.startTime());
        simulation.put("endTime", context.endTime().orElse(null));
        simulation.put("duration", formatDuration(context.getDuration()));
        simulation.put("status", summary.getStatusString());
        simulation.put("nodeCount", context.config().nodeCount());
        report.put("simulation", simulation);

        var sla = new HashMap<String, Object>();
        context.finalMetrics().forEach((key, value) -> {
            var metric = new HashMap<String, Object>();
            metric.put("value", value);
            metric.put("passed", !context.slaViolations().contains(key));
            sla.put(key, metric);
        });
        report.put("sla", sla);

        var events = context.events().stream()
            .map(e -> Map.of(
                "time", e.timestamp(),
                "type", e.type().name(),
                "message", e.message(),
                "severity", e.getSeverity().name()
            ))
            .collect(Collectors.toList());
        report.put("events", events);

        report.put("summary", Map.of(
            "passed", summary.passed(),
            "uptimePercent", summary.uptimePercent(),
            "grade", summary.getGrade(),
            "score", summary.calculateScore()
        ));

        return JSON_MAPPER.writeValueAsString(report);
    }

    /**
     * Generate a Markdown report suitable for GitHub.
     */
    public String generateMarkdownReport() {
        var md = new StringBuilder();

        md.append("# Simulation Report\n\n");
        md.append(String.format("**Start Time:** %s\n\n", formatTimestamp(context.startTime())));
        md.append(String.format("**Duration:** %s\n\n", formatDuration(context.getDuration())));
        md.append(String.format("**Status:** %s (%s)\n\n", summary.getStatusString(), summary.getGrade()));

        md.append("## Executive Summary\n\n");
        md.append(String.format("- **Uptime:** %.2f%%\n", summary.uptimePercent()));
        md.append(String.format("- **Verifications:** %d passed, %d failed\n",
                                summary.verificationsPassed(), summary.verificationsFailed()));
        md.append(String.format("- **Chaos Scenarios:** %d total, %.1f%% recovery rate\n",
                                summary.chaosScenarioCount(), summary.chaosRecoveryRate()));
        md.append(String.format("- **Major Incidents:** %d\n\n", summary.majorIncidentsCount()));

        if (!context.slaViolations().isEmpty()) {
            md.append("## SLA Violations\n\n");
            context.slaViolations().forEach(v -> md.append(String.format("- %s\n", v)));
            md.append("\n");
        }

        md.append("## Key Metrics\n\n");
        md.append("| Metric | Value |\n");
        md.append("|--------|-------|\n");
        context.finalMetrics().forEach((key, value) -> {
            md.append(String.format("| %s | %.2f |\n", key, value));
        });
        md.append("\n");

        md.append("## Event Timeline\n\n");
        md.append("| Time | Event | Message |\n");
        md.append("|------|-------|----------|\n");
        context.events().stream()
               .limit(50) // Limit to first 50 events
               .forEach(e -> md.append(String.format("| %s | %s | %s |\n",
                                                     formatTimestamp(e.timestamp()),
                                                     e.type().name(),
                                                     e.message())));

        return md.toString();
    }

    /**
     * Write HTML report to file.
     */
    public void writeHtmlReport(Path outputPath) throws IOException {
        Files.writeString(outputPath, generateHtmlReport());
    }

    /**
     * Write JSON report to file.
     */
    public void writeJsonReport(Path outputPath) throws IOException {
        Files.writeString(outputPath, generateJsonReport());
    }

    /**
     * Write Markdown report to file.
     */
    public void writeMarkdownReport(Path outputPath) throws IOException {
        Files.writeString(outputPath, generateMarkdownReport());
    }

    // Private helper methods

    private String renderExecutiveSummary() {
        return String.format("""
            <div class="card mb-4">
              <div class="card-header">
                <h5>Executive Summary</h5>
              </div>
              <div class="card-body">
                <div class="row">
                  <div class="col-md-6">
                    <p><strong>Status:</strong> <span class="badge bg-%s">%s</span></p>
                    <p><strong>Grade:</strong> <span class="badge bg-info">%s</span></p>
                    <p><strong>Duration:</strong> %s</p>
                    <p><strong>Cluster Size:</strong> %d nodes</p>
                  </div>
                  <div class="col-md-6">
                    <p><strong>Uptime:</strong> %.2f%%</p>
                    <p><strong>Major Incidents:</strong> %d</p>
                    <p><strong>Chaos Recovery Rate:</strong> %.1f%%</p>
                  </div>
                </div>
                %s
              </div>
            </div>
            """,
            summary.passed() ? "success" : "danger",
            summary.getStatusString(),
            summary.getGrade(),
            formatDuration(summary.duration()),
            context.config().nodeCount(),
            summary.uptimePercent(),
            summary.majorIncidentsCount(),
            summary.chaosRecoveryRate(),
            summary.failureReason().map(r -> "<div class=\"alert alert-danger\">Failure: " + r + "</div>").orElse("")
        );
    }

    private String renderKeyMetrics() {
        var metrics = new StringBuilder();
        metrics.append("""
            <div class="card mb-4">
              <div class="card-header">
                <h5>Key Metrics</h5>
              </div>
              <div class="card-body">
                <table class="table table-striped">
                  <thead>
                    <tr>
                      <th>Metric</th>
                      <th>Value</th>
                    </tr>
                  </thead>
                  <tbody>
            """);

        context.finalMetrics().forEach((key, value) -> {
            metrics.append(String.format("""
                    <tr>
                      <td>%s</td>
                      <td>%.2f</td>
                    </tr>
                """, key, value));
        });

        metrics.append("""
                  </tbody>
                </table>
              </div>
            </div>
            """);

        return metrics.toString();
    }

    private String renderTimeline() {
        var timeline = new StringBuilder();
        timeline.append("""
            <div class="card mb-4">
              <div class="card-header">
                <h5>Event Timeline (First 100 Events)</h5>
              </div>
              <div class="card-body">
                <div class="timeline" style="max-height: 400px; overflow-y: auto;">
            """);

        context.events().stream()
               .limit(100)
               .forEach(event -> {
                   var badgeClass = switch (event.getSeverity()) {
                       case INFO -> "bg-info";
                       case WARNING -> "bg-warning";
                       case ERROR -> "bg-danger";
                       case CRITICAL -> "bg-dark";
                   };
                   timeline.append(String.format("""
                       <div class="timeline-event mb-2">
                         <span class="badge %s">%s</span>
                         <small class="text-muted">%s</small>
                         <strong>%s</strong>: %s
                       </div>
                       """,
                       badgeClass,
                       event.type().name(),
                       formatTimestamp(event.timestamp()),
                       event.type().name(),
                       event.message()
                   ));
               });

        timeline.append("""
                </div>
              </div>
            </div>
            """);

        return timeline.toString();
    }

    private String renderVerificationResults() {
        return String.format("""
            <div class="card mb-4">
              <div class="card-header">
                <h5>Verification Results</h5>
              </div>
              <div class="card-body">
                <div class="row">
                  <div class="col-md-6">
                    <div class="text-center">
                      <h3 class="text-success">%d</h3>
                      <p>Passed</p>
                    </div>
                  </div>
                  <div class="col-md-6">
                    <div class="text-center">
                      <h3 class="text-danger">%d</h3>
                      <p>Failed</p>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            """,
            context.verificationsPassed(),
            context.verificationsFailed()
        );
    }

    private String renderChaosResults() {
        return String.format("""
            <div class="card mb-4">
              <div class="card-header">
                <h5>Chaos Engineering Results</h5>
              </div>
              <div class="card-body">
                <p><strong>Total Scenarios:</strong> %d</p>
                <p><strong>Successful Recoveries:</strong> %d</p>
                <p><strong>Recovery Rate:</strong> %.1f%%</p>
                <p><strong>Average Recovery Time:</strong> %s</p>
              </div>
            </div>
            """,
            context.chaosScenarios(),
            context.chaosRecoveries(),
            context.getChaosRecoveryRate(),
            formatDuration(summary.averageRecoveryTime())
        );
    }

    private String renderSlaCompliance() {
        var sla = new StringBuilder();
        sla.append("""
            <div class="card mb-4">
              <div class="card-header">
                <h5>SLA Compliance</h5>
              </div>
              <div class="card-body">
            """);

        if (context.slaViolations().isEmpty()) {
            sla.append("<div class=\"alert alert-success\">All SLAs met</div>");
        } else {
            sla.append("<div class=\"alert alert-danger\">SLA Violations:</div>");
            sla.append("<ul>");
            context.slaViolations().forEach(v -> sla.append(String.format("<li>%s</li>", v)));
            sla.append("</ul>");
        }

        sla.append("""
              </div>
            </div>
            """);

        return sla.toString();
    }

    private String generateChartData() {
        // Generate JavaScript data for Chart.js
        return String.format("""
            const eventCounts = %s;
            const uptimeData = {
              passed: %d,
              failed: %d
            };
            """,
            JSON_MAPPER.valueToTree(context.countEventsBySeverity()),
            context.healthChecksPassed(),
            context.healthChecksFailed()
        );
    }

    private String renderRecommendations() {
        var recommendations = new StringBuilder();
        recommendations.append("""
            <div class="card mb-4">
              <div class="card-header">
                <h5>Recommendations</h5>
              </div>
              <div class="card-body">
                <ul>
            """);

        if (summary.passed()) {
            recommendations.append("<li>All systems nominal - no action required</li>");
            if (summary.chaosRecoveryRate() < 100.0) {
                recommendations.append("<li>Consider improving chaos recovery procedures</li>");
            }
        } else {
            recommendations.append("<li>Review failure incidents and root causes</li>");
            if (!context.slaViolations().isEmpty()) {
                recommendations.append("<li>Address SLA violations as priority</li>");
            }
            if (context.verificationsFailed() > 0) {
                recommendations.append("<li>Investigate verification failures</li>");
            }
        }

        recommendations.append("""
                </ul>
              </div>
            </div>
            """);

        return recommendations.toString();
    }

    private String loadHtmlTemplate() {
        try {
            var resource = getClass().getResourceAsStream("/templates/simulation-report-template.html");
            if (resource != null) {
                return new String(resource.readAllBytes());
            }
        } catch (IOException e) {
            // Fall back to embedded template
        }

        // Embedded fallback template
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>{{TITLE}}</title>
              <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
              <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
              <style>
                body { padding: 20px; }
                .timeline-event { border-left: 3px solid #ddd; padding-left: 10px; }
              </style>
            </head>
            <body>
              <div class="container">
                <h1>{{TITLE}}</h1>
                {{EXECUTIVE_SUMMARY}}
                {{KEY_METRICS}}
                {{TIMELINE}}
                {{VERIFICATION_RESULTS}}
                {{CHAOS_RESULTS}}
                {{SLA_COMPLIANCE}}
                {{RECOMMENDATIONS}}
              </div>
              <script>
                {{CHART_DATA}}
              </script>
            </body>
            </html>
            """;
    }

    private String formatTimestamp(java.time.Instant instant) {
        return instant.atZone(java.time.ZoneId.systemDefault()).format(TIMESTAMP_FORMAT);
    }

    private String formatDuration(Duration duration) {
        var hours = duration.toHours();
        var minutes = duration.toMinutesPart();
        var seconds = duration.toSecondsPart();
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }

    private static ObjectMapper createJsonMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        return mapper;
    }
}
