package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.config.RecordingProperties;
import com.databoss.sfbucwa.model.RecordingMetadata;
import com.databoss.sfbucwa.model.TranscriptResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RecordingPipelineServiceTest {

    @TempDir
    Path tempDir;

    private RecordingPipelineService pipeline;
    private AudioConverterService audioConverter;
    private SttForwardingService sttService;

    @BeforeEach
    void setup() {
        RecordingProperties props = new RecordingProperties();
        props.setWatchDir(tempDir.resolve("watch").toString());
        props.setProcessedDir(tempDir.resolve("processed").toString());
        props.setFailedDir(tempDir.resolve("failed").toString());
        props.setTempDir(tempDir.resolve("tmp").toString());
        props.setFileStabilityWaitSeconds(0); // Test'te beklemeyelim

        audioConverter = mock(AudioConverterService.class);
        sttService = mock(SttForwardingService.class);
        Semaphore throttle = new Semaphore(5);

        pipeline = new RecordingPipelineService(props, audioConverter, sttService, throttle);
    }

    @Test
    @DisplayName("Basarili pipeline: detect → convert → STT → complete")
    void shouldProcessRecordingSuccessfully() throws Exception {
        // Sahte audio dosyasi olustur
        Path watchDir = tempDir.resolve("watch");
        Files.createDirectories(watchDir);
        Path audioFile = watchDir.resolve("meeting_001.wav");
        Files.writeString(audioFile, "fake audio content for test");

        // Mock: FFmpeg donusumu
        Path convertedFile = tempDir.resolve("tmp").resolve("meeting_001_16k_mono.wav");
        Files.createDirectories(convertedFile.getParent());
        Files.writeString(convertedFile, "converted audio");

        when(audioConverter.convertToSttFormat(any())).thenReturn(convertedFile);
        when(audioConverter.getAudioDuration(any())).thenReturn(Duration.ofSeconds(60));

        // Mock: STT servisi
        TranscriptResult mockTranscript = TranscriptResult.builder()
                .recordingId("test")
                .fullText("Merhaba bu bir test toplantisidir")
                .language("tr")
                .durationSeconds(60.0)
                .transcribedAt(Instant.now())
                .build();
        when(sttService.transcribe(any(), anyString())).thenReturn(mockTranscript);

        // Pipeline calistir
        pipeline.processRecording(audioFile);

        // Dogrulamalar
        verify(audioConverter).convertToSttFormat(any());
        verify(sttService).transcribe(any(), anyString());
        verify(audioConverter).cleanup(convertedFile);

        // Kayit durumu COMPLETED olmali
        var recordings = pipeline.getAllRecordings();
        assertThat(recordings).hasSize(1);

        RecordingMetadata meta = recordings.values().iterator().next();
        assertThat(meta.getStatus()).isEqualTo(RecordingMetadata.Status.COMPLETED);
        assertThat(meta.getOriginalFileName()).isEqualTo("meeting_001.wav");
    }

    @Test
    @DisplayName("FFmpeg hatasi durumunda FAILED status")
    void shouldMarkAsFailedOnConversionError() throws Exception {
        Path watchDir = tempDir.resolve("watch");
        Files.createDirectories(watchDir);
        Path audioFile = watchDir.resolve("corrupt.wav");
        Files.writeString(audioFile, "corrupt data");

        // FFmpeg hata firlatsin
        when(audioConverter.convertToSttFormat(any()))
                .thenThrow(new IOException("FFmpeg: Invalid audio stream"));

        // Pipeline calistir
        Files.createDirectories(tempDir.resolve("failed"));
        pipeline.processRecording(audioFile);

        // STT cagirilmamali
        verify(sttService, never()).transcribe(any(), anyString());

        // Status FAILED olmali
        var recordings = pipeline.getAllRecordings();
        RecordingMetadata meta = recordings.values().iterator().next();
        assertThat(meta.getStatus()).isEqualTo(RecordingMetadata.Status.FAILED);
        assertThat(meta.getErrorMessage()).contains("FFmpeg");
    }

    @Test
    @DisplayName("Stats dogru hesaplaniyor")
    void shouldCalculateStatsCorrectly() throws Exception {
        // Bos baslangic
        var stats = pipeline.getStats();
        assertThat(stats.get("total")).isEqualTo(0L);
        assertThat(stats.get("completed")).isEqualTo(0L);
    }
}
