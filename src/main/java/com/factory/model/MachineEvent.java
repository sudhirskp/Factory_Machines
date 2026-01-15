package com.factory.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Entity representing a machine event in the database.
 *
 * Key design decisions:
 * - eventId is the primary key (unique identifier)
 * - payloadHash is used for detecting payload changes (dedupe vs update logic)
 * - Indexes on machineId and eventTime for fast queries
 * - receivedTime tracks when the backend received the event
 */
@Entity
@Table(name = "machine_events", indexes = {
    @Index(name = "idx_machine_time", columnList = "machineId,eventTime"),
    @Index(name = "idx_event_time", columnList = "eventTime"),
    @Index(name = "idx_line_time", columnList = "lineId,eventTime")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MachineEvent {

    @Id
    @Column(length = 100)
    private String eventId;

    @Column(nullable = false)
    private Instant eventTime;

    @Column(nullable = false)
    private Instant receivedTime;

    @Column(nullable = false, length = 50)
    private String machineId;

    @Column(nullable = false)
    private Long durationMs;

    @Column(nullable = false)
    private Integer defectCount;

    /**
     * Hash of the payload for detecting changes.
     * Used to distinguish between duplicate (same payload) and update (different payload).
     */
    @Column(nullable = false)
    private String payloadHash;

    /**
     * Factory ID for grouping machines by factory line.
     * This supports the top-defect-lines endpoint.
     */
    @Column(length = 50)
    private String factoryId;

    /**
     * Line ID within a factory.
     * This supports the top-defect-lines endpoint.
     */
    @Column(length = 50)
    private String lineId;

    /**
     * Version tracking for optimistic locking and update detection.
     */
    @Version
    private Long version;
}

