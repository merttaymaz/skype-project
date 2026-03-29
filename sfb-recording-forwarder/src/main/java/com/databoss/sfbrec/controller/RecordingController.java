package com.databoss.sfbrec.controller;

import com.databoss.sfbrec.model.ProcessingResult;
import com.databoss.sfbrec.service.ProcessingTracker;
import com.databoss.sfbrec.service.RecordingWatcher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/recordings")
@RequiredArgsConstructor
@Tag(name = "Recording Forwarder", description = "SfB ses kaydi isleme ve STT gonderim API")
public class RecordingController {

    private final RecordingWatcher watcher;
    private final ProcessingTracker tracker;

    @GetMapping("/stats")
    @Operation(summary = "Islem istatistikleri")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(tracker.getStats());
    }

    @GetMapping("/results")
    @Operation(summary = "Tum islem sonuclari")
    public ResponseEntity<Collection<ProcessingResult>> getAllResults() {
        return ResponseEntity.ok(tracker.getAllResults());
    }

    @GetMapping("/results/{recordingId}")
    @Operation(summary = "Belirli bir kaydin islem sonucu")
    public ResponseEntity<ProcessingResult> getResult(@PathVariable String recordingId) {
        ProcessingResult result = tracker.getResult(recordingId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/process")
    @Operation(summary = "Belirli bir dosyayi manuel olarak isle",
            description = "Dosya yolunu body'de gonder. Ornek: /mnt/sfb-recordings/test.wav")
    public ResponseEntity<ProcessingResult> processFile(@RequestBody Map<String, String> request) {
        String filePath = request.get("filePath");
        if (filePath == null || filePath.isBlank()) {
            return ResponseEntity.badRequest().body(ProcessingResult.builder()
                    .status(ProcessingResult.Status.FAILED)
                    .errorMessage("filePath gerekli")
                    .build());
        }

        ProcessingResult result = watcher.processFileManually(Path.of(filePath));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/scan")
    @Operation(summary = "Dizin taramasini manuel tetikle")
    public ResponseEntity<Map<String, String>> triggerScan() {
        Thread.startVirtualThread(watcher::scanDirectory);
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "message", "Dizin taramasi tetiklendi (Virtual Thread)"));
    }

    @DeleteMapping("/results")
    @Operation(summary = "Tum islem sonuclarini temizle")
    public ResponseEntity<Map<String, String>> clearResults() {
        tracker.clear();
        return ResponseEntity.ok(Map.of("status", "OK", "message", "Sonuclar temizlendi"));
    }
}
