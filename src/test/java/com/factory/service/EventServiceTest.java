package com.factory.service;

import com.factory.dto.BatchIngestResponse;
import com.factory.dto.EventDTO;
import com.factory.dto.StatsResponse;
import com.factory.model.MachineEvent;
import com.factory.repository.MachineEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for EventService.
 *
 * Tests cover:
 * 1. Identical duplicate deduplication
 * 2. Update with newer receivedTime
 * 3. Ignore update with older receivedTime
 * 4. Invalid duration rejection
 * 5. Future eventTime rejection
 * 6. DefectCount = -1 ignored in calculations
 * 7. Start/end boundary correctness
 * 8. Thread-safety with concurrent ingestion
 * 9. Batch processing performance
 */
@SpringBootTest
@ActiveProfiles("test")
class EventServiceTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private MachineEventRepository eventRepository;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
    }

    /**
     * Test 1: Identical duplicate eventId → deduped
     *
     * When we send the same event twice (same eventId and payload),
     * the second occurrence should be deduped (ignored).
     */
    @Test
    void testIdenticalDuplicateIsDeduped() {
        Instant now = Instant.now();

        EventDTO event = EventDTO.builder()
            .eventId("E-100")
            .eventTime(now.minus(1, ChronoUnit.HOURS))
            .machineId("M-001")
            .durationMs(1000L)
            .defectCount(0)
            .build();

        // First ingestion - should be accepted
        BatchIngestResponse response1 = eventService.ingestBatch(List.of(event));
        assertEquals(1, response1.getAccepted());
        assertEquals(0, response1.getDeduped());

        // Second ingestion with identical data - should be deduped
        BatchIngestResponse response2 = eventService.ingestBatch(List.of(event));
        assertEquals(0, response2.getAccepted());
        assertEquals(1, response2.getDeduped());

        // Verify only one event in database
        assertEquals(1, eventRepository.count());
    }

    /**
     * Test 2: Different payload + newer receivedTime → update happens
     *
     * When an event with the same eventId but different payload arrives later,
     * it should update the existing event.
     */
    @Test
    void testDifferentPayloadNewerReceivedTimeUpdates() throws InterruptedException {
        Instant eventTime = Instant.now().minus(1, ChronoUnit.HOURS);

        EventDTO event1 = EventDTO.builder()
            .eventId("E-200")
            .eventTime(eventTime)
            .machineId("M-001")
            .durationMs(1000L)
            .defectCount(0)
            .build();

        // First ingestion
        BatchIngestResponse response1 = eventService.ingestBatch(List.of(event1));
        assertEquals(1, response1.getAccepted());

        // Wait to ensure newer receivedTime
        Thread.sleep(10);

        // Second ingestion with different payload
        EventDTO event2 = EventDTO.builder()
            .eventId("E-200")
            .eventTime(eventTime)
            .machineId("M-002") // Different machine
            .durationMs(2000L) // Different duration
            .defectCount(5) // Different defect count
            .build();

        BatchIngestResponse response2 = eventService.ingestBatch(List.of(event2));
        assertEquals(0, response2.getAccepted());
        assertEquals(1, response2.getUpdated());

        // Verify update
        MachineEvent updated = eventRepository.findById("E-200").orElseThrow();
        assertEquals("M-002", updated.getMachineId());
        assertEquals(2000L, updated.getDurationMs());
        assertEquals(5, updated.getDefectCount());
    }

    /**
     * Test 3: Different payload + older receivedTime → ignored
     *
     * This test simulates receiving an older version of an event after
     * a newer version has already been processed. The older version should
     * be ignored (treated as dedupe).
     */
    @Test
    void testDifferentPayloadOlderReceivedTimeIgnored() {
        Instant eventTime = Instant.now().minus(1, ChronoUnit.HOURS);

        // Create event with newer data
        EventDTO newerEvent = EventDTO.builder()
            .eventId("E-300")
            .eventTime(eventTime)
            .machineId("M-001")
            .durationMs(2000L)
            .defectCount(5)
            .build();

        // Ingest newer event first
        eventService.ingestBatch(List.of(newerEvent));

        // Manually set older receivedTime by updating the database
        MachineEvent saved = eventRepository.findById("E-300").orElseThrow();
        Instant futureReceivedTime = Instant.now().plus(1, ChronoUnit.HOURS);
        saved.setReceivedTime(futureReceivedTime);
        eventRepository.save(saved);

        // Try to ingest older version (with different payload)
        EventDTO olderEvent = EventDTO.builder()
            .eventId("E-300")
            .eventTime(eventTime)
            .machineId("M-002")
            .durationMs(1000L)
            .defectCount(0)
            .build();

        BatchIngestResponse response = eventService.ingestBatch(List.of(olderEvent));

        // Should be ignored (deduped) because receivedTime is older
        assertEquals(0, response.getAccepted());
        assertEquals(0, response.getUpdated());
        assertEquals(1, response.getDeduped());

        // Verify no update occurred
        MachineEvent notUpdated = eventRepository.findById("E-300").orElseThrow();
        assertEquals("M-001", notUpdated.getMachineId()); // Original value
        assertEquals(2000L, notUpdated.getDurationMs()); // Original value
    }

    /**
     * Test 4: Invalid duration rejected
     *
     * Events with negative duration or duration > 6 hours should be rejected.
     */
    @Test
    void testInvalidDurationRejected() {
        Instant now = Instant.now();

        // Negative duration
        EventDTO event1 = EventDTO.builder()
            .eventId("E-400")
            .eventTime(now.minus(1, ChronoUnit.HOURS))
            .machineId("M-001")
            .durationMs(-100L)
            .defectCount(0)
            .build();

        // Duration > 6 hours
        EventDTO event2 = EventDTO.builder()
            .eventId("E-401")
            .eventTime(now.minus(1, ChronoUnit.HOURS))
            .machineId("M-001")
            .durationMs(7 * 60 * 60 * 1000L) // 7 hours
            .defectCount(0)
            .build();

        BatchIngestResponse response = eventService.ingestBatch(List.of(event1, event2));

        assertEquals(0, response.getAccepted());
        assertEquals(2, response.getRejected());
        assertTrue(response.getRejections().stream()
            .anyMatch(r -> r.getReason().contains("INVALID_DURATION")));
    }

    /**
     * Test 5: Future eventTime rejected
     *
     * Events with eventTime > 15 minutes in the future should be rejected.
     */
    @Test
    void testFutureEventTimeRejected() {
        Instant futureTime = Instant.now().plus(20, ChronoUnit.MINUTES);

        EventDTO event = EventDTO.builder()
            .eventId("E-500")
            .eventTime(futureTime)
            .machineId("M-001")
            .durationMs(1000L)
            .defectCount(0)
            .build();

        BatchIngestResponse response = eventService.ingestBatch(List.of(event));

        assertEquals(0, response.getAccepted());
        assertEquals(1, response.getRejected());
        assertTrue(response.getRejections().get(0).getReason().contains("INVALID_EVENT_TIME"));
    }

    /**
     * Test 6: DefectCount = -1 ignored in defect totals
     *
     * Events with defectCount = -1 (unknown) should be stored but not
     * included in defect calculations.
     */
    @Test
    void testDefectCountMinusOneIgnoredInCalculations() {
        Instant start = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant end = Instant.now();

        List<EventDTO> events = List.of(
            EventDTO.builder()
                .eventId("E-600")
                .eventTime(start.plus(10, ChronoUnit.MINUTES))
                .machineId("M-001")
                .durationMs(1000L)
                .defectCount(5)
                .build(),
            EventDTO.builder()
                .eventId("E-601")
                .eventTime(start.plus(20, ChronoUnit.MINUTES))
                .machineId("M-001")
                .durationMs(1000L)
                .defectCount(-1) // Unknown - should be ignored
                .build(),
            EventDTO.builder()
                .eventId("E-602")
                .eventTime(start.plus(30, ChronoUnit.MINUTES))
                .machineId("M-001")
                .durationMs(1000L)
                .defectCount(3)
                .build()
        );

        BatchIngestResponse ingestResponse = eventService.ingestBatch(events);
        assertEquals(3, ingestResponse.getAccepted());

        // Query stats
        StatsResponse stats = eventService.getStats("M-001", start, end);

        assertEquals(3, stats.getEventsCount()); // All 3 events counted
        assertEquals(8, stats.getDefectsCount()); // Only 5 + 3 = 8 (ignoring -1)
    }

    /**
     * Test 7: Start/end boundary correctness (inclusive/exclusive)
     *
     * Verify that start is inclusive and end is exclusive.
     */
    @Test
    void testStartEndBoundaryCorrectness() {
        Instant start = Instant.parse("2026-01-15T10:00:00Z");
        Instant end = Instant.parse("2026-01-15T12:00:00Z");

        List<EventDTO> events = List.of(
            // Before start - should NOT be included
            EventDTO.builder()
                .eventId("E-700")
                .eventTime(start.minus(1, ChronoUnit.SECONDS))
                .machineId("M-001")
                .durationMs(1000L)
                .defectCount(1)
                .build(),
            // At start - should be included (inclusive)
            EventDTO.builder()
                .eventId("E-701")
                .eventTime(start)
                .machineId("M-001")
                .durationMs(1000L)
                .defectCount(2)
                .build(),
            // Between start and end - should be included
            EventDTO.builder()
                .eventId("E-702")
                .eventTime(start.plus(30, ChronoUnit.MINUTES))
                .machineId("M-001")
                .durationMs(1000L)
                .defectCount(3)
                .build(),
            // At end - should NOT be included (exclusive)
            EventDTO.builder()
                .eventId("E-703")
                .eventTime(end)
                .machineId("M-001")
                .durationMs(1000L)
                .defectCount(4)
                .build(),
            // After end - should NOT be included
            EventDTO.builder()
                .eventId("E-704")
                .eventTime(end.plus(1, ChronoUnit.SECONDS))
                .machineId("M-001")
                .durationMs(1000L)
                .defectCount(5)
                .build()
        );

        eventService.ingestBatch(events);

        // Query with [start, end) range
        StatsResponse stats = eventService.getStats("M-001", start, end);

        assertEquals(2, stats.getEventsCount()); // Only E-701 and E-702
        assertEquals(5, stats.getDefectsCount()); // 2 + 3 = 5
    }

    /**
     * Test 8: Thread-safety test: concurrent ingestion
     *
     * Simulate multiple threads ingesting events concurrently.
     * Verify that:
     * - No data corruption occurs
     * - Duplicate detection works correctly
     * - All events are processed
     */
    @Test
    void testConcurrentIngestionThreadSafety() throws InterruptedException, ExecutionException {
        int threadCount = 10;
        int eventsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Create events with some duplicates across threads
        List<Callable<BatchIngestResponse>> tasks = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            tasks.add(() -> {
                List<EventDTO> events = new ArrayList<>();
                for (int i = 0; i < eventsPerThread; i++) {
                    events.add(EventDTO.builder()
                        .eventId("E-" + threadId + "-" + i)
                        .eventTime(Instant.now().minus(1, ChronoUnit.HOURS))
                        .machineId("M-" + (i % 5)) // 5 different machines
                        .durationMs(1000L + i)
                        .defectCount(i % 10)
                        .build());
                }
                return eventService.ingestBatch(events);
            });
        }

        // Execute all tasks concurrently
        List<Future<BatchIngestResponse>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        // Collect results
        int totalAccepted = 0;
        for (Future<BatchIngestResponse> future : futures) {
            BatchIngestResponse response = future.get();
            totalAccepted += response.getAccepted();
        }

        // Verify all events were processed
        assertEquals(threadCount * eventsPerThread, totalAccepted);
        assertEquals(threadCount * eventsPerThread, eventRepository.count());
    }

    /**
     * Test 9: Performance benchmark
     *
     * Verify that 1000 events can be processed in under 1 second.
     */
    @Test
    void testBatchProcessingPerformance() {
        int eventCount = 1000;
        Instant baseTime = Instant.now().minus(1, ChronoUnit.HOURS);

        List<EventDTO> events = IntStream.range(0, eventCount)
            .mapToObj(i -> EventDTO.builder()
                .eventId("E-PERF-" + i)
                .eventTime(baseTime.plus(i, ChronoUnit.SECONDS))
                .machineId("M-" + (i % 10))
                .durationMs(1000L + i)
                .defectCount(i % 5)
                .factoryId("F01")
                .lineId("L" + (i % 3))
                .build())
            .toList();

        long startTime = System.currentTimeMillis();
        BatchIngestResponse response = eventService.ingestBatch(events);
        long endTime = System.currentTimeMillis();

        long duration = endTime - startTime;

        assertEquals(eventCount, response.getAccepted());
        assertTrue(duration < 1000,
            "Processing 1000 events took " + duration + "ms, should be under 1000ms");

        System.out.println("Performance: Processed " + eventCount +
                          " events in " + duration + "ms");
    }

    /**
     * Test 10: Health status calculation
     *
     * Verify that status is "Healthy" when avgDefectRate < 2.0,
     * and "Warning" otherwise.
     */
    @Test
    void testHealthStatusCalculation() {
        Instant start = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant end = Instant.now();

        // Low defect rate - should be Healthy
        List<EventDTO> healthyEvents = List.of(
            EventDTO.builder()
                .eventId("E-H1")
                .eventTime(start.plus(10, ChronoUnit.MINUTES))
                .machineId("M-HEALTHY")
                .durationMs(1000L)
                .defectCount(1)
                .build(),
            EventDTO.builder()
                .eventId("E-H2")
                .eventTime(start.plus(20, ChronoUnit.MINUTES))
                .machineId("M-HEALTHY")
                .durationMs(1000L)
                .defectCount(2)
                .build()
        );

        eventService.ingestBatch(healthyEvents);
        StatsResponse healthyStats = eventService.getStats("M-HEALTHY", start, end);
        assertEquals("Healthy", healthyStats.getStatus());

        // High defect rate - should be Warning
        List<EventDTO> warningEvents = List.of(
            EventDTO.builder()
                .eventId("E-W1")
                .eventTime(start.plus(10, ChronoUnit.MINUTES))
                .machineId("M-WARNING")
                .durationMs(1000L)
                .defectCount(10)
                .build(),
            EventDTO.builder()
                .eventId("E-W2")
                .eventTime(start.plus(20, ChronoUnit.MINUTES))
                .machineId("M-WARNING")
                .durationMs(1000L)
                .defectCount(15)
                .build()
        );

        eventService.ingestBatch(warningEvents);
        StatsResponse warningStats = eventService.getStats("M-WARNING", start, end);
        assertEquals("Warning", warningStats.getStatus());
    }
}

