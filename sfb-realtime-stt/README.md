# SfB Realtime STT — Partial Audio Streaming Pipeline

SfB meeting ses kaydini **kayit bitmeden, parcali (partial) olarak** external STT servisine gondererek gercek zamanli transkript ureten pipeline.

---

## Mimari

```
SfB Server                    Spring Boot (JDK 21)
┌──────────────┐   WebSocket  ┌─────────────────────────────────────┐
│ Conference   │              │                                     │
│ Audio (RTP)  │              │  AudioStreamWebSocketHandler        │
│              │   Binary     │  ┌───────────────────────┐          │
│  UCMA Bot ◄──┼──(PCM 20ms)─┼─▶│ AudioSession          │          │
│  (.NET)      │              │  │  - pcmBuffer (BAOS)   │          │
│              │              │  │  - appendChunk()      │          │
│              │   Text       │  └──────────┬────────────┘          │
│              │◄─(partial)───┼─────────────┘                       │
└──────────────┘  transcript  │       ↓ her 3 saniye               │
                              │  ┌────────────────────┐             │
                              │  │ Virtual Thread      │ (flusher)  │
                              │  │  flushAsWav()       │             │
                              │  │  → WAV header + PCM │             │
                              │  └────────┬───────────┘             │
                              │           ↓                         │
                              │  ┌────────────────────┐             │
                              │  │ StreamingSttService │             │
                              │  │  multipart POST    │─────────┐   │
                              │  │  (block() — VT OK) │         │   │
                              │  └────────────────────┘         │   │
                              └─────────────────────────────────┼───┘
                                                                │
                              ┌──────────────────────────────────┼───┐
                              │  External STT Service            │   │
                              │  (Whisper / Azure Speech /       │   │
                              │   Google Speech / Self-hosted)   │   │
                              │                                  ▼   │
                              │  POST /api/v1/transcribe              │
                              │  ← {"text": "Merhaba, bu bir..."}    │
                              └──────────────────────────────────────┘
```

## Neden Bu Tasarim?

| Soru | Cevap |
|---|---|
| **Neden UCWA ile olmuyor?** | UCWA sadece signaling (presence, IM, meeting join/leave) yapar. Media stream'e (RTP audio) erisemez. |
| **Neden UCMA?** | UCMA, SfB Server uzerinde Trusted Application olarak AudioVideoFlow'a baglanip raw PCM buffer'lari yakalayabilen tek resmi API. |
| **Neden WebSocket?** | UCMA → Spring arasinda dusuk gecikmeli, bidirectional kanal. Partial transkriptler aninda geri donebilir. |
| **Neden Virtual Threads?** | Her session icin bir flusher thread gerekiyor. VT ile 100 concurrent session = 100 VT ama sadece birkaç OS thread. `Thread.sleep()` ve `block()` sifir maliyetli. |
| **Neden Kafka yok?** | Over-engineering. WebSocket + in-memory session yeterli. Kayip toleransi gerekirse ileride eklenebilir. |

---

## Bilesenler

### 1. UCMA Bot (C#/.NET Framework 4.8)

**Dosya:** `ucma-bot/Program.cs`

Minimal .NET konsol uygulamasi:
- SfB Server'a Trusted Application olarak baglanir
- Conference'a TrustedParticipant olarak katilir
- `SpeechRecognitionConnector` ile mixed audio alir (tum katilimcilar)
- Her 20ms'de ~640 byte PCM chunk'i WebSocket uzerinden Spring'e gonderir
- Spring'den gelen partial transkriptleri konsola basar

**Calistirma:**
```cmd
SfbAudioBridge.exe "sip:user@domain.com;gruu;opaque=app:conf:focus:id:XXXXX"
```

**Environment:**
```
SPRING_WS_URL=ws://spring-server:8480/ws/audio-stream
SFB_APP_ID=sfb-audio-bridge
SFB_TRUSTED_FQDN=sfb-ucwa-pool.yourdomain.com
```

### 2. Spring Boot App (Java 21, Virtual Threads)

**Dizin:** `spring-app/`

| Sinif | Gorev |
|---|---|
| `AudioStreamWebSocketHandler` | WS baglanti yonetimi, PCM chunk alma, flusher VT baslatma |
| `AudioSession` | Per-session PCM buffer, WAV olusturma, transkript biriktirme |
| `StreamingSttService` | Buffer → WAV → STT multipart POST (VT uzerinde block) |
| `SessionManager` | Aktif session takibi |
| `StreamController` | REST: /stats, /sessions, /transcript |

---

## Kurulum & Calistirma

### Spring App

```bash
cd spring-app

# Build
./gradlew bootJar

# Calistir (Mock STT ile test)
STT_SERVICE_URL=http://localhost:9090 \
FLUSH_INTERVAL=PT3S \
java -jar build/libs/sfb-realtime-stt.jar

# Swagger UI
open http://localhost:8480/swagger-ui.html
```

### UCMA Bot Gereksinimleri

UCMA bot'u calistirmak icin SfB Server ortaminda:

```
1. UCMA 5.0 SDK kurulu olmali (SfB Server medyasindan)
   C:\Program Files\Microsoft UCMA 5.0\SDK\

2. SfB Topology'de Trusted Application kaydi:
   New-CsTrustedApplicationPool -Identity sfb-audio-pool.domain.com ...
   New-CsTrustedApplication -ApplicationId sfb-audio-bridge -Port 8443

3. Bot'un calistigi makinede SfB sertifikasi yuklu olmali

4. .NET Framework 4.8 Runtime
```

