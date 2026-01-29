/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.reporting;

import com.hellblazer.delos.demo.simulation.SimulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SimulationReporter.
 *
 * @author hal.hildebrand
 */
class SimulationReporterTest {

    @TempDir
    Path tempDir;

    private SimulationContext context;
    private SimulationReporter reporter;

    @BeforeEach
    void setUp() {
        var config = SimulationConfig.builder()
            .resultsDir(tempDir)
            .build();

        context = SimulationContext.builder()
            .config(config)
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(168)))
            .events(List.of(
                SimulationEvent.of(SimulationEvent.SimulationEventType.SIMULATION_STARTED,
                                  Instant.now(), "Started")
            ))
            .finalMetrics(Map.of("test_metric", 100.0))
            .healthChecksPassed(1000)
            .healthChecksFailed(0)
            .verificationsPassed(168)
            .verificationsFailed(0)
            .chaosScenarios(10)
            .chaosRecoveries(10)
            .slaViolations(List.of())
            .build();

        reporter = new SimulationReporter(context);
    }

    @Test
    void testGenerateReport() {
        reporter.generateReport();

        var reportsDir = context.config().getReportsDir();
        assertTrue(Files.exists(reportsDir));

        // Check that report files were created
        try (var stream = Files.list(reportsDir)) {
            var files = stream.toList();
            assertTrue(files.size() >= 3, "Should have at least HTML, JSON, and Markdown reports");
        } catch (IOException e) {
            fail("Could not list report directory: " + e.getMessage());
        }
    }

    @Test
    void testExportMetricsAsJson() throws IOException {
        var jsonPath = tempDir.resolve("test-metrics.json");

        reporter.exportMetricsAsJson(jsonPath);

        assertTrue(Files.exists(jsonPath));
        var content = Files.readString(jsonPath);
        assertTrue(content.contains("simulation"));
        assertTrue(content.contains("sla"));
    }

    @Test
    void testExportMetricsAsHtml() throws IOException {
        var htmlPath = tempDir.resolve("test-report.html");

        reporter.exportMetricsAsHtml(htmlPath);

        assertTrue(Files.exists(htmlPath));
        var content = Files.readString(htmlPath);
        assertTrue(content.contains("<!DOCTYPE html>"));
        assertTrue(content.contains("Simulation Report"));
    }

    @Test
    void testExportMetricsAsMarkdown() throws IOException {
        var mdPath = tempDir.resolve("test-report.md");

        reporter.exportMetricsAsMarkdown(mdPath);

        assertTrue(Files.exists(mdPath));
        var content = Files.readString(mdPath);
        assertTrue(content.contains("# Simulation Report"));
    }

    @Test
    void testGenerateExecutiveSummary() {
        var summary = reporter.generateExecutiveSummary();

        assertNotNull(summary);
        assertTrue(summary.passed());
        assertEquals(168, summary.verificationsPassed());
        assertEquals(0, summary.verificationsFailed());
    }

    @Test
    void testGetContext() {
        assertEquals(context, reporter.getContext());
    }

    @Test
    void testGetReportPath() {
        reporter.generateReport();
        var reportPath = reporter.getReportPath();

        assertNotNull(reportPath);
        assertTrue(Files.exists(reportPath));
        assertTrue(reportPath.toString().endsWith(".html"));
    }

    @Test
    void testReportPathBeforeGeneration() {
        var reportPath = reporter.getReportPath();
        assertNull(reportPath);
    }

    @Test
    void testGenerateReportCreatesDirectory() {
        var config = SimulationConfig.builder()
            .resultsDir(tempDir.resolve("new-results"))
            .build();

        var newContext = SimulationContext.builder()
            .config(config)
            .startTime(Instant.now())
            .build();

        var newReporter = new SimulationReporter(newContext);

        assertFalse(Files.exists(config.getReportsDir()));

        newReporter.generateReport();

        assertTrue(Files.exists(config.getReportsDir()));
    }

    @Test
    void testGenerateReportWithFailedSimulation() {
        var failedConfig = SimulationConfig.builder()
            .resultsDir(tempDir.resolve("failed-results"))
            .build();

        var failedContext = SimulationContext.builder()
            .config(failedConfig)
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(100)))
            .verificationsFailed(5)
            .slaViolations(List.of("Latency SLA violated"))
            .build();

        var failedReporter = new SimulationReporter(failedContext);

        failedReporter.generateReport();

        var reportsDir = failedConfig.getReportsDir();
        assertTrue(Files.exists(reportsDir));

        var summary = failedReporter.generateExecutiveSummary();
        assertFalse(summary.passed());
    }

    @Test
    void testMultipleReportsCreateUniqueFiles() throws InterruptedException {
        reporter.generateReport();

        // Small delay to ensure different timestamp
        Thread.sleep(10);

        var reporter2 = new SimulationReporter(context);
        reporter2.generateReport();

        var reportsDir = context.config().getReportsDir();

        try (var stream = Files.list(reportsDir)) {
            var htmlFiles = stream.filter(p -> p.toString().endsWith(".html"))
                                 .filter(p -> !p.toString().contains("latest"))
                                 .toList();
            assertTrue(htmlFiles.size() >= 2, "Should create multiple unique report files");
        } catch (IOException e) {
            fail("Could not list report directory: " + e.getMessage());
        }
    }
}
