package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.config.RecordingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FFmpeg ile audio format donusumu.
 *
 * <p>SfB kayitlari genellikle WMA veya stereo WAV olarak gelir.
 * STT servisleri icin 16kHz mono WAV'a donusturur.</p>
 *
 * <p>Virtual Thread icinde calisir — ProcessBuilder.start() blocking I/O yapar
 * ve Virtual Thread bunu otomatik olarak park eder (platform thread bloklamaz).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioConverterService {

    private final RecordingProperties props;

    private static final Pattern DURATION_PATTERN =
            Pattern.compile("Duration:\\s*(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{2})");

    /**
     * Audio dosyasini STT uyumlu formata donusturur.
     * Hedef: 16kHz, mono, 16-bit PCM WAV.
     *
     * @param inputFile kaynak audio dosyasi
     * @return donusturulmus WAV dosyasinin yolu
     */
    public Path convertToSttFormat(Path inputFile) throws IOException, InterruptedException {
        Files.createDirectories(props.getTempPath());

        String outputName = inputFile.getFileName().toString()
                .replaceAll("\\.[^.]+$", "") + "_16k_mono.wav";
        Path outputFile = props.getTempPath().resolve(outputName);

        log.info("Audio donusturuluyor: {} -> {}", inputFile.getFileName(), outputFile.getFileName());

        ProcessBuilder pb = new ProcessBuilder(
                props.getFfmpegPath(),
                "-i", inputFile.toAbsolutePath().toString(),
                "-ar", String.valueOf(props.getTargetSampleRate()),  // 16000 Hz
                "-ac", String.valueOf(props.getTargetChannels()),     // mono
                "-sample_fmt", "s16",                                 // 16-bit PCM
                "-f", "wav",                                          // WAV container
                "-y",                                                 // overwrite
                outputFile.toAbsolutePath().toString()
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Virtual Thread burada park eder — platform thread bloklanmaz
        String ffmpegOutput = new String(process.getInputStream().readAllBytes());

        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("FFmpeg timeout (120s): " + inputFile.getFileName());
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("FFmpeg hata (exit=" + exitCode + "): " + ffmpegOutput);
        }

        long outputSize = Files.size(outputFile);
        log.info("Donusum tamamlandi: {} ({}KB)", outputFile.getFileName(), outputSize / 1024);

        return outputFile;
    }

    /**
     * FFprobe ile audio suresi alir.
     */
    public Duration getAudioDuration(Path audioFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    props.getFfmpegPath().replace("ffmpeg", "ffprobe"),
                    "-i", audioFile.toAbsolutePath().toString(),
                    "-show_entries", "format=duration",
                    "-v", "quiet",
                    "-of", "default=noprint_wrappers=1:nokey=1"
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor(10, TimeUnit.SECONDS);

            double seconds = Double.parseDouble(output);
            return Duration.ofMillis((long) (seconds * 1000));
        } catch (Exception e) {
            log.warn("Audio suresi alinamadi: {}", e.getMessage());
            return Duration.ZERO;
        }
    }

    /**
     * Gecici donusturulmus dosyayi temizler.
     */
    public void cleanup(Path convertedFile) {
        try {
            if (convertedFile != null && Files.exists(convertedFile)) {
                Files.delete(convertedFile);
                log.debug("Gecici dosya silindi: {}", convertedFile.getFileName());
            }
        } catch (IOException e) {
            log.warn("Gecici dosya silinemedi: {}", convertedFile, e);
        }
    }
}
