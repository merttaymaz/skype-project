package com.databoss.sfbrec.model;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Kayit dosyasi metadata'si.
 * Dosya adından ve WAV header'indan parse edilir.
 */
@Data
@Builder
public class RecordingMetadata {
    private String recordingId;
    private Path filePath;
    private String fileName;
    private long fileSizeBytes;
    private Instant fileCreatedAt;

    // WAV header bilgileri (varsa)
    private String audioFormat;     // PCM, WMA, etc.
    private int sampleRate;         // 16000, 44100, etc.
    private int channels;           // 1=mono, 2=stereo
    private int bitsPerSample;      // 16, 24
    private Duration audioDuration;

    // SfB metadata (dosya adindan veya sidecar XML'den)
    private String conferenceUri;
    private String organizer;
}
