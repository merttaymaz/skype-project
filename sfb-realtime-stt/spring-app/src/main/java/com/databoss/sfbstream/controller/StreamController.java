package com.databoss.sfbstream.controller;

import com.databoss.sfbstream.model.AudioSession;
import com.databoss.sfbstream.service.SessionManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/stream")
@RequiredArgsConstructor
@Tag(name = "Realtime STT", description = "SfB ses stream monitoring ve transkript API")
public class StreamController {

    private final SessionManager sessionManager;

    @GetMapping("/stats")
    @Operation(summary = "Genel istatistikler")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(sessionManager.getStats());
    }

    @GetMapping("/sessions")
    @Operation(summary = "Aktif streaming session'lari")
    public ResponseEntity<List<Map<String, Object>>> getActiveSessions() {
        var sessions = sessionManager.getActiveSessions().stream()
                .map(s -> Map.<String, Object>of(
                        "sessionId", s.getSessionId(),
                        "conferenceUri", s.getConferenceUri() != null ? s.getConferenceUri() : "N/A",
                        "active", s.isActive(),
                        "elapsedSeconds", s.getElapsedTime().toSeconds(),
                        "totalBytesReceived", s.getTotalBytesReceived(),
                        "audioDurationSeconds", s.getEstimatedAudioDuration().toSeconds(),
                        "flushCount", s.getFlushCount(),
                        "transcriptSegments", s.getTranscriptSegments().size(),
                        "bufferSizeBytes", s.getBufferSize()
                ))
                .toList();
        return ResponseEntity.ok(sessions);
    }

    @GetMapping("/sessions/{sessionId}/transcript")
    @Operation(summary = "Belirli bir session'in biriken transkripti")
    public ResponseEntity<Map<String, Object>> getTranscript(@PathVariable String sessionId) {
        AudioSession session = sessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "conferenceUri", session.getConferenceUri() != null ? session.getConferenceUri() : "N/A",
                "segments", session.getTranscriptSegments(),
                "fullTranscript", session.getFullTranscript(),
                "segmentCount", session.getTranscriptSegments().size(),
                "audioDurationSeconds", session.getEstimatedAudioDuration().toSeconds()
        ));
    }
}
