package com.databoss.sfbucwa.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordingMetadata {

    public enum Status {
        DETECTED, CONVERTING, FORWARDING, COMPLETED, FAILED
    }

    private String id;
    private String originalFileName;
    private Path originalPath;
    private Path convertedPath;
    private long fileSizeBytes;
    private String mimeType;
    private Duration audioDuration;
    private Instant detectedAt;
    private Instant completedAt;
    private Status status;
    private String errorMessage;

    /** SfB meeting bilgileri — dosya adindan parse edilebilir */
    private String conferenceUri;
    private String organizer;
}
