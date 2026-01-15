package com.factory.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class EventDTO {

    private String lineId;
    private String factoryId;
    // Optional fields for factory/line grouping

    @NotNull(message = "defectCount is required")
    private Integer defectCount;

    @NotNull(message = "durationMs is required")
    private Long durationMs;

    @NotBlank(message = "machineId is required")
    private String machineId;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    // receivedTime in request payload is ignored - server sets it
    private Instant receivedTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    @NotNull(message = "eventTime is required")
    private Instant eventTime;

    @NotBlank(message = "eventId is required")
    private String eventId;
}