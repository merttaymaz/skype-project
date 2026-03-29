package com.databoss.sfbrec.service;

import com.databoss.sfbrec.config.RecordingProperties;
import com.databoss.sfbrec.model.ProcessingResult;
import com.databoss.sfbrec.model.RecordingMetadata;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * SfB compliance recording dizinini tarar ve yeni dosyalari isler.
 *
 * <p>Her yeni dosya icin bir Virtual Thread olusturulur.
 * Semaphore ile downstream STT servisi korunur (max N concurrent).</p>
 *
 * <p>Akis:</p>
 * <pre>
 *   @Scheduled scan → dosya bul → metadata cikar → Semaphore acquire
 *   → Virtual Thread { STT gonder → sonuc kaydet → dosyayi tasi }
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingWatcher {

    private final RecordingProperties props;
    private final AudioMetadataService metadataService;
    private final SttForwardingService sttService;
    private final ProcessingTracker tracker;

    private Semaphore concurrencyLimiter;

    @PostConstruct
    void init() throws IOException {
        // Dizinleri olustur
        Path watchDir = Path.of(props.getWatchDirectory());
        Path processedDir = Path.of(props.getProcessedDirectory());
        Path failedDir = Path.of(props.getFailedDirectory());

        Files.createDirectories(watchDir);
        Files.createDirectories(processedDir);
        Files.createDirectories(failedDir);

        concurrencyLimiter = new Semaphore(props.getMaxConcurrentProcessing());

        log.info("RecordingWatcher baslatildi — dizin: {}, max paralel: {}, tarama araligi: {}",
                watchDir, props.getMaxConcurrentProcessing(), props.getScanInterval());
    }

    /**
     * Dizini periyodik olarak tarar.
     * Spring @Scheduled ile calisir, her scan'de bulunan dosyalar
     * Virtual Thread'lere dispatch edilir.
     */
    @Scheduled(fixedDelayString = "${recording.scan-interval:PT10S}")
    public void scanDirectory() {
        Path watchDir = Path.of(props.getWatchDirectory());

        if (!Files.isDirectory(watchDir)) {
            log.warn("Kayit dizini bulunamadi: {}", watchDir);
            return;
        }

        List<Path> newFiles;
        try (Stream<Path> stream = Files.list(watchDir)) {
            newFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedFile)
                    .filter(this::isFileStable)
                    .toList();
        } catch (IOException e) {
            log.error("Dizin tarama hatasi: {}", watchDir, e);
            return;
        }

        if (newFiles.isEmpty()) return;

        log.info("{} yeni kayit dosyasi bulundu", newFiles.size());

        for (Path file : newFiles) {
            // Her dosya icin Virtual Thread olustur
            Thread.startVirtualThread(() -> processFile(file));
        }
    }

    /**
     * Tek bir dosyayi isler. Virtual Thread uzerinde calisir.
     *
     * <p>Akis: metadata cikar → semaphore acquire (blocking, VT'de ucuz)
     * → STT gonder → sonuc kaydet → dosyayi tasi</p>
     */
    private void processFile(Path file) {
        RecordingMetadata metadata = metadataService.extractMetadata(file);
        String recordingId = metadata.getRecordingId();

        // Daha once islenmis mi kontrol et
        if (!tracker.tryAcquire(recordingId)) {
            return;
        }

        log.info("[{}] Isleme aliniyor: {} ({} bytes)",
                recordingId, metadata.getFileName(), metadata.getFileSizeBytes());

        try {
            // Semaphore ile STT servisini koru — Virtual Thread'de block() ucuz
            concurrencyLimiter.acquire();
            try {
                // STT'ye gonder (retry dahil)
                ProcessingResult result = sttService.forwardWithRetry(metadata);

                // Sonucu kaydet
                tracker.recordResult(result);

                // Dosyayi tasi
                moveFile(file, result.getStatus());

                log.info("[{}] Islem tamamlandi: {} — {} ({}ms)",
                        recordingId, result.getStatus(), metadata.getFileName(),
                        result.getProcessingTime().toMillis());

            } finally {
                concurrencyLimiter.release();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}] Islem kesildi", recordingId);
            tracker.recordResult(ProcessingResult.builder()
                    .recordingId(recordingId)
                    .status(ProcessingResult.Status.FAILED)
                    .errorMessage("Interrupted")
                    .build());
        }
    }

    /**
     * Dosyayi islem sonucuna gore processed/ veya failed/ dizinine tasir.
     */
    private void moveFile(Path file, ProcessingResult.Status status) {
        try {
            Path targetDir;
            if (status == ProcessingResult.Status.SUCCESS && props.isMoveProcessedFiles()) {
                targetDir = Path.of(props.getProcessedDirectory());
            } else if (status == ProcessingResult.Status.FAILED && props.isMoveFailedFiles()) {
                targetDir = Path.of(props.getFailedDirectory());
            } else {
                return; // Dosyayi yerinde birak
            }

            Path target = targetDir.resolve(file.getFileName());
            Files.move(file, target);
            log.debug("Dosya tasiindi: {} → {}", file.getFileName(), targetDir);

        } catch (IOException e) {
            log.warn("Dosya tasima hatasi: {}", file, e);
        }
    }

    /** Dosya uzantisini kontrol eder */
    private boolean isSupportedFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return props.getSupportedExtensions().stream()
                .anyMatch(name::endsWith);
    }

    /**
     * Dosyanin yazminin tamamlanip tamamlanmadigini kontrol eder.
     * Dosya boyutu stability delay suresi icerisinde degismediyse stabil kabul edilir.
     */
    private boolean isFileStable(Path file) {
        try {
            long size1 = Files.size(file);
            Thread.sleep(props.getFileStabilityDelay().toMillis()); // VT'de ucuz
            long size2 = Files.size(file);
            return size1 == size2 && size1 > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Manuel tetikleme — belirli bir dosyayi isle.
     */
    public ProcessingResult processFileManually(Path file) {
        RecordingMetadata metadata = metadataService.extractMetadata(file);
        if (!tracker.tryAcquire(metadata.getRecordingId())) {
            return ProcessingResult.builder()
                    .recordingId(metadata.getRecordingId())
                    .status(ProcessingResult.Status.SKIPPED)
                    .errorMessage("Zaten islenmis")
                    .build();
        }
        ProcessingResult result = sttService.forwardWithRetry(metadata);
        tracker.recordResult(result);
        moveFile(file, result.getStatus());
        return result;
    }
}
