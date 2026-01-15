package com.factory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Response for batch ingestion endpoint.
 *
 * Tracks:
 * - accepted: new valid events stored
 * - deduped: identical events ignored
 * - updated: events with same ID but different payload
 * - rejected: invalid events
 * - rejections: list of rejection details
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchIngestResponse {

    private int accepted;
    private int deduped;
    private int updated;
    private int rejected;

    @Builder.Default
    private List<RejectionDetail> rejections = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RejectionDetail {
        private String eventId;
        private String reason;
    }
}

