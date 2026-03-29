package com.databoss.sfbrec.service;

import com.databoss.sfbrec.model.RecordingMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Ses dosyasi metadata cikartici.
 * javax.sound.sampled kullanir — ek kutuphane gerektirmez.
 */
@Slf4j
@Service
public class AudioMetadataService {

    /**
     * Dosya path'inden metadata cikarir.
     * WAV dosyalari icin header parse edilir, digerleri icin
     * sadece dosya bilgileri dondurulur.
     */
    public RecordingMetadata extractMetadata(Path filePath) {
        String fileName = filePath.getFileName().toString();
        String recordingId = generateRecordingId(fileName);

        var builder = RecordingMetadata.builder()
                .recordingId(recordingId)
                .filePath(filePath)
                .fileName(fileName);

        try {
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            builder.fileSizeBytes(attrs.size())
                   .fileCreatedAt(attrs.creationTime().toInstant());
        } catch (Exception e) {
            log.warn("Dosya attribute'lari okunamadi: {}", filePath, e);
            builder.fileSizeBytes(filePath.toFile().length())
                   .fileCreatedAt(Instant.now());
        }

        // WAV header parse
        if (fileName.toLowerCase().endsWith(".wav")) {
            extractWavMetadata(filePath, builder);
        } else {
            builder.audioFormat(detectFormatFromExtension(fileName));
        }

        // SfB compliance recording dosya isimlerinden metadata cikarma
        // Tipik format: conf_sip-user@domain.com_2026-03-26T14-30-00.wav
        extractSfbMetadata(fileName, builder);

        return builder.build();
    }

    private void extractWavMetadata(Path filePath, RecordingMetadata.RecordingMetadataBuilder builder) {
        try {
            File file = filePath.toFile();
            AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(file);
            AudioFormat format = fileFormat.getFormat();

            int sampleRate = (int) format.getSampleRate();
            int channels = format.getChannels();
            int bitsPerSample = format.getSampleSizeInBits();

            builder.audioFormat("PCM")
                   .sampleRate(sampleRate)
                   .channels(channels)
                   .bitsPerSample(bitsPerSample);

            // Sure hesapla: frame sayisi / sample rate
            long frameLength = fileFormat.getFrameLength();
            if (frameLength > 0 && sampleRate > 0) {
                double durationSeconds = (double) frameLength / sampleRate;
                builder.audioDuration(Duration.ofMillis((long) (durationSeconds * 1000)));
            }

            log.debug("WAV metadata: {}Hz, {}ch, {}bit, frames={}",
                    sampleRate, channels, bitsPerSample, frameLength);

        } catch (Exception e) {
            log.warn("WAV header parse hatasi: {} — {}", filePath.getFileName(), e.getMessage());
            builder.audioFormat("WAV/UNKNOWN");
        }
    }

    private void extractSfbMetadata(String fileName, RecordingMetadata.RecordingMetadataBuilder builder) {
        // SfB compliance recording dosya isimleri:
        // conf_sip-user@domain.com_2026-03-26T14-30-00.wav
        // veya: Recording_12345_2026-03-26.wav
        if (fileName.startsWith("conf_")) {
            String withoutPrefix = fileName.substring(5);
            int lastUnderscore = withoutPrefix.lastIndexOf('_');
            if (lastUnderscore > 0) {
                String sipPart = withoutPrefix.substring(0, lastUnderscore);
                // sip- prefix'ini sip: ile degistir
                if (sipPart.startsWith("sip-")) {
                    builder.organizer("sip:" + sipPart.substring(4));
                }
                builder.conferenceUri("sip:conference:" + sipPart);
            }
        }
    }

    private String detectFormatFromExtension(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".wma")) return "WMA";
        if (lower.endsWith(".mp4") || lower.endsWith(".m4a")) return "AAC";
        if (lower.endsWith(".pcm")) return "PCM_RAW";
        if (lower.endsWith(".ogg")) return "OGG";
        return "UNKNOWN";
    }

    private String generateRecordingId(String fileName) {
        // Dosya adindan deterministik ID uret (ayni dosya tekrar islenmez)
        return "rec-" + UUID.nameUUIDFromBytes(fileName.getBytes()).toString().substring(0, 12);
    }
}
