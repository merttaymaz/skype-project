package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.config.RecordingProperties;
import com.databoss.sfbucwa.model.TranscriptResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * External STT (Speech-to-Text) servisine audio forwarding.
 *
 * <p>Desteklenen STT backend'leri:</p>
 * <ul>
 *   <li><b>OpenAI Whisper API</b> — POST /v1/audio/transcriptions (multipart/form-data)</li>
 *   <li><b>Self-hosted Whisper</b> — faster-whisper-server, whisper.cpp server, vb.</li>
 *   <li><b>Azure Speech Services</b> — Batch transcription API</li>
 *   <li><b>Google Cloud Speech</b> — LongRunningRecognize</li>
 * </ul>
 *
 * <p>Default olarak OpenAI-uyumlu API formatini kullanir.
 * Self-hosted faster-whisper-server ile birebir uyumludur.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SttForwardingService {

    private final WebClient ucwaWebClient;
    private final RecordingProperties props;
    private final ObjectMapper objectMapper;

    /**
     * Audio dosyasini STT servisine gonderir ve transkript dondurur.
     *
     * <p>Bu metod blocking I/O yapar (HTTP multipart upload + response bekler).
     * Virtual Thread icinde cagrilir — platform thread bloklanmaz.</p>
     *
     * @param audioFile   STT uyumlu formata donusturulmus WAV dosyasi
     * @param recordingId eslestirme icin kayit ID'si
     * @return transkript sonucu
     */
    public TranscriptResult transcribe(Path audioFile, String recordingId) {
        log.info("STT'ye gonderiliyor: {} (recordingId={})", audioFile.getFileName(), recordingId);

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new FileSystemResource(audioFile))
                .contentType(MediaType.parseMediaType("audio/wav"));
        bodyBuilder.part("model", props.getSttModel());
        bodyBuilder.part("language", props.getSttLanguage());
        bodyBuilder.part("response_format", "verbose_json");

        try {
            String responseJson = ucwaWebClient.post()
                    .uri(props.getSttServiceUrl())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .headers(headers -> {
                        if (props.getSttApiKey() != null && !props.getSttApiKey().isBlank()) {
                            headers.setBearerAuth(props.getSttApiKey());
                        }
                    })
                    .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(props.getSttTimeoutSeconds()));

            return parseWhisperResponse(responseJson, recordingId);

        } catch (Exception e) {
            log.error("STT hatasi (recordingId={}): {}", recordingId, e.getMessage());
            return TranscriptResult.builder()
                    .recordingId(recordingId)
                    .fullText("[TRANSKRIPT HATASI: " + e.getMessage() + "]")
                    .transcribedAt(Instant.now())
                    .build();
        }
    }

    /**
     * OpenAI Whisper verbose_json formatini parse eder.
     *
     * <pre>
     * {
     *   "text": "tam metin",
     *   "language": "tr",
     *   "duration": 120.5,
     *   "segments": [
     *     {"start": 0.0, "end": 3.2, "text": "Merhaba", "avg_logprob": -0.3}
     *   ]
     * }
     * </pre>
     */
    private TranscriptResult parseWhisperResponse(String json, String recordingId) {
        try {
            JsonNode root = objectMapper.readTree(json);

            String fullText = root.path("text").asText("");
            String language = root.path("language").asText(props.getSttLanguage());
            double duration = root.path("duration").asDouble(0);

            List<TranscriptResult.Segment> segments = new ArrayList<>();
            JsonNode segmentsNode = root.path("segments");
            if (segmentsNode.isArray()) {
                for (JsonNode seg : segmentsNode) {
                    segments.add(TranscriptResult.Segment.builder()
                            .startSeconds(seg.path("start").asDouble())
                            .endSeconds(seg.path("end").asDouble())
                            .text(seg.path("text").asText())
                            .confidence(Math.exp(seg.path("avg_logprob").asDouble(0)))
                            .build());
                }
            }

            log.info("Transkript tamamlandi: {} segment, {}s, dil={}",
                    segments.size(), duration, language);

            return TranscriptResult.builder()
                    .recordingId(recordingId)
                    .fullText(fullText)
                    .language(language)
                    .durationSeconds(duration)
                    .transcribedAt(Instant.now())
                    .segments(segments)
                    .build();

        } catch (Exception e) {
            log.error("Whisper response parse hatasi", e);
            return TranscriptResult.builder()
                    .recordingId(recordingId)
                    .fullText(json) // ham response'u dondur
                    .transcribedAt(Instant.now())
                    .build();
        }
    }
}
