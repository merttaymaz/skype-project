package com.databoss.sfbstream.service;

import com.databoss.sfbstream.config.StreamingProperties;
import com.databoss.sfbstream.model.AudioSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Biriken audio buffer'i STT servisine gonderir ve partial transkript alir.
 *
 * <p>Her flush'ta buffer WAV'a cevirilir, STT'ye multipart olarak gonderilir.
 * Gelen transkript session'a eklenir ve WebSocket uzerinden client'a push edilir.</p>
 *
 * <p>Virtual Thread uzerinde calisir — block() guvenlidir.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamingSttService {

    private final WebClient sttWebClient;
    private final StreamingProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Session'daki biriken audio'yu STT'ye gonderir.
     * Virtual Thread uzerinde calisir — blocking I/O sorun degil.
     *
     * @param session aktif audio session
     * @return partial transkript veya null (buffer bos ise)
     */
    public String flushAndTranscribe(AudioSession session) {
        byte[] wavData = session.flushAsWav();
        if (wavData == null || wavData.length <= 44) { // 44 = WAV header only
            return null;
        }

        String sessionId = session.getSessionId();
        int flushNum = session.getFlushCount();

        log.debug("[{}] Flush #{} → STT gonderiliyor: {} bytes WAV",
                sessionId, flushNum, wavData.length);

        try {
            MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
            bodyBuilder.part("file",
                    new ByteArrayResource(wavData) {
                        @Override
                        public String getFilename() {
                            return "chunk_" + sessionId + "_" + flushNum + ".wav";
                        }
                    });
            bodyBuilder.part("model", "whisper-1");
            bodyBuilder.part("language", "tr");
            bodyBuilder.part("response_format", "json");

            // Ek metadata
            if (session.getConferenceUri() != null) {
                bodyBuilder.part("conference_uri", session.getConferenceUri());
            }

            String responseBody = sttWebClient.post()
                    .uri(props.getSttEndpointPath())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .headers(h -> {
                        if (props.getSttApiKey() != null && !props.getSttApiKey().isBlank()) {
                            h.setBearerAuth(props.getSttApiKey());
                        }
                    })
                    .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(props.getSttTimeout()); // Virtual Thread — block() ucuz

            // JSON'dan text alanini cikar
            String transcript = extractTranscriptText(responseBody);

            if (transcript != null && !transcript.isBlank()) {
                session.addTranscriptSegment(transcript);
                log.info("[{}] Flush #{} transkript: \"{}\"",
                        sessionId, flushNum,
                        transcript.length() > 80 ? transcript.substring(0, 80) + "..." : transcript);
            }

            return transcript;

        } catch (Exception e) {
            log.error("[{}] Flush #{} STT hatasi: {}", sessionId, flushNum, e.getMessage());
            return null;
        }
    }

    /**
     * STT yanit JSON'undan transkript metnini cikarir.
     * Whisper API, Azure Speech ve custom endpoint formatlarini destekler.
     */
    private String extractTranscriptText(String jsonResponse) {
        if (jsonResponse == null) return null;

        try {
            JsonNode root = objectMapper.readTree(jsonResponse);

            // Whisper API formati: { "text": "..." }
            if (root.has("text")) {
                return root.get("text").asText();
            }

            // Azure Speech formati: { "DisplayText": "..." }
            if (root.has("DisplayText")) {
                return root.get("DisplayText").asText();
            }

            // Google Speech formati: { "results": [{ "alternatives": [{ "transcript": "..." }] }] }
            if (root.has("results") && root.get("results").isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode result : root.get("results")) {
                    if (result.has("alternatives") && result.get("alternatives").isArray()) {
                        JsonNode firstAlt = result.get("alternatives").get(0);
                        if (firstAlt != null && firstAlt.has("transcript")) {
                            sb.append(firstAlt.get("transcript").asText()).append(" ");
                        }
                    }
                }
                return sb.toString().trim();
            }

            // Fallback — tum body'yi dondur
            return jsonResponse;

        } catch (Exception e) {
            log.warn("STT yanit parse hatasi: {}", e.getMessage());
            return jsonResponse;
        }
    }
}
