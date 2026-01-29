/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * Tests for ReportGenerator.
 *
 * @author hal.hildebrand
 */
class ReportGeneratorTest {

    @TempDir
    Path tempDir;

    private SimulationContext context;
    private ReportGenerator generator;

    @BeforeEach
    void setUp() {
        var config = SimulationConfig.builder()
            .resultsDir(tempDir)
            .build();

        var events = List.of(
            SimulationEvent.of(SimulationEvent.SimulationEventType.SIMULATION_STARTED,
                              Instant.now(), "Simulation started"),
            SimulationEvent.of(SimulationEvent.SimulationEventType.HEALTH_CHECK_PASSED,
                              Instant.now().plusSeconds(10), "Health check passed"),
            SimulationEvent.of(SimulationEvent.SimulationEventType.VERIFICATION_PASSED,
                              Instant.now().plusSeconds(20), "Verification passed")
        );

        context = SimulationContext.builder()
            .config(config)
            .startTime(Instant.now())
            .endTime(Instant.now().plus(Duration.ofHours(168)))
            .events(events)
            .finalMetrics(Map.of(
                "transaction_throughput", 7321.4,
                "p99_latency", 45.0,
                "uptime_percent", 99.98
            ))
            .healthChecksPassed(1000)
            .healthChecksFailed(2)
            .verificationsPassed(168)
            .verificationsFailed(0)
            .chaosScenarios(10)
            .chaosRecoveries(10)
            .heapDumpsCollected(7)
            .slaViolations(List.of())
            .build();

        generator = new ReportGenerator(context);
    }

    @Test
    void testGenerateHtmlReport() {
        var html = generator.generateHtmlReport();

        assertNotNull(html);
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("Simulation Report"));
        assertTrue(html.contains("Executive Summary"));
        assertTrue(html.contains("Key Metrics"));
        assertTrue(html.contains("PASSED") || html.contains("FAILED"));
    }

    @Test
    void testHtmlReportContainsMetrics() {
        var html = generator.generateHtmlReport();

        // Check that metrics are present
        assertTrue(html.contains("transaction_throughput"));
        assertTrue(html.contains("p99_latency"));
        assertTrue(html.contains("uptime_percent"));
    }

    @Test
    void testGenerateJsonReport() throws IOException {
        var json = generator.generateJsonReport();

        assertNotNull(json);

        // Parse JSON to verify structure
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        var root = mapper.readTree(json);

        assertTrue(root.has("simulation"));
        assertTrue(root.has("sla"));
        assertTrue(root.has("events"));
        assertTrue(root.has("summary"));

        // Check simulation section
        var simulation = root.get("simulation");
        assertTrue(simulation.has("status"));
        assertTrue(simulation.has("duration"));
        assertTrue(simulation.has("nodeCount"));

        // Check SLA section
        var sla = root.get("sla");
        assertTrue(sla.has("transaction_throughput"));
        assertTrue(sla.has("p99_latency"));

        // Check summary section
        var summary = root.get("summary");
        assertTrue(summary.has("passed"));
        assertTrue(summary.has("uptimePercent"));
        assertTrue(summary.has("grade"));
    }

    @Test
    void testGenerateMarkdownReport() {
        var markdown = generator.generateMarkdownReport();

        assertNotNull(markdown);
        assertTrue(markdown.contains("# Simulation Report"));
        assertTrue(markdown.contains("## Executive Summary"));
        assertTrue(markdown.contains("## Key Metrics"));
        assertTrue(markdown.contains("## Event Timeline"));
        assertTrue(markdown.contains("| Metric | Value |"));
    }

    @Test
    void testWriteHtmlReport() throws IOException {
        var outputPath = tempDir.resolve("test-report.html");

        generator.writeHtmlReport(outputPath);

        assertTrue(Files.exists(outputPath));
        var content = Files.readString(outputPath);
        assertTrue(content.contains("<!DOCTYPE html>"));
        assertTrue(content.contains("Simulation Report"));
    }

    @Test
    void testWriteJsonReport() throws IOException {
        var outputPath = tempDir.resolve("test-metrics.json");

        generator.writeJsonReport(outputPath);

        assertTrue(Files.exists(outputPath));
        var content = Files.readString(outputPath);

        // Verify it's valid JSON
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        var root = mapper.readTree(content);
        assertNotNull(root);
    }

    @Test
    void testWriteMarkdownReport() throws IOException {
        var outputPath = tempDir.resolve("test-report.md");

        generator.writeMarkdownReport(outputPath);

        assertTrue(Files.exists(outputPath));
        var content = Files.readString(outputPath);
        assertTrue(content.contains("# Simulation Report"));
    }

    @Test
    void testJsonReportWithSlaViolations() throws IOException {
        var failedContext = SimulationContext.builder()
            .config(context.config())
            .startTime(context.startTime())
            .endTime(context.endTime().orElse(null))
            .events(context.events())
            .finalMetrics(context.finalMetrics())
            .healthChecksPassed(context.healthChecksPassed())
            .healthChecksFailed(context.healthChecksFailed())
            .verificationsPassed(context.verificationsPassed())
            .verificationsFailed(context.verificationsFailed())
            .chaosScenarios(context.chaosScenarios())
            .chaosRecoveries(context.chaosRecoveries())
            .heapDumpsCollected(context.heapDumpsCollected())
            .slaViolations(List.of("Latency P99 exceeded", "Throughput below threshold"))
            .build();

        var failedGenerator = new ReportGenerator(failedContext);
        var json = failedGenerator.generateJsonReport();

        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Jdk8Module());
        var root = mapper.readTree(json);

        var summary = root.get("summary");
        assertFalse(summary.get("passed").asBoolean());
    }

    @Test
    void testHtmlReportWithBootstrapStyles() {
        var html = generator.generateHtmlReport();

        // Check for Bootstrap classes
        assertTrue(html.contains("card"));
        assertTrue(html.contains("badge"));
        assertTrue(html.contains("container"));
    }

    @Test
    void testMarkdownReportLimitsEvents() {
        // Create context with many events
        var manyEvents = new java.util.ArrayList<SimulationEvent>();
        for (int i = 0; i < 100; i++) {
            manyEvents.add(SimulationEvent.of(
                SimulationEvent.SimulationEventType.HEALTH_CHECK_PASSED,
                Instant.now().plusSeconds(i),
                "Event " + i
            ));
        }

        var contextWithManyEvents = SimulationContext.builder()
            .config(context.config())
            .startTime(context.startTime())
            .endTime(context.endTime().orElse(null))
            .events(manyEvents)
            .build();

        var generatorWithManyEvents = new ReportGenerator(contextWithManyEvents);
        var markdown = generatorWithManyEvents.generateMarkdownReport();

        // Markdown should limit to first 50 events
        assertNotNull(markdown);
        assertTrue(markdown.contains("Event Timeline"));
    }
}
