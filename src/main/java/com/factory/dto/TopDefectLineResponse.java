package com.factory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response item for top defect lines endpoint.
 *
 * Contains:
 * - lineId: production line identifier
 * - totalDefects: sum of defects for this line
 * - eventCount: number of events
 * - defectsPercent: defects per 100 events (rounded to 2 decimals)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopDefectLineResponse {

    private String lineId;
    private long totalDefects;
    private long eventCount;
    private double defectsPercent;
}

