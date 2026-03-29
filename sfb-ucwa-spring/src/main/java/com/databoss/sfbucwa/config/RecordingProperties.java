package com.databoss.sfbucwa.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.List;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "sfb.recording")
public class RecordingProperties {

    /** SfB recording dosyalarinin yazildigi dizin (NFS/SMB mount) */
    @NotBlank
    private String watchDir = "/mnt/sfb-recordings";

    /** Islenen dosyalarin tasindigi dizin */
    private String processedDir = "/mnt/sfb-recordings/processed";

    /** Hatali dosyalarin tasindigi dizin */
    private String failedDir = "/mnt/sfb-recordings/failed";

    /** Gecici calisma dizini (ffmpeg output vb.) */
    private String tempDir = "/tmp/sfb-audio";

    /** Izlenecek dosya uzantilari */
    private List<String> watchExtensions = List.of(".wav", ".mp3", ".wma", ".mp4");

    /** Yeni dosya yazilmasinin bitmesini beklemek icin (saniye) */
    private int fileStabilityWaitSeconds = 5;

    /** Paralel isleme icin Virtual Thread havuzu buyuklugu */
    private int maxConcurrentProcessing = 10;

    /** STT servisi URL'si */
    @NotBlank
    private String sttServiceUrl = "http://localhost:8000/v1/audio/transcriptions";

    /** STT servisi API key (varsa) */
    private String sttApiKey;

    /** STT hedef dil */
    private String sttLanguage = "tr";

    /** STT model adi */
    private String sttModel = "whisper-large-v3";

    /** STT servisi zaman asimi (saniye) */
    private int sttTimeoutSeconds = 300;

    /** FFmpeg binary yolu */
    private String ffmpegPath = "ffmpeg";

    /** Hedef audio format (STT icin) — 16kHz mono WAV */
    private int targetSampleRate = 16000;
    private int targetChannels = 1;

    /** Dosya watcher aktif mi */
    private boolean enabled = true;

    public Path getWatchPath() { return Path.of(watchDir); }
    public Path getProcessedPath() { return Path.of(processedDir); }
    public Path getFailedPath() { return Path.of(failedDir); }
    public Path getTempPath() { return Path.of(tempDir); }
}
