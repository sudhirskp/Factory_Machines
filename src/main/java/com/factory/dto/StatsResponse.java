package com.factory.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response for stats query endpoint.
 *
 * Provides:
 * - eventsCount: total valid events in time window
 * - defectsCount: total defects (ignoring defectCount = -1)
 * - avgDefectRate: defects per hour
 * - status: "Healthy" if avgDefectRate < 2.0, else "Warning"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsResponse {

    private String machineId;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant start;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant end;

    private long eventsCount;
    private long defectsCount;
    private double avgDefectRate;
    private String status;
}

