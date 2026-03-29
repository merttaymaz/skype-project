package com.databoss.sfbrec.model;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;

@Data
@Builder
public class ProcessingResult {

    public enum Status { SUCCESS, FAILED, SKIPPED, IN_PROGRESS }

    private String recordingId;
    private Status status;
    private String transcript;       // STT sonucu (basarili ise)
    private String errorMessage;     // Hata mesaji (basarisiz ise)
    private Instant startedAt;
    private Instant completedAt;
    private Duration processingTime;
    private long fileSizeBytes;
    private String sttProvider;      // Hangi STT servisi kullanildi
    private int httpStatusCode;      // STT servisinden donen HTTP status
}
