package com.databoss.sfbstream.model;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tek bir conference/meeting icin audio streaming session'i.
 *
 * <p>UCMA bot'tan gelen PCM chunk'lari biriktirir.
 * Flush tetiklendiginde biriken buffer'i WAV formatina cevirir
 * ve STT'ye gonderim icin hazir hale getirir.</p>
 *
 * <p>Thread-safe: Virtual Thread'ler concurrent erisebilir.</p>
 */
@Slf4j
public class AudioSession {

    @Getter private final String sessionId;
    @Getter private final String conferenceUri;
    @Getter private final int sampleRate;
    @Getter private final int channels;
    @Getter private final int bitsPerSample;
    @Getter private final Instant createdAt;

    private final ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
    private final ReentrantLock bufferLock = new ReentrantLock();

    @Getter private volatile Instant lastChunkAt;
    @Getter private volatile long totalBytesReceived;
    @Getter private volatile int flushCount;
    @Getter private volatile boolean active = true;

    /** Biriken partial transkriptler */
    @Getter private final List<String> transcriptSegments = new ArrayList<>();

    public AudioSession(String sessionId, String conferenceUri,
                        int sampleRate, int channels, int bitsPerSample) {
        this.sessionId = sessionId;
        this.conferenceUri = conferenceUri;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.createdAt = Instant.now();
        this.lastChunkAt = Instant.now();

        log.info("[{}] Session olusturuldu: {}Hz, {}ch, {}bit, conf={}",
                sessionId, sampleRate, channels, bitsPerSample, conferenceUri);
    }

    /**
     * PCM chunk ekler. UCMA bot'tan her ~20ms'de bir gelir.
     * Thread-safe.
     */
    public void appendChunk(byte[] data, int offset, int length) {
        bufferLock.lock();
        try {
            pcmBuffer.write(data, offset, length);
            totalBytesReceived += length;
            lastChunkAt = Instant.now();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Buffer'daki mevcut boyutu dondurur.
     */
    public int getBufferSize() {
        bufferLock.lock();
        try {
            return pcmBuffer.size();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Buffer'i bosaltir ve WAV formatinda byte[] dondurur.
     * STT servisleri genellikle WAV bekler, raw PCM degil.
     *
     * @return WAV header + PCM data, veya null (buffer bos ise)
     */
    public byte[] flushAsWav() {
        bufferLock.lock();
        try {
            if (pcmBuffer.size() == 0) return null;

            byte[] pcmData = pcmBuffer.toByteArray();
            pcmBuffer.reset();
            flushCount++;

            log.debug("[{}] Flush #{}: {} bytes PCM → WAV",
                    sessionId, flushCount, pcmData.length);

            return wrapWithWavHeader(pcmData);
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Partial transkript ekler.
     */
    public void addTranscriptSegment(String text) {
        if (text != null && !text.isBlank()) {
            transcriptSegments.add(text);
        }
    }

    /**
     * Tum partial transkriptleri birlestirip tam metin dondurur.
     */
    public String getFullTranscript() {
        return String.join(" ", transcriptSegments);
    }

    /**
     * Session'in ne kadar suredir aktif oldugunu dondurur.
     */
    public Duration getElapsedTime() {
        return Duration.between(createdAt, Instant.now());
    }

    /**
     * Tahmini audio suresi (biriken toplam byte'dan hesaplanan).
     */
    public Duration getEstimatedAudioDuration() {
        int bytesPerSecond = sampleRate * channels * (bitsPerSample / 8);
        if (bytesPerSecond == 0) return Duration.ZERO;
        long seconds = totalBytesReceived / bytesPerSecond;
        return Duration.ofSeconds(seconds);
    }

    public void close() {
        active = false;
        log.info("[{}] Session kapatildi. Toplam: {} bytes, {} flush, {} segment, sure: {}",
                sessionId, totalBytesReceived, flushCount,
                transcriptSegments.size(), getElapsedTime());
    }

    // ─── WAV Header Olusturma ───

    /**
     * Raw PCM verisini WAV container'a sarar.
     * STT servisleri (Whisper, Azure Speech, vs.) WAV formatini bekler.
     */
    private byte[] wrapWithWavHeader(byte[] pcmData) {
        int dataSize = pcmData.length;
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);

        byte[] wav = new byte[44 + dataSize];

        // RIFF header
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeInt(wav, 4, 36 + dataSize);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';

        // fmt sub-chunk
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeInt(wav, 16, 16);                 // sub-chunk size
        writeShort(wav, 20, 1);                // PCM format
        writeShort(wav, 22, channels);
        writeInt(wav, 24, sampleRate);
        writeInt(wav, 28, byteRate);
        writeShort(wav, 32, blockAlign);
        writeShort(wav, 34, bitsPerSample);

        // data sub-chunk
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeInt(wav, 40, dataSize);
        System.arraycopy(pcmData, 0, wav, 44, dataSize);

        return wav;
    }

    private static void writeInt(byte[] buf, int off, int val) {
        buf[off]     = (byte) (val & 0xff);
        buf[off + 1] = (byte) ((val >> 8) & 0xff);
        buf[off + 2] = (byte) ((val >> 16) & 0xff);
        buf[off + 3] = (byte) ((val >> 24) & 0xff);
    }

    private static void writeShort(byte[] buf, int off, int val) {
        buf[off]     = (byte) (val & 0xff);
        buf[off + 1] = (byte) ((val >> 8) & 0xff);
    }
}
