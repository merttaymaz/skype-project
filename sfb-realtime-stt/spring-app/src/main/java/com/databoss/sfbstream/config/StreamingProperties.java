package com.databoss.sfbstream.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "streaming")
public class StreamingProperties {

    /** PCM chunk'lari biriktirilip STT'ye gonderilmeden onceki bekleme suresi */
    private Duration flushInterval = Duration.ofSeconds(3);

    /** Minimum flush boyutu (byte). Bu kadar birikmeden STT'ye gonderilmez */
    private int minFlushSizeBytes = 16000; // ~0.5s @ 16kHz 16bit mono

    /** Maksimum buffer boyutu (byte). Asilirsa zorla flush yapilir */
    private int maxBufferSizeBytes = 960_000; // ~30s @ 16kHz 16bit mono

    /** STT servisi base URL */
    private String sttServiceUrl = "http://localhost:9090";

    /** STT endpoint path */
    private String sttEndpointPath = "/api/v1/transcribe";

    /** STT API key (opsiyonel) */
    private String sttApiKey;

    /** STT istek timeout */
    private Duration sttTimeout = Duration.ofSeconds(30);

    /** Partial transkriptleri birlestirip tam transkript olarak sakla */
    private boolean accumulateTranscripts = true;
}
