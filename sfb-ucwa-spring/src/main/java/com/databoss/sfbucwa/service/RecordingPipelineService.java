package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.config.RecordingProperties;
import com.databoss.sfbucwa.model.RecordingMetadata;
import com.databoss.sfbucwa.model.TranscriptResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Kayit isleme pipeline'i.
 *
 * <p>Her kayit dosyasi icin asagidaki adimlari sirayla calistirir:</p>
 * <ol>
 *   <li>Dosya kararliligi kontrolu (yazim bitmis mi?)</li>
 *   <li>FFmpeg ile 16kHz mono WAV'a donusum</li>
 *   <li>External STT servisine gonderim</li>
 *   <li>Transkript saklama</li>
 *   <li>Kaynak dosyayi processed/ dizinine tasima</li>
 * </ol>
 *
 * <p>Her adim Virtual Thread icinde calisir. Semaphore ile
 * es zamanli STT istekleri sinirlandirilir.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingPipelineService {

    private final RecordingProperties props;
    private final AudioConverterService audioConverter;
    private final SttForwardingService sttService;
    private final Semaphore processingThrottle;

    /** Islenen kayitlarin durumu — basit in-memory store */
    private final Map<String, RecordingMetadata> recordingStore = new ConcurrentHashMap<>();
    private final Map<String, TranscriptResult> transcriptStore = new ConcurrentHashMap<>();

    /**
     * Tek bir kayit dosyasini tam pipeline'dan gecirir.
     * Bu metod Virtual Thread icinde cagrilmak uzere tasarlanmistir.
     *
     * @param audioFile tespit edilen kayit dosyasi
     */
    public void processRecording(Path audioFile) {
        String recordingId = UUID.randomUUID().toString().substring(0, 8);

        RecordingMetadata metadata = RecordingMetadata.builder()
                .id(recordingId)
                .originalFileName(audioFile.getFileName().toString())
                .originalPath(audioFile)
                .detectedAt(Instant.now())
                .status(RecordingMetadata.Status.DETECTED)
                .build();

        recordingStore.put(recordingId, metadata);

        Path convertedFile = null;
        try {
            // 1. Dosya kararliligi — yazim bitmis mi kontrol et
            waitForFileStability(audioFile);
            metadata.setFileSizeBytes(Files.size(audioFile));

            // 2. Semaphore ile throttle — STT servisi asiri yuklenmesin
            log.info("[{}] Pipeline basladi, throttle bekleniyor...", recordingId);
            processingThrottle.acquire(); // Virtual Thread burada park eder

            try {
                // 3. Audio donusum (FFmpeg)
                metadata.setStatus(RecordingMetadata.Status.CONVERTING);
                convertedFile = audioConverter.convertToSttFormat(audioFile);
                metadata.setConvertedPath(convertedFile);

                Duration audioDuration = audioConverter.getAudioDuration(convertedFile);
                metadata.setAudioDuration(audioDuration);
                log.info("[{}] Donusum tamam, sure: {}s", recordingId, audioDuration.toSeconds());

                // 4. STT'ye gonder
                metadata.setStatus(RecordingMetadata.Status.FORWARDING);
                TranscriptResult transcript = sttService.transcribe(convertedFile, recordingId);
                transcriptStore.put(recordingId, transcript);
                log.info("[{}] Transkript alindi: {} karakter", recordingId,
                        transcript.getFullText() != null ? transcript.getFullText().length() : 0);

                // 5. Basarili — dosyayi processed/ dizinine tasi
                metadata.setStatus(RecordingMetadata.Status.COMPLETED);
                metadata.setCompletedAt(Instant.now());
                moveFile(audioFile, props.getProcessedPath());

                long totalMs = Duration.between(metadata.getDetectedAt(), metadata.getCompletedAt()).toMillis();
                log.info("[{}] Pipeline tamamlandi ({}ms)", recordingId, totalMs);

            } finally {
                processingThrottle.release();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metadata.setStatus(RecordingMetadata.Status.FAILED);
            metadata.setErrorMessage("Interrupted: " + e.getMessage());
            log.error("[{}] Pipeline interrupted", recordingId);
        } catch (Exception e) {
            metadata.setStatus(RecordingMetadata.Status.FAILED);
            metadata.setErrorMessage(e.getMessage());
            log.error("[{}] Pipeline hatasi: {}", recordingId, e.getMessage(), e);
            moveFile(audioFile, props.getFailedPath());
        } finally {
            // Gecici dosyayi temizle
            if (convertedFile != null) {
                audioConverter.cleanup(convertedFile);
            }
        }
    }

    /**
     * Dosya boyutunun degismediginden emin ol — SfB hala yaziyor olabilir.
     */
    private void waitForFileStability(Path file) throws IOException, InterruptedException {
        long previousSize = -1;
        int stableChecks = 0;
        int waitSeconds = props.getFileStabilityWaitSeconds();

        while (stableChecks < 2) {
            long currentSize = Files.size(file);
            if (currentSize == previousSize && currentSize > 0) {
                stableChecks++;
            } else {
                stableChecks = 0;
            }
            previousSize = currentSize;

            if (stableChecks < 2) {
                // Virtual Thread burada park eder
                Thread.sleep(Duration.ofSeconds(waitSeconds));
            }
        }
    }

    private void moveFile(Path source, Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(source.getFileName());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Dosya tasinidi: {} -> {}", source.getFileName(), targetDir);
        } catch (IOException e) {
            log.warn("Dosya tasinamadi: {}", source, e);
        }
    }

    // ──── Durum sorgulama ────

    public Map<String, RecordingMetadata> getAllRecordings() {
        return Map.copyOf(recordingStore);
    }

    public RecordingMetadata getRecording(String id) {
        return recordingStore.get(id);
    }

    public TranscriptResult getTranscript(String id) {
        return transcriptStore.get(id);
    }

    public Map<String, Object> getStats() {
        long total = recordingStore.size();
        long completed = recordingStore.values().stream()
                .filter(r -> r.getStatus() == RecordingMetadata.Status.COMPLETED).count();
        long failed = recordingStore.values().stream()
                .filter(r -> r.getStatus() == RecordingMetadata.Status.FAILED).count();
        long inProgress = total - completed - failed;

        return Map.of(
                "total", total,
                "completed", completed,
                "failed", failed,
                "inProgress", inProgress,
                "availablePermits", processingThrottle.availablePermits()
        );
    }
}
