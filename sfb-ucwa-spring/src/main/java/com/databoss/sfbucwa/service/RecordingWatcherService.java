package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.config.RecordingProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SfB kayit dizinini WatchService + Virtual Threads ile izler.
 *
 * <h3>Nasil calisir?</h3>
 * <pre>
 *   ┌─────────────────────────────────┐
 *   │  /mnt/sfb-recordings/           │
 *   │    meeting_2026-03-26_001.wav    │ ← SfB Server yazar
 *   │    meeting_2026-03-26_002.wma    │
 *   └────────────┬────────────────────┘
 *                │ WatchService ENTRY_CREATE
 *                ▼
 *   ┌─────────────────────────────────┐
 *   │  RecordingWatcherService        │
 *   │  (tek watcher thread)           │
 *   └────────────┬────────────────────┘
 *                │ her dosya icin yeni Virtual Thread
 *                ▼
 *   ┌─────────────────────────────────┐
 *   │  Virtual Thread Pool            │
 *   │                                 │
 *   │  VT-1: convert → STT → store   │
 *   │  VT-2: convert → STT → store   │
 *   │  VT-3: convert → STT → store   │
 *   │  ...                            │
 *   │  (Semaphore ile max 10 es zaman)│
 *   └─────────────────────────────────┘
 * </pre>
 *
 * <h3>Virtual Thread avantaji</h3>
 * <p>Her kayit dosyasi icin ayri bir Virtual Thread baslatilir.
 * FFmpeg donusum (~10s), STT API bekleme (~60-300s) gibi I/O-bound
 * islemler sirasinda Virtual Thread park eder ve platform thread
 * serbest kalir. 100 kayit es zamanli islense bile sadece birkac
 * platform thread kullanilir.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingWatcherService {

    private final RecordingProperties props;
    private final RecordingPipelineService pipeline;
    private final ExecutorService recordingExecutor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> processingFiles = ConcurrentHashMap.newKeySet();

    private WatchService watchService;
    private Thread watcherThread;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (props.isEnabled()) {
            start();
        } else {
            log.info("Recording watcher devre disi (sfb.recording.enabled=false)");
        }
    }

    /**
     * Dizin izlemeyi baslatir.
     * Watcher kendi Virtual Thread'inde calisir.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            try {
                Path watchPath = props.getWatchPath();
                Files.createDirectories(watchPath);
                Files.createDirectories(props.getProcessedPath());
                Files.createDirectories(props.getFailedPath());
                Files.createDirectories(props.getTempPath());

                watchService = FileSystems.getDefault().newWatchService();
                watchPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

                log.info("Recording watcher baslatildi: {}", watchPath);

                // Watcher'i Virtual Thread olarak baslat
                watcherThread = Thread.ofVirtual()
                        .name("recording-watcher")
                        .start(this::watchLoop);

                // Baslangicta mevcut dosyalari da isle
                scanExistingFiles(watchPath);

            } catch (IOException e) {
                running.set(false);
                log.error("Watcher baslatilamadi", e);
            }
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Recording watcher durduruluyor...");
            try {
                if (watchService != null) watchService.close();
                if (watcherThread != null) watcherThread.interrupt();
            } catch (IOException e) {
                log.warn("WatchService kapatma hatasi", e);
            }
        }
    }

    /**
     * Ana izleme dongusu.
     * WatchService.take() blocking cagridir — Virtual Thread burada park eder.
     */
    private void watchLoop() {
        while (running.get()) {
            try {
                // Virtual Thread burada park eder — platform thread serbest
                WatchKey key = watchService.take();

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path fileName = pathEvent.context();
                    Path fullPath = props.getWatchPath().resolve(fileName);

                    if (isWatchedExtension(fileName.toString())) {
                        submitForProcessing(fullPath);
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    log.warn("WatchKey artik gecerli degil, watcher durduruluyor");
                    running.set(false);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (Exception e) {
                log.error("Watch loop hatasi", e);
            }
        }
        log.info("Watch loop sona erdi");
    }

    /**
     * Baslangicta dizinde zaten var olan dosyalari isle.
     */
    private void scanExistingFiles(Path dir) {
        try (var files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> isWatchedExtension(p.getFileName().toString()))
                    .forEach(this::submitForProcessing);
        } catch (IOException e) {
            log.warn("Mevcut dosya taramasi hatasi", e);
        }
    }

    /**
     * Dosyayi pipeline'a gonder — her dosya kendi Virtual Thread'inde islenir.
     * Ayni dosyanin tekrar islenmesini onler.
     */
    private void submitForProcessing(Path file) {
        String fileName = file.getFileName().toString();

        if (!processingFiles.add(fileName)) {
            log.debug("Zaten isleniyor, atlaniyor: {}", fileName);
            return;
        }

        log.info("Yeni kayit tespit edildi, Virtual Thread baslatiliyor: {}", fileName);

        recordingExecutor.submit(() -> {
            try {
                pipeline.processRecording(file);
            } finally {
                processingFiles.remove(fileName);
            }
        });
    }

    private boolean isWatchedExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return props.getWatchExtensions().stream().anyMatch(lower::endsWith);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getActiveProcessingCount() {
        return processingFiles.size();
    }

    @PreDestroy
    public void onShutdown() {
        stop();
    }
}
