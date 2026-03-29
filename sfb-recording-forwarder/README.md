# SfB Recording Forwarder — Batch Audio STT Pipeline

SfB compliance recording dizinini izleyerek tamamlanan ses dosyalarini otomatik olarak external STT servisine gonderen Spring Boot uygulamasi.

---

## Mimari

```
SfB Server                          Spring Boot (JDK 21)
┌─────────────────────┐             ┌──────────────────────────────────────┐
│ Compliance Recording│             │                                      │
│ (IIS Media Store)   │             │  RecordingWatcher                    │
│                     │   NFS/SMB   │  ┌──────────────────────┐            │
│  /recordings/       ├────────────▶│  │ @Scheduled scan      │            │
│    conf_*.wav       │   mount     │  │ (her 10s)            │            │
│    conf_*.wma       │             │  └──────────┬───────────┘            │
│    conf_*.mp4       │             │             │                        │
└─────────────────────┘             │             ▼                        │
                                    │  ┌──────────────────────┐            │
                                    │  │ AudioMetadataService  │            │
                                    │  │  WAV header parse     │            │
                                    │  │  SfB dosya adi parse  │            │
                                    │  └──────────┬───────────┘            │
                                    │             ▼                        │
                                    │  ┌──────────────────────┐            │
                                    │  │ Virtual Thread Pool   │            │
                                    │  │  Semaphore(N)         │            │
                                    │  │  ┌────────────────┐   │            │
                                    │  │  │ SttForwarding  │   │────────┐   │
                                    │  │  │ Service        │   │        │   │
                                    │  │  │ multipart POST │   │        │   │
                                    │  │  │ + retry (exp.) │   │        │   │
                                    │  │  └────────────────┘   │        │   │
                                    │  └──────────────────────┘        │   │
                                    │             ▼                     │   │
                                    │  ┌──────────────────────┐        │   │
                                    │  │ ProcessingTracker     │        │   │
                                    │  │  ConcurrentHashMap    │        │   │
                                    │  │  stats + results      │        │   │
                                    │  └──────────────────────┘        │   │
                                    └──────────────────────────────────┼───┘
                                                                       │
                                    ┌──────────────────────────────────┼───┐
                                    │  External STT Service            │   │
                                    │  (Whisper / Azure Speech /       │   │
                                    │   Google Speech / Self-hosted)   │   │
                                    │                                  ▼   │
                                    │  POST /api/v1/transcribe              │
                                    │  ← {"text": "Merhaba, toplanti..."}  │
                                    └──────────────────────────────────────┘
```

## Neden Bu Tasarim?

