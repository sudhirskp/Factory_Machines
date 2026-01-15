package com.factory.controller;

import com.factory.dto.*;
import com.factory.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * REST Controller for factory machine events.
 *
 * Endpoints:
 * 1. POST /events/batch - Ingest batch of events
 * 2. GET /stats - Query statistics for a machine
 * 3. GET /stats/top-defect-lines - Get top defect lines for a factory
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventService eventService;

    /**
     * Ingest a batch of events.
     *
     * POST /events/batch
     *
     * Request body: JSON array of events
     * Response: BatchIngestResponse with counts and rejection details
     *
     * Thread-safe: Multiple concurrent requests are handled safely.
     */
    @PostMapping("/events/batch")
    public ResponseEntity<BatchIngestResponse> ingestBatch(@Valid @RequestBody List<EventDTO> events) {
        log.info("Received batch of {} events", events.size());
        BatchIngestResponse response = eventService.ingestBatch(events);
        return ResponseEntity.ok(response);
    }

    /**
     * Get statistics for a machine within a time window.
     *
     * GET /stats?machineId=M-001&start=2026-01-15T00:00:00Z&end=2026-01-15T06:00:00Z
     *
     * Query params:
     * - machineId: machine identifier
     * - start: start time (inclusive), ISO-8601 format
     * - end: end time (exclusive), ISO-8601 format
     *
     * Response: StatsResponse with event counts, defects, and health status
     */
    @GetMapping("/stats")
    public ResponseEntity<StatsResponse> getStats(
            @RequestParam String machineId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {

        log.info("Querying stats for machine={}, start={}, end={}", machineId, start, end);
        StatsResponse response = eventService.getStats(machineId, start, end);
        return ResponseEntity.ok(response);
    }

    /**
     * Get top defect lines for a factory.
     *
     * GET /stats/top-defect-lines?factoryId=F01&from=2026-01-15T00:00:00Z&to=2026-01-15T23:59:59Z&limit=10
     *
     * Query params:
     * - factoryId: factory identifier
     * - from: start time (inclusive), ISO-8601 format
     * - to: end time (exclusive), ISO-8601 format
     * - limit: max number of lines to return (default: 10)
     *
     * Response: List of TopDefectLineResponse sorted by total defects (descending)
     */
    @GetMapping("/stats/top-defect-lines")
    public ResponseEntity<List<TopDefectLineResponse>> getTopDefectLines(
            @RequestParam String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "10") int limit) {

        log.info("Querying top defect lines for factory={}, from={}, to={}, limit={}",
                 factoryId, from, to, limit);
        List<TopDefectLineResponse> response = eventService.getTopDefectLines(factoryId, from, to, limit);
        return ResponseEntity.ok(response);
    }

    /**
     * Global exception handler for validation errors.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Error processing request", e);
        ErrorResponse error = new ErrorResponse(e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Simple error response DTO.
     */
    private record ErrorResponse(String message) {}
}