---

## Konfigurasyonlar

### Flush Stratejisi

| Parametre | Default | Aciklama |
|---|---|---|
| `flush-interval` | 3s | Buffer'in STT'ye gonderilme araligi |
| `min-flush-size-bytes` | 16000 | Minimum ~0.5s audio birikmeden gonderilmez |
| `max-buffer-size-bytes` | 960000 | ~30s audio birikmeden zorla flush |

**Daha dusuk gecikme istiyorsan:**
```yaml
streaming:
  flush-interval: PT1S        # 1 saniye
  min-flush-size-bytes: 8000  # ~0.25s
```

**Daha az STT cagrisi istiyorsan:**
```yaml
streaming:
  flush-interval: PT10S        # 10 saniye
  min-flush-size-bytes: 160000 # ~5s
```

### Audio Format

UCMA default cikisi: **16kHz, 16-bit, mono PCM** (640 byte / 20ms chunk)

| Parametre | Deger | Hesaplama |
|---|---|---|
| Sample rate | 16000 Hz | - |
| Bit depth | 16 bit | - |
| Channels | 1 (mono) | Mixed audio — tum katilimcilar |
| Byte rate | 32000 B/s | 16000 × 1 × 2 |
| Chunk boyutu | 640 byte | 32000 × 0.020 |
| 1 dakika audio | ~1.92 MB | 32000 × 60 |
| 1 saat audio | ~115 MB | 32000 × 3600 |

---

## WebSocket Protokolu

```
Client (UCMA Bot)                          Server (Spring)
       │                                         │
       │── TEXT: session_start ──────────────────▶│
       │   {"type":"session_start",               │
       │    "conferenceUri":"sip:...",             │
       │    "sampleRate":16000,                   │
       │    "channels":1,                         │
       │    "bitsPerSample":16}                   │
       │                                         │
       │◀── TEXT: session_started ───────────────│
       │   {"type":"session_started",             │
       │    "sessionId":"stream-a1b2c3d4"}        │
       │                                         │
       │── BINARY: PCM chunk (640 bytes) ───────▶│ ← her 20ms
       │── BINARY: PCM chunk (640 bytes) ───────▶│
       │── BINARY: PCM chunk (640 bytes) ───────▶│
       │   ... (150 chunk = 3 saniye) ...         │
       │                                         │──▶ STT flush
       │◀── TEXT: partial_transcript ────────────│
       │   {"type":"partial_transcript",          │
       │    "text":"Merhaba arkadaslar",          │
       │    "flushNumber":1}                      │
       │                                         │
       │── BINARY: ... daha fazla chunk ────────▶│
       │                                         │──▶ STT flush
       │◀── TEXT: partial_transcript ────────────│
       │   {"text":"bugun gundem maddemiz..."}    │
       │                                         │
       │── TEXT: session_end ───────────────────▶│
       │                                         │──▶ final flush
       │◀── TEXT: final_transcript ──────────────│
       │   {"type":"final_transcript",            │
       │    "fullTranscript":"Merhaba ... maddemiz│..."} 
       │                                         │
```

---

## REST API

| Method | Endpoint | Aciklama |
|---|---|---|
| GET | `/api/v1/stream/stats` | Genel istatistikler |
| GET | `/api/v1/stream/sessions` | Aktif session listesi |
| GET | `/api/v1/stream/sessions/{id}/transcript` | Biriken transkript |

---

## Virtual Thread Detaylari

```java
// Her session icin flusher Virtual Thread
Thread.startVirtualThread(() -> flushLoop(sessionId, wsSession));

// flushLoop icinde:
while (session.isActive()) {
    Thread.sleep(3000);        // VT — OS thread bloke olmaz
    byte[] wav = session.flushAsWav();
    String text = sttService   // VT — HTTP block() ucuz
        .post().block();
    wsSession.sendMessage(text);
}
```

**Platform Thread karsilastirmasi:**

| Senaryo | Platform Threads | Virtual Threads |
|---|---|---|
| 10 concurrent meeting | 10 OS thread (flusher) + 10 (STT I/O) = 20 | 20 VT, ~2 OS thread |
| 100 concurrent meeting | 200 OS thread, context switch overhead | 200 VT, ~4 OS thread |
| Thread.sleep(3s) | OS thread 3s bloke | Carrier thread aninda serbest |
| HTTP block() 2s | OS thread 2s bloke | Carrier thread aninda serbest |

---

## Troubleshooting

| Problem | Neden | Cozum |
|---|---|---|
| UCMA bot baglanamiyor | Trusted App kaydi yok | SfB Shell'de New-CsTrustedApplication |
| Audio gelmiyor | AudioVideoFlow null | avCall.AudioVideoFlowConfigurationRequested event'ini dinle |
| WS baglanti kopuyor | Timeout | Server tarafinda idle timeout artir |
| STT cok yavas | Chunk cok buyuk | flush-interval'i azalt (PT1S) |
| Transkript bos | STT desteklenmeyen format | WAV header'i kontrol et, sampleRate=16000 |
| Memory artisi | Session kapatilmiyor | session_end sinyali gonderildiginden emin ol |
