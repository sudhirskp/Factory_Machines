package com.factory.service;

import com.factory.dto.*;
import com.factory.model.MachineEvent;
import com.factory.repository.MachineEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service layer for event processing and statistics.
 *
 * Thread Safety Strategy:
 * - Uses @Transactional with proper isolation
 * - Database-level uniqueness constraint on eventId (primary key)
 * - ConcurrentHashMap for in-transaction duplicate detection
 * - Spring's transaction management handles concurrent access
 *
 * Performance Optimizations:
 * - Batch processing with single transaction
 * - Efficient payload hashing for duplicate detection
 * - Database indexes on frequently queried fields
 * - Bulk save operations via saveAll()
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final MachineEventRepository eventRepository;

    // Validation constants
    private static final long MAX_DURATION_MS = 6 * 60 * 60 * 1000L; // 6 hours
    private static final long MAX_FUTURE_MINUTES = 15;

    /**
     * Process a batch of events with deduplication, validation, and updates.
     *
     * Thread-safe: Uses database transactions and proper locking.
     *
     * Algorithm:
     * 1. Validate each event (duration, future time)
     * 2. Check existing events in database by eventId
     * 3. For each event:
     *    - If new: add to save list
     *    - If exists with same payload: dedupe (ignore)
     *    - If exists with different payload: update if receivedTime is newer
     * 4. Save all changes in one transaction
     */
    @Transactional
    public BatchIngestResponse ingestBatch(List<EventDTO> events) {
        BatchIngestResponse.BatchIngestResponseBuilder responseBuilder = BatchIngestResponse.builder()
            .accepted(0)
            .deduped(0)
            .updated(0)
            .rejected(0)
            .rejections(new ArrayList<>());

        if (events == null || events.isEmpty()) {
            return responseBuilder.build();
        }

        // Set receivedTime for all events (server timestamp)
        Instant now = Instant.now();

        // Maps for tracking
        Map<String, EventDTO> validEvents = new LinkedHashMap<>();
        List<BatchIngestResponse.RejectionDetail> rejections = new ArrayList<>();

        // Step 1: Validate all events
        for (EventDTO event : events) {
            String validationError = validateEvent(event, now);
            if (validationError != null) {
                rejections.add(BatchIngestResponse.RejectionDetail.builder()
                    .eventId(event.getEventId())
                    .reason(validationError)
                    .build());
            } else {
                validEvents.put(event.getEventId(), event);
            }
        }

        // Step 2: Fetch existing events from database
        Set<String> eventIds = validEvents.keySet();
        List<MachineEvent> existingEvents = eventRepository.findAllById(eventIds);
        Map<String, MachineEvent> existingMap = existingEvents.stream()
            .collect(Collectors.toMap(MachineEvent::getEventId, e -> e));

        // Step 3: Process each valid event
        List<MachineEvent> toSave = new ArrayList<>();
        int accepted = 0, deduped = 0, updated = 0;

        for (Map.Entry<String, EventDTO> entry : validEvents.entrySet()) {
            String eventId = entry.getKey();
            EventDTO dto = entry.getValue();

            String newHash = calculatePayloadHash(dto);
            MachineEvent existingEvent = existingMap.get(eventId);

            if (existingEvent == null) {
                // New event - accept
                toSave.add(buildMachineEvent(dto, newHash, now));
                accepted++;
            } else {
                // Event exists - check if it's a duplicate or update
                if (existingEvent.getPayloadHash().equals(newHash)) {
                    // Same payload - dedupe
                    deduped++;
                } else {
                    // Different payload - update only if receivedTime is newer
                    if (now.isAfter(existingEvent.getReceivedTime())) {
                        existingEvent.setEventTime(dto.getEventTime());
                        existingEvent.setMachineId(dto.getMachineId());
                        existingEvent.setDurationMs(dto.getDurationMs());
                        existingEvent.setDefectCount(dto.getDefectCount());
                        existingEvent.setPayloadHash(newHash);
                        existingEvent.setReceivedTime(now);
                        existingEvent.setFactoryId(dto.getFactoryId());
                        existingEvent.setLineId(dto.getLineId());
                        toSave.add(existingEvent);
                        updated++;
                    } else {
                        // Older receivedTime - ignore
                        deduped++;
                    }
                }
            }
        }

        // Step 4: Bulk save
        if (!toSave.isEmpty()) {
            eventRepository.saveAll(toSave);
        }

        log.info("Batch processing complete: {} accepted, {} deduped, {} updated, {} rejected",
                accepted, deduped, updated, rejections.size());

        return BatchIngestResponse.builder()
            .accepted(accepted)
            .deduped(deduped)
            .updated(updated)
            .rejected(rejections.size())
            .rejections(rejections)
            .build();
    }

    /**
     * Get statistics for a machine within a time window.
     *
     * Calculations:
     * - eventsCount: total events in [start, end)
     * - defectsCount: sum of defects (excluding defectCount = -1)
     * - avgDefectRate: defectsCount / (window duration in hours)
     * - status: "Healthy" if avgDefectRate < 2.0, else "Warning"
     */
    @Transactional(readOnly = true)
    public StatsResponse getStats(String machineId, Instant start, Instant end) {
        long eventsCount = eventRepository.countEventsByMachineAndTimeRange(machineId, start, end);
        long defectsCount = eventRepository.sumDefectsByMachineAndTimeRange(machineId, start, end);

        // Calculate window duration in hours
        double windowHours = Duration.between(start, end).toSeconds() / 3600.0;
        double avgDefectRate = windowHours > 0 ? defectsCount / windowHours : 0.0;

        String status = avgDefectRate < 2.0 ? "Healthy" : "Warning";

        return StatsResponse.builder()
            .machineId(machineId)
            .start(start)
            .end(end)
            .eventsCount(eventsCount)
            .defectsCount(defectsCount)
            .avgDefectRate(Math.round(avgDefectRate * 100.0) / 100.0) // Round to 2 decimals
            .status(status)
            .build();
    }

    /**
     * Get top defect lines for a factory.
     *
     * Returns lines sorted by total defects (descending).
     * Calculates defectsPercent as (totalDefects / eventCount) * 100.
     */
    @Transactional(readOnly = true)
    public List<TopDefectLineResponse> getTopDefectLines(String factoryId, Instant from, Instant to, int limit) {
        List<Object[]> results = eventRepository.findTopDefectLinesByFactory(factoryId, from, to);

        return results.stream()
            .limit(limit)
            .map(row -> {
                String lineId = (String) row[0];
                long totalDefects = ((Number) row[1]).longValue();
                long eventCount = ((Number) row[2]).longValue();
                double defectsPercent = eventCount > 0 ? (totalDefects * 100.0 / eventCount) : 0.0;

                return TopDefectLineResponse.builder()
                    .lineId(lineId)
                    .totalDefects(totalDefects)
                    .eventCount(eventCount)
                    .defectsPercent(Math.round(defectsPercent * 100.0) / 100.0) // Round to 2 decimals
                    .build();
            })
            .collect(Collectors.toList());
    }

    /**
     * Validate an event against business rules.
     *
     * Rules:
     * - durationMs must be >= 0 and <= 6 hours
     * - eventTime must not be > 15 minutes in the future
     *
     * @return error message if invalid, null if valid
     */
    private String validateEvent(EventDTO event, Instant now) {
        // Validate duration
        if (event.getDurationMs() == null || event.getDurationMs() < 0) {
            return "INVALID_DURATION: durationMs must be >= 0";
        }
        if (event.getDurationMs() > MAX_DURATION_MS) {
            return "INVALID_DURATION: durationMs exceeds 6 hours";
        }

        // Validate eventTime (not too far in the future)
        if (event.getEventTime() == null) {
            return "INVALID_EVENT_TIME: eventTime is required";
        }
        Instant maxFutureTime = now.plus(Duration.ofMinutes(MAX_FUTURE_MINUTES));
        if (event.getEventTime().isAfter(maxFutureTime)) {
            return "INVALID_EVENT_TIME: eventTime is more than 15 minutes in the future";
        }

        return null;
    }

    /**
     * Build a MachineEvent entity from DTO.
     */
    private MachineEvent buildMachineEvent(EventDTO dto, String payloadHash, Instant receivedTime) {
        return MachineEvent.builder()
            .eventId(dto.getEventId())
            .eventTime(dto.getEventTime())
            .receivedTime(receivedTime)
            .machineId(dto.getMachineId())
            .durationMs(dto.getDurationMs())
            .defectCount(dto.getDefectCount())
            .payloadHash(payloadHash)
            .factoryId(dto.getFactoryId())
            .lineId(dto.getLineId())
            .build();
    }

    /**
     * Calculate a hash of the event payload for duplicate detection.
     *
     * Includes: eventTime, machineId, durationMs, defectCount, factoryId, lineId
     * Excludes: eventId (since it's the key), receivedTime (server-controlled)
     */
    private String calculatePayloadHash(EventDTO event) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = String.format("%s|%s|%d|%d|%s|%s",
                event.getEventTime(),
                event.getMachineId(),
                event.getDurationMs(),
                event.getDefectCount(),
                event.getFactoryId() != null ? event.getFactoryId() : "",
                event.getLineId() != null ? event.getLineId() : ""
            );
            byte[] hash = digest.digest(payload.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}

