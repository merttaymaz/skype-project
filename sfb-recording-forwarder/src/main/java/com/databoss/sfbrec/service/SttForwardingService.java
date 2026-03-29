package com.databoss.sfbrec.service;

import com.databoss.sfbrec.config.RecordingProperties;
import com.databoss.sfbrec.model.ProcessingResult;
import com.databoss.sfbrec.model.RecordingMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;

/**
 * External STT servisine ses dosyasi gonderim servisi.
 *
 * <p>Virtual Threads uzerinde calisir — her dosya gonderimy ayri
 * bir virtual thread'de bloke olabilir, OS thread tuketmez.</p>
 *
 * <p>Desteklenen STT servisleri:</p>
 * <ul>
 *   <li>OpenAI Whisper API</li>
 *   <li>Azure Speech Services</li>
 *   <li>Google Cloud Speech-to-Text</li>
 *   <li>Self-hosted Whisper/Faster-Whisper</li>
 *   <li>Herhangi bir multipart/form-data kabul eden HTTP endpoint</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SttForwardingService {

    private final WebClient sttWebClient;
    private final RecordingProperties props;

    /**
     * Ses dosyasini STT servisine gonderir ve transkript sonucunu dondurur.
     *
     * <p>Bu metod BLOCKING'dir — Virtual Thread uzerinde cagrilmak uzere tasarlandi.
     * WebClient'in .block() metodu kullanilir, cunku Virtual Thread'de
     * blocking I/O sifiir maliyetlidir.</p>
     *
     * @param metadata kayit dosyasi metadata'si
     * @return isleme sonucu (transkript veya hata)
     */
    public ProcessingResult forwardToStt(RecordingMetadata metadata) {
        Instant startedAt = Instant.now();
        String recordingId = metadata.getRecordingId();

        log.info("[{}] STT gonderimi basliyor: {} ({} bytes, {})",
                recordingId, metadata.getFileName(),
                metadata.getFileSizeBytes(),
                metadata.getAudioDuration() != null ? metadata.getAudioDuration() : "sure bilinmiyor");

        try {
            // Multipart form-data olustur
            MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
            bodyBuilder.part("file", new FileSystemResource(metadata.getFilePath()))
                    .filename(metadata.getFileName());
            bodyBuilder.part("model", "whisper-1"); // Whisper API uyumu
            bodyBuilder.part("language", "tr");      // Turkce

            // Metadata ekle (STT servisi destekliyorsa)
            if (metadata.getConferenceUri() != null) {
                bodyBuilder.part("conference_uri", metadata.getConferenceUri());
            }
            if (metadata.getOrganizer() != null) {
                bodyBuilder.part("organizer", metadata.getOrganizer());
            }
            if (metadata.getSampleRate() > 0) {
                bodyBuilder.part("sample_rate", String.valueOf(metadata.getSampleRate()));
            }

            // STT servisine gonder — Virtual Thread'de block() guvenli
            String responseBody = sttWebClient.post()
                    .uri(props.getSttEndpointPath())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .headers(headers -> {
                        if (props.getSttApiKey() != null && !props.getSttApiKey().isBlank()) {
                            headers.setBearerAuth(props.getSttApiKey());
                        }
                    })
                    .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(props.getSttTimeout()); // Virtual Thread — block() OK

            Duration processingTime = Duration.between(startedAt, Instant.now());

            log.info("[{}] STT tamamlandi: {} — sure: {}ms, yanit boyutu: {} char",
                    recordingId, metadata.getFileName(),
                    processingTime.toMillis(),
                    responseBody != null ? responseBody.length() : 0);

            return ProcessingResult.builder()
                    .recordingId(recordingId)
                    .status(ProcessingResult.Status.SUCCESS)
                    .transcript(responseBody)
                    .startedAt(startedAt)
                    .completedAt(Instant.now())
                    .processingTime(processingTime)
                    .fileSizeBytes(metadata.getFileSizeBytes())
                    .sttProvider(props.getSttServiceUrl())
                    .httpStatusCode(200)
                    .build();

        } catch (WebClientResponseException e) {
            log.error("[{}] STT HTTP hatasi: {} — {}", recordingId, e.getStatusCode(), e.getResponseBodyAsString());
            return buildFailedResult(recordingId, startedAt, metadata,
                    "STT HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(),
                    e.getStatusCode().value());

        } catch (Exception e) {
            log.error("[{}] STT gonderim hatasi: {}", recordingId, e.getMessage(), e);
            return buildFailedResult(recordingId, startedAt, metadata,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), 0);
        }
    }

    /**
     * Retry mekanizmali gonderim.
     * Her retry arasinda exponential backoff uygulanir.
     */
    public ProcessingResult forwardWithRetry(RecordingMetadata metadata) {
        int maxRetries = props.getSttMaxRetries();
        ProcessingResult lastResult = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            lastResult = forwardToStt(metadata);

            if (lastResult.getStatus() == ProcessingResult.Status.SUCCESS) {
                return lastResult;
            }

            if (attempt < maxRetries) {
                long backoffMs = 1000L * attempt * attempt; // 1s, 4s, 9s
                log.warn("[{}] STT deneme {}/{} basarisiz, {}ms sonra tekrar deneniyor",
                        metadata.getRecordingId(), attempt, maxRetries, backoffMs);
                try {
                    Thread.sleep(backoffMs); // Virtual Thread — sleep ucuz
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.error("[{}] STT tum denemeler basarisiz ({} deneme)", metadata.getRecordingId(), maxRetries);
        return lastResult;
    }

    private ProcessingResult buildFailedResult(String recordingId, Instant startedAt,
                                                RecordingMetadata metadata, String error, int httpStatus) {
        return ProcessingResult.builder()
                .recordingId(recordingId)
                .status(ProcessingResult.Status.FAILED)
                .errorMessage(error)
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .processingTime(Duration.between(startedAt, Instant.now()))
                .fileSizeBytes(metadata.getFileSizeBytes())
                .sttProvider(props.getSttServiceUrl())
                .httpStatusCode(httpStatus)
                .build();
    }
}
