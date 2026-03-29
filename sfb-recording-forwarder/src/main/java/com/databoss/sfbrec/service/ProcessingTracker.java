package com.databoss.sfbrec.service;

import com.databoss.sfbrec.model.ProcessingResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory islem takip servisi.
 * Kafka/Redis/DB yerine ConcurrentHashMap — basit ve yeterli.
 *
 * <p>Virtual Threads ile thread-safe: ConcurrentHashMap + Atomic operasyonlar.</p>
 */
@Slf4j
@Service
public class ProcessingTracker {

    private final Map<String, ProcessingResult> results = new ConcurrentHashMap<>();
    private final ConcurrentHashMap.KeySetView<String, Boolean> inProgress = ConcurrentHashMap.newKeySet();

    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalSuccess = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);

    /**
     * Dosyayi isleme olarak isaretler.
     * @return true ise islenebilir, false ise zaten isleniyor/islenmis
     */
    public boolean tryAcquire(String recordingId) {
        if (results.containsKey(recordingId)) {
            log.debug("[{}] Zaten islenmis, atlaniyor", recordingId);
            return false;
        }
        return inProgress.add(recordingId);
    }

    /** Islem sonucunu kaydeder */
    public void recordResult(ProcessingResult result) {
        results.put(result.getRecordingId(), result);
        inProgress.remove(result.getRecordingId());
        totalProcessed.incrementAndGet();
        totalBytesProcessed.addAndGet(result.getFileSizeBytes());

        if (result.getStatus() == ProcessingResult.Status.SUCCESS) {
            totalSuccess.incrementAndGet();
        } else if (result.getStatus() == ProcessingResult.Status.FAILED) {
            totalFailed.incrementAndGet();
        }
    }

    /** Aktif islem sayisi */
    public int getActiveCount() {
        return inProgress.size();
    }

    /** Belirli bir kaydin sonucunu dondurur */
    public ProcessingResult getResult(String recordingId) {
        return results.get(recordingId);
    }

    /** Tum sonuclari dondurur */
    public Collection<ProcessingResult> getAllResults() {
        return results.values();
    }

    /** Istatistikler */
    public Map<String, Object> getStats() {
        return Map.of(
                "totalProcessed", totalProcessed.get(),
                "totalSuccess", totalSuccess.get(),
                "totalFailed", totalFailed.get(),
                "totalBytesProcessed", totalBytesProcessed.get(),
                "activeProcessing", inProgress.size(),
                "resultsInMemory", results.size()
        );
    }

    /** State temizle (test icin) */
    public void clear() {
        results.clear();
        inProgress.clear();
        totalProcessed.set(0);
        totalSuccess.set(0);
        totalFailed.set(0);
        totalBytesProcessed.set(0);
    }
}
