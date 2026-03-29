package com.databoss.sfbrec.service;

import com.databoss.sfbrec.config.RecordingProperties;
import com.databoss.sfbrec.model.ProcessingResult;
import com.databoss.sfbrec.model.RecordingMetadata;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SttForwardingServiceTest {

    private MockWebServer mockStt;
    private SttForwardingService sttService;
    private Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        mockStt = new MockWebServer();
        mockStt.start();

        RecordingProperties props = new RecordingProperties();
        props.setSttServiceUrl(mockStt.url("/").toString());
        props.setSttEndpointPath("/api/v1/transcribe");
        props.setSttTimeout(Duration.ofSeconds(10));
        props.setSttMaxRetries(2);

        WebClient webClient = WebClient.builder()
                .baseUrl(mockStt.url("/").toString())
                .build();

        sttService = new SttForwardingService(webClient, props);

        // Gecici test WAV dosyasi olustur
        tempDir = Files.createTempDirectory("sfb-test-");
    }

    @AfterEach
    void teardown() throws IOException {
        mockStt.shutdown();
    }

    @Test
    @DisplayName("Basarili STT gonderimi transkript dondurur")
    void shouldForwardAndGetTranscript() throws IOException {
        // Mock STT yaniti
        mockStt.enqueue(new MockResponse()
                .setBody("{\"text\":\"Merhaba, bu bir test mesajidir.\"}")
                .addHeader("Content-Type", "application/json"));

        // Test dosyasi olustur
        Path testFile = tempDir.resolve("test-recording.wav");
        Files.write(testFile, createMinimalWavBytes());

        RecordingMetadata metadata = RecordingMetadata.builder()
                .recordingId("test-001")
                .filePath(testFile)
                .fileName("test-recording.wav")
                .fileSizeBytes(Files.size(testFile))
                .fileCreatedAt(Instant.now())
                .sampleRate(16000)
                .channels(1)
                .build();

        ProcessingResult result = sttService.forwardToStt(metadata);

        assertThat(result.getStatus()).isEqualTo(ProcessingResult.Status.SUCCESS);
        assertThat(result.getTranscript()).contains("Merhaba");
        assertThat(result.getProcessingTime()).isNotNull();
        assertThat(result.getHttpStatusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("STT 500 hatasi FAILED sonucu dondurur")
    void shouldHandleSttServerError() throws IOException {
        mockStt.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":\"internal server error\"}"));

        Path testFile = tempDir.resolve("error-test.wav");
        Files.write(testFile, createMinimalWavBytes());

        RecordingMetadata metadata = RecordingMetadata.builder()
                .recordingId("test-err-001")
                .filePath(testFile)
                .fileName("error-test.wav")
                .fileSizeBytes(Files.size(testFile))
                .build();

        ProcessingResult result = sttService.forwardToStt(metadata);

        assertThat(result.getStatus()).isEqualTo(ProcessingResult.Status.FAILED);
        assertThat(result.getHttpStatusCode()).isEqualTo(500);
        assertThat(result.getErrorMessage()).contains("500");
    }

    @Test
    @DisplayName("Retry mekanizmasi 2. denemede basarili olur")
    void shouldRetryAndSucceed() throws IOException {
        // 1. deneme basarisiz
        mockStt.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
        // 2. deneme basarili
        mockStt.enqueue(new MockResponse()
                .setBody("{\"text\":\"Retry sonrasi basarili\"}")
                .addHeader("Content-Type", "application/json"));

        Path testFile = tempDir.resolve("retry-test.wav");
        Files.write(testFile, createMinimalWavBytes());

        RecordingMetadata metadata = RecordingMetadata.builder()
                .recordingId("test-retry-001")
                .filePath(testFile)
                .fileName("retry-test.wav")
                .fileSizeBytes(Files.size(testFile))
                .build();

        ProcessingResult result = sttService.forwardWithRetry(metadata);

        assertThat(result.getStatus()).isEqualTo(ProcessingResult.Status.SUCCESS);
        assertThat(mockStt.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Multipart request dogru sekilde STT'ye gonderiliyor")
    void shouldSendMultipartCorrectly() throws Exception {
        mockStt.enqueue(new MockResponse()
                .setBody("{\"text\":\"ok\"}")
                .addHeader("Content-Type", "application/json"));

        Path testFile = tempDir.resolve("multipart-test.wav");
        Files.write(testFile, createMinimalWavBytes());

        RecordingMetadata metadata = RecordingMetadata.builder()
                .recordingId("test-mp-001")
                .filePath(testFile)
                .fileName("multipart-test.wav")
                .fileSizeBytes(Files.size(testFile))
                .conferenceUri("sip:conf@domain.com")
                .organizer("sip:admin@domain.com")
                .sampleRate(16000)
                .build();

        sttService.forwardToStt(metadata);

        RecordedRequest request = mockStt.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/v1/transcribe");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("multipart-test.wav");
        assertThat(body).contains("conference_uri");
    }

    /** Minimal gecerli WAV dosyasi (44 byte header + 100 byte silence) */
    private byte[] createMinimalWavBytes() {
        int dataSize = 100;
        int fileSize = 36 + dataSize;
        byte[] wav = new byte[44 + dataSize];

        // RIFF header
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeInt(wav, 4, fileSize);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';

        // fmt chunk
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeInt(wav, 16, 16);   // chunk size
        writeShort(wav, 20, 1);  // PCM format
        writeShort(wav, 22, 1);  // mono
        writeInt(wav, 24, 16000); // sample rate
        writeInt(wav, 28, 32000); // byte rate
        writeShort(wav, 32, 2);  // block align
        writeShort(wav, 34, 16); // bits per sample

        // data chunk
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeInt(wav, 40, dataSize);
        // data bytes are already 0 (silence)

        return wav;
    }

    private void writeInt(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xff);
        buf[offset + 1] = (byte) ((value >> 8) & 0xff);
        buf[offset + 2] = (byte) ((value >> 16) & 0xff);
        buf[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private void writeShort(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xff);
        buf[offset + 1] = (byte) ((value >> 8) & 0xff);
    }
}
