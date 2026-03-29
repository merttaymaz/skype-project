package com.databoss.sfbucwa.controller;

import com.databoss.sfbucwa.model.RecordingMetadata;
import com.databoss.sfbucwa.model.TranscriptResult;
import com.databoss.sfbucwa.service.RecordingPipelineService;
import com.databoss.sfbucwa.service.RecordingWatcherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/recordings")
@RequiredArgsConstructor
@Tag(name = "Recording & STT", description = "Ses kaydi isleme ve transkript API")
public class RecordingController {

    private final RecordingWatcherService watcherService;
    private final RecordingPipelineService pipelineService;

    // ──── Watcher kontrol ────

    @PostMapping("/watcher/start")
    @Operation(summary = "Kayit dizini izlemeyi baslat")
    public ResponseEntity<Map<String, Object>> startWatcher() {
        watcherService.start();
        return ResponseEntity.ok(Map.of("running", true));
    }

    @PostMapping("/watcher/stop")
    @Operation(summary = "Kayit dizini izlemeyi durdur")
    public ResponseEntity<Map<String, Object>> stopWatcher() {
        watcherService.stop();
        return ResponseEntity.ok(Map.of("running", false));
    }

    @GetMapping("/watcher/status")
    @Operation(summary = "Watcher ve pipeline durumu")
    public ResponseEntity<Map<String, Object>> watcherStatus() {
        Map<String, Object> stats = pipelineService.getStats();
        return ResponseEntity.ok(Map.of(
                "watcherRunning", watcherService.isRunning(),
                "activeProcessing", watcherService.getActiveProcessingCount(),
                "pipeline", stats));
    }

    // ──── Manuel upload ────

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Audio dosyasini manuel olarak yukle ve pipeline'a gonder",
            description = "Watcher disinda, dogrudan HTTP uzerinden audio yuklemek icin")
    public ResponseEntity<Map<String, String>> uploadRecording(
            @RequestParam("file") MultipartFile file) throws IOException {

        // Gecici dizine kaydet
        Path tempFile = Files.createTempFile("upload-", "-" + file.getOriginalFilename());
        file.transferTo(tempFile);

        // Pipeline'a gonder (ayri Virtual Thread'de calisir)
        Thread.ofVirtual()
                .name("manual-upload-" + file.getOriginalFilename())
                .start(() -> pipelineService.processRecording(tempFile));

        return ResponseEntity.accepted().body(Map.of(
                "status", "ACCEPTED",
                "fileName", file.getOriginalFilename(),
                "message", "Dosya pipeline'a gonderildi, /recordings/list ile takip edin"));
    }

    // ──── Kayit ve transkript sorgulama ────

    @GetMapping("/list")
    @Operation(summary = "Tum islenen kayitlari listele")
    public ResponseEntity<Map<String, RecordingMetadata>> listRecordings() {
        return ResponseEntity.ok(pipelineService.getAllRecordings());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Belirli bir kaydin durumunu getir")
    public ResponseEntity<RecordingMetadata> getRecording(@PathVariable String id) {
        RecordingMetadata meta = pipelineService.getRecording(id);
        if (meta == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(meta);
    }

    @GetMapping("/{id}/transcript")
    @Operation(summary = "Belirli bir kaydin transkriptini getir")
    public ResponseEntity<TranscriptResult> getTranscript(@PathVariable String id) {
        TranscriptResult transcript = pipelineService.getTranscript(id);
        if (transcript == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(transcript);
    }
}