| Soru | Cevap |
|---|---|
| **Neden file watching?** | SfB compliance recording tamamlaninca dizine `.wav` olarak yazar. Kafka/event sistemi kurmadan en basit entegrasyon yolu. |
| **Neden Virtual Threads?** | Her dosya icin ayri thread gerekiyor (STT API'ye HTTP call blocking). VT ile 100 dosya = 100 VT ama sadece birkac OS thread. `Semaphore.acquire()` ve `block()` sifir maliyetli. |
| **Neden Semaphore?** | Downstream STT servisini korumak icin. 100 dosya geldiginde hepsini ayni anda gondermek yerine max N (default 10) paralel istek. |
| **Neden Kafka/RabbitMQ yok?** | Over-engineering. Dosya sistemi zaten kuyruk gorevi goruyor. processed/failed dizinleri ile durum takibi yeterli. |
| **Neden in-memory tracker?** | Basitlik. Restart'ta state kaybolur ama dosyalar hala processed/failed dizinlerinde — idempotent tasarim. |

---

## Bilesenler

| Sinif | Gorev |
|---|---|
| `RecordingWatcher` | Dizin tarama (@Scheduled), dosya filtreleme, stabilite kontrolu, VT dispatch |
| `AudioMetadataService` | WAV header parse (javax.sound), SfB dosya adi parse, metadata cikartma |
| `SttForwardingService` | Multipart POST ile STT'ye gonderim, exponential backoff retry |
| `ProcessingTracker` | In-memory islem takibi, istatistikler, duplicate koruması |
| `RecordingController` | REST API: stats, results, manuel islem tetikleme |
| `RecordingProperties` | Konfigurasyon (watch dir, STT URL, retry, timeout) |
| `VirtualThreadConfig` | Tomcat + async Virtual Thread konfigurasyonu |

---

## Kurulum & Calistirma

### Hizli Baslangic (Mock STT ile)

```bash
# Docker Compose ile (Mock STT dahil)
docker-compose up -d

# Test kayit dosyasi olustur
cp test.wav test-recordings/

# Sonuclari kontrol et
curl http://localhost:8480/api/v1/recordings/stats
```

### Manuel Calistirma

```bash
# Build
./gradlew bootJar

# Calistir
STT_SERVICE_URL=http://your-stt:9090 \
RECORDING_WATCH_DIR=/mnt/sfb-recordings \
java -jar build/libs/sfb-recording-forwarder.jar

# Swagger UI
open http://localhost:8480/swagger-ui.html
```

### Docker

```bash
# Build
docker build -t sfb-recording-forwarder .

# Calistir
docker run -d \
  -p 8480:8480 \
  -v /mnt/sfb-recordings:/mnt/sfb-recordings \
  -e STT_SERVICE_URL=http://stt-server:9090 \
  -e STT_MAX_CONCURRENT=10 \
  sfb-recording-forwarder
```

---

## Konfigurasyonlar

### Temel Ayarlar

| Parametre | Env Variable | Default | Aciklama |
|---|---|---|---|
| `recording.watch-directory` | `RECORDING_WATCH_DIR` | `/mnt/sfb-recordings` | SfB kayit dizini |
| `recording.processed-directory` | `RECORDING_PROCESSED_DIR` | `/mnt/sfb-recordings/processed` | Basarili islem dizini |
| `recording.failed-directory` | `RECORDING_FAILED_DIR` | `/mnt/sfb-recordings/failed` | Basarisiz islem dizini |
| `recording.scan-interval` | — | `PT10S` | Tarama araligi |
| `recording.file-stability-delay` | — | `PT5S` | Dosya yazim bekleme suresi |
| `recording.max-concurrent-processing` | `STT_MAX_CONCURRENT` | `10` | Max paralel STT isteği |

### STT Servisi Ayarlari

| Parametre | Env Variable | Default | Aciklama |
|---|---|---|---|
| `recording.stt-service-url` | `STT_SERVICE_URL` | `http://localhost:9090` | STT servisi URL |
| `recording.stt-endpoint-path` | `STT_ENDPOINT_PATH` | `/api/v1/transcribe` | STT endpoint |
| `recording.stt-api-key` | `STT_API_KEY` | — | Bearer token (opsiyonel) |
| `recording.stt-timeout` | — | `PT5M` | Tek istek timeout |
| `recording.stt-max-retries` | — | `3` | Max retry sayisi |

### Desteklenen Ses Formatlari

| Format | Uzanti | Aciklama |
|---|---|---|
| WAV | `.wav` | PCM header parse edilir (sampleRate, channels, bitsPerSample) |
| WMA | `.wma` | Windows Media Audio — SfB compliance default |
| MP4 | `.mp4` | AAC codec — bazi SfB konfigurasyonlari |
| PCM | `.pcm` | Raw PCM — headerless, sampleRate config'den alinir |

### Dosya Isleme Akisi

```
/mnt/sfb-recordings/
  ├── conf_sip-user@domain_2026-03-26.wav    ← Yeni dosya (izleniyor)
  ├── processed/
  │   └── conf_sip-user@domain_2026-03-25.wav ← Basarili (transkript alindi)
  └── failed/
      └── conf_corrupted_2026-03-24.wav       ← Basarisiz (3 retry sonrasi)
```

---

## REST API

| Method | Endpoint | Aciklama |
|---|---|---|
| GET | `/api/v1/recordings/stats` | Islem istatistikleri (toplam, basarili, basarisiz, aktif) |
| GET | `/api/v1/recordings/results` | Tum islem sonuclari |
| GET | `/api/v1/recordings/results/{id}` | Belirli kaydin sonucu |
| POST | `/api/v1/recordings/process` | Manuel dosya isleme (`{"filePath": "/path/to.wav"}`) |
| POST | `/api/v1/recordings/scan` | Manuel dizin taramasi tetikle |
| DELETE | `/api/v1/recordings/results` | Sonuclari temizle |

### Ornek Istekler

```bash
# Istatistikler
curl http://localhost:8480/api/v1/recordings/stats
# {"totalProcessed":42,"totalSuccess":40,"totalFailed":2,"activeProcessing":3,...}

# Manuel dosya isleme
curl -X POST http://localhost:8480/api/v1/recordings/process \
  -H "Content-Type: application/json" \
  -d '{"filePath": "/mnt/sfb-recordings/test.wav"}'

# Manuel tarama tetikle
curl -X POST http://localhost:8480/api/v1/recordings/scan
```

---

## Virtual Thread & Concurrency Detaylari

```java
// Her dosya icin Virtual Thread
Thread.startVirtualThread(() -> processFile(file));

// processFile icinde:
concurrencyLimiter.acquire();  // VT — OS thread bloke olmaz
try {
    result = sttService.forwardWithRetry(metadata); // VT — HTTP block() ucuz
    tracker.recordResult(result);
    moveFile(file, result.getStatus());
} finally {
    concurrencyLimiter.release();
}
```

| Senaryo | Platform Threads | Virtual Threads |
|---|---|---|
| 10 dosya ayni anda | 10 OS thread (STT I/O) | 10 VT, ~2 OS thread |
| 100 dosya ayni anda | 100 OS thread, context switch | 100 VT, ~4 OS thread |
| Semaphore.acquire() | OS thread bloke | Carrier thread serbest |
| HTTP block() 30s | OS thread 30s bloke | Carrier thread aninda serbest |

### Retry Stratejisi

```
Deneme 1 → basarisiz → 1s bekle (1²)
Deneme 2 → basarisiz → 4s bekle (2²)
Deneme 3 → basarisiz → FAILED olarak isaretle, failed/ dizinine tasi
```

---

## SfB Compliance Recording Entegrasyonu

SfB Server compliance recording etkinlestirildiginde, tum meeting ses kayitlari otomatik olarak belirtilen dizine yazilir.

### SfB Tarafinda Gerekli Ayarlar

```powershell
# Compliance recording etkinlestir
Set-CsConferencingPolicy -Identity Global -EnableComplianceRecording $true

# Kayit dizini ayarla (IIS Media Store)
# Bu dizin NFS/SMB ile Spring server'a mount edilecek
```

### NFS/SMB Mount

```bash
# Linux (Spring server)
mount -t cifs //sfb-server/recordings /mnt/sfb-recordings \
  -o username=svc_recording,domain=YOURDOMAIN

# veya fstab'a ekle:
# //sfb-server/recordings /mnt/sfb-recordings cifs credentials=/etc/smb-creds,_netdev 0 0
```

---

## Troubleshooting

| Problem | Neden | Cozum |
|---|---|---|
| Dosyalar islenmyor | Watch dizini bos veya erisim yok | `ls -la /mnt/sfb-recordings` kontrol et |
| "Zaten islenmis" | Ayni dosya tekrar taranmis | `DELETE /api/v1/recordings/results` ile temizle |
| STT timeout | Dosya cok buyuk veya STT yavas | `stt-timeout` artir, `max-concurrent` azalt |
| STT 401 | API key hatali | `STT_API_KEY` env variable kontrol et |
| STT 413 | Dosya boyutu limiti | STT servisi max upload limit kontrol et |
| Dosya stabil degil | Henuz yazim devam ediyor | `file-stability-delay` artir (PT10S) |
| Memory artisi | Cok fazla sonuc birikiyor | Periyodik `DELETE /results` veya restart |
| Mount erisim hatasi | SMB/NFS credentials | `mount` komutunu ve credentials'i kontrol et |

---

## Teknoloji Stack

| Teknoloji | Versiyon | Amac |
|---|---|---|
| Java | 21 | Virtual Threads, modern API |
| Spring Boot | 3.3.5 | Web framework, scheduling, actuator |
| WebClient (WebFlux) | — | Non-blocking STT HTTP client |
| Resilience4j | 2.2.0 | Retry, circuit breaker |
| SpringDoc OpenAPI | 2.6.0 | Swagger UI |
| Logstash Logback | 8.0 | JSON structured logging |
| JDK javax.sound | — | WAV header parse (ek kutuphane yok) |
| ZGC | — | Low-latency garbage collection |
