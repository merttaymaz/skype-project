package com.databoss.sfbrec.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "recording")
public class RecordingProperties {

    /** SfB compliance recording cikti dizini (NFS/SMB mount veya local) */
    @NotBlank
    private String watchDirectory = "/mnt/sfb-recordings";

    /** Islenmis dosyalarin tasindigi dizin */
    private String processedDirectory = "/mnt/sfb-recordings/processed";

    /** Hatali dosyalarin tasindigi dizin */
    private String failedDirectory = "/mnt/sfb-recordings/failed";

    /** Desteklenen dosya uzantilari */
    private List<String> supportedExtensions = List.of(".wav", ".wma", ".mp4", ".pcm");

    /** Dizin tarama araligi */
    private Duration scanInterval = Duration.ofSeconds(10);

    /** Dosya stabilite bekleme suresi (yazim tamamlansin diye) */
    private Duration fileStabilityDelay = Duration.ofSeconds(5);

    /** Maksimum paralel islem sayisi (Virtual Thread sinirsiz ama downstream STT'yi korumak icin) */
    private int maxConcurrentProcessing = 10;

    /** Buyuk dosyalar icin chunk boyutu (byte) — 0 ise chunk'lamadan gonder */
    private long chunkSizeBytes = 0;

    /** STT servisi base URL */
    @NotBlank
    private String sttServiceUrl = "http://localhost:9090";

    /** STT servisi endpoint path */
    private String sttEndpointPath = "/api/v1/transcribe";

    /** STT servisi API key (opsiyonel) */
    private String sttApiKey;

    /** STT servisi timeout */
    private Duration sttTimeout = Duration.ofMinutes(5);

    /** STT servisi retry sayisi */
    private int sttMaxRetries = 3;

    /** Basarisiz islemde dosyayi sil yerine failed dizinine tasi */
    private boolean moveFailedFiles = true;

    /** Basarili islemde dosyayi sil yerine processed dizinine tasi */
    private boolean moveProcessedFiles = true;
}
