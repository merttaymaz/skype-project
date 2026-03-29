package com.databoss.sfbstream.service;

import com.databoss.sfbstream.config.StreamingProperties;
import com.databoss.sfbstream.model.AudioSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UCMA Bot'tan gelen WebSocket baglantilarini yonetir.
 *
 * <h3>Protokol:</h3>
 * <ol>
 *   <li>Client TEXT mesaj gonderir: {@code {"type":"session_start", "conferenceUri":"...", "sampleRate":16000, ...}}</li>
 *   <li>Client BINARY mesajlar gonderir: raw PCM chunk'lari (her ~20ms, ~640 byte)</li>
 *   <li>Server TEXT mesaj gonderir: {@code {"type":"partial_transcript", "text":"..."}}</li>
 *   <li>Client TEXT mesaj gonderir: {@code {"type":"session_end"}}</li>
 * </ol>
 *
 * <h3>Virtual Thread Kullanimi:</h3>
 * <p>Her session icin bir "flusher" Virtual Thread baslatilir.
 * Bu thread periyodik olarak audio buffer'i kontrol eder,
 * STT'ye gonderir ve partial transkripti WebSocket'e push eder.</p>
 *
 * <pre>
 * WS Binary Frame → appendChunk(pcm)
 *                         ↓
 *              AudioSession buffer (ByteArrayOutputStream)
 *                         ↓
 *         Virtual Thread (flushInterval'da bir)
 *                         ↓
 *              flushAsWav() → StreamingSttService
 *                         ↓
 *              partial transcript → WS Text Frame
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AudioStreamWebSocketHandler extends AbstractWebSocketHandler {

    private final SessionManager sessionManager;
    private final StreamingSttService sttService;
    private final StreamingProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** WebSocket session ID → Audio session ID mapping */
    private final Map<String, String> wsToAudioSession = new ConcurrentHashMap<>();

    /** Flusher thread'lerin durdurma sinyali */
    private final Map<String, Thread> flusherThreads = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) {
        log.info("WebSocket baglandi: {} ({})", wsSession.getId(), wsSession.getRemoteAddress());
    }

    /**
     * TEXT mesaj: session_start veya session_end komutu.
     */
    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        JsonNode json = objectMapper.readTree(message.getPayload());
        String type = json.has("type") ? json.get("type").asText() : "";

        switch (type) {
            case "session_start" -> handleSessionStart(wsSession, json);
            case "session_end"   -> handleSessionEnd(wsSession);
            default -> log.warn("Bilinmeyen mesaj tipi: {}", type);
        }
    }

    /**
     * BINARY mesaj: raw PCM audio chunk.
     * UCMA bot'tan her 20ms'de bir gelir (~640 byte @ 16kHz 16bit mono).
     */
    @Override
    protected void handleBinaryMessage(WebSocketSession wsSession, BinaryMessage message) {
        String audioSessionId = wsToAudioSession.get(wsSession.getId());
        if (audioSessionId == null) {
            log.warn("Audio session bulunamadi, WS: {}", wsSession.getId());
            return;
        }

        AudioSession session = sessionManager.getSession(audioSessionId);
        if (session == null || !session.isActive()) return;

        byte[] payload = message.getPayload().array();
        session.appendChunk(payload, 0, payload.length);
    }

    /**
     * Session baslatma: audio parametrelerini alir ve flusher thread baslatir.
     */
    private void handleSessionStart(WebSocketSession wsSession, JsonNode json) throws IOException {
        String conferenceUri = json.has("conferenceUri") ? json.get("conferenceUri").asText() : "unknown";
        int sampleRate = json.has("sampleRate") ? json.get("sampleRate").asInt() : 16000;
        int channels = json.has("channels") ? json.get("channels").asInt() : 1;
        int bitsPerSample = json.has("bitsPerSample") ? json.get("bitsPerSample").asInt() : 16;

        String audioSessionId = "stream-" + UUID.randomUUID().toString().substring(0, 8);

        AudioSession session = sessionManager.createSession(
                audioSessionId, conferenceUri, sampleRate, channels, bitsPerSample);

        wsToAudioSession.put(wsSession.getId(), audioSessionId);

        // Flusher Virtual Thread baslat
        Thread flusher = Thread.startVirtualThread(() ->
                flushLoop(audioSessionId, wsSession));
        flusherThreads.put(audioSessionId, flusher);

        // Client'a onay gonder
        String ack = objectMapper.writeValueAsString(Map.of(
                "type", "session_started",
                "sessionId", audioSessionId,
                "flushIntervalMs", props.getFlushInterval().toMillis()
        ));
        wsSession.sendMessage(new TextMessage(ack));

        log.info("[{}] Session baslatildi, flusher thread aktif (interval={})",
                audioSessionId, props.getFlushInterval());
    }

    /**
     * Flusher loop — Virtual Thread uzerinde calisir.
     *
     * <p>Her flushInterval'da bir buffer'i kontrol eder.
     * Yeterli veri birikmisse STT'ye gonderir ve
     * partial transkripti WebSocket'e push eder.</p>
     *
     * <p>Virtual Thread'de Thread.sleep() ucuz — OS thread tuketmez.</p>
     */
    private void flushLoop(String audioSessionId, WebSocketSession wsSession) {
        long intervalMs = props.getFlushInterval().toMillis();

        log.debug("[{}] Flusher thread basladi (VT: {})", audioSessionId, Thread.currentThread());

        while (true) {
            try {
                // Virtual Thread sleep — sifir maliyet
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            AudioSession session = sessionManager.getSession(audioSessionId);
            if (session == null || !session.isActive()) break;

            int bufferSize = session.getBufferSize();

            // Minimum flush boyutuna ulasilmadiysa bekle
            if (bufferSize < props.getMinFlushSizeBytes()) continue;

            // Maksimum buffer asilmissa veya yeterli veri varsa → flush
            // STT'ye gonder (blocking I/O — Virtual Thread'de sorun degil)
            String transcript = sttService.flushAndTranscribe(session);

            // Partial transkripti client'a push et
            if (transcript != null && !transcript.isBlank() && wsSession.isOpen()) {
                try {
                    String msg = objectMapper.writeValueAsString(Map.of(
                            "type", "partial_transcript",
                            "sessionId", audioSessionId,
                            "text", transcript,
                            "flushNumber", session.getFlushCount(),
                            "elapsedSeconds", session.getElapsedTime().toSeconds(),
                            "audioDurationSeconds", session.getEstimatedAudioDuration().toSeconds()
                    ));
                    synchronized (wsSession) { // WebSocket sendMessage thread-safe degil
                        wsSession.sendMessage(new TextMessage(msg));
                    }
                } catch (IOException e) {
                    log.warn("[{}] Transkript push hatasi: {}", audioSessionId, e.getMessage());
                }
            }
        }

        // Session kapandiginda kalan buffer'i son kez flush et
        finalFlush(audioSessionId, wsSession);
        log.debug("[{}] Flusher thread sona erdi", audioSessionId);
    }

    /**
     * Session kapanirken kalan audio'yu son kez STT'ye gonderir.
     */
    private void finalFlush(String audioSessionId, WebSocketSession wsSession) {
        AudioSession session = sessionManager.getSession(audioSessionId);
        if (session == null) return;

        if (session.getBufferSize() > 0) {
            log.info("[{}] Final flush: {} bytes kaldi", audioSessionId, session.getBufferSize());
            String transcript = sttService.flushAndTranscribe(session);

            if (transcript != null && wsSession.isOpen()) {
                try {
                    String msg = objectMapper.writeValueAsString(Map.of(
                            "type", "final_transcript",
                            "sessionId", audioSessionId,
                            "text", transcript,
                            "fullTranscript", session.getFullTranscript(),
                            "totalFlushes", session.getFlushCount(),
                            "totalDurationSeconds", session.getEstimatedAudioDuration().toSeconds()
                    ));
                    synchronized (wsSession) {
                        wsSession.sendMessage(new TextMessage(msg));
                    }
                } catch (IOException e) {
                    log.warn("[{}] Final transkript push hatasi", audioSessionId, e);
                }
            }
        }
    }

    /**
     * Client session_end gonderdiginde veya baglanti koptuunda.
     */
    private void handleSessionEnd(WebSocketSession wsSession) {
        String audioSessionId = wsToAudioSession.remove(wsSession.getId());
        if (audioSessionId == null) return;

        // Flusher thread'i durdur (interrupt)
        Thread flusher = flusherThreads.remove(audioSessionId);
        if (flusher != null) flusher.interrupt();

        AudioSession session = sessionManager.removeSession(audioSessionId);
        if (session != null) {
            log.info("[{}] Session sonlandi. Tam transkript ({} segment): {}",
                    audioSessionId,
                    session.getTranscriptSegments().size(),
                    session.getFullTranscript().length() > 200
                            ? session.getFullTranscript().substring(0, 200) + "..."
                            : session.getFullTranscript());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        log.info("WebSocket kapandi: {} ({})", wsSession.getId(), status);
        handleSessionEnd(wsSession);
    }

    @Override
    public void handleTransportError(WebSocketSession wsSession, Throwable exception) {
        log.error("WebSocket transport hatasi: {}", wsSession.getId(), exception);
        handleSessionEnd(wsSession);
    }
}
