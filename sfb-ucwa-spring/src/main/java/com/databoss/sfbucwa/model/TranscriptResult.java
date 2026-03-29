package com.databoss.sfbucwa.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptResult {

    private String recordingId;
    private String fullText;
    private String language;
    private double durationSeconds;
    private Instant transcribedAt;
    private List<Segment> segments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Segment {
        private double startSeconds;
        private double endSeconds;
        private String text;
        private String speaker;      // speaker diarization varsa
        private double confidence;
    }
}
