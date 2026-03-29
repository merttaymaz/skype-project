# Skype for Business — Ses & Iletisim Platform Entegrasyonu

SfB Server (on-premise) ile entegre calisarak presence yonetimi, anlik mesajlasma, ses kaydi ve gercek zamanli transkript ureten mikro-servis mimarisi.

## Projeler

| Proje | Teknoloji | Aciklama |
|---|---|---|
| **sfb-ucwa-spring** | Spring Boot 3.3 / Java 21 | UCWA (Unified Communications Web API) uzerinden presence, IM, contact search, event channel yonetimi. OAuth2 + ADFS/Azure AD. |
| **sfb-recording-forwarder** | Spring Boot 3.3 / Java 21 | Compliance recording dizinini izleyerek tamamlanan ses dosyalarini STT servisine gonderen batch pipeline. Virtual Threads + Semaphore. |
| **sfb-realtime-stt** | Spring Boot 3.3 / Java 21 + UCMA (.NET) | Meeting ses kaydini kayit bitmeden, parcali olarak STT'ye gondererek gercek zamanli transkript ureten pipeline. WebSocket + Virtual Threads. |
| **sfb-audio-bridge** | .NET Framework 4.8 / C# | UCMA bot — SfB meeting audio'sunu yakalayip Spring Boot'a WebSocket ile aktaran Windows Service. TopShelf ile deploy. |

## Mimari Genel Bakis

```
SfB Server (Windows / On-Premise)
┌─────────────────────────────────────────────────────┐
│                                                     │
│  UCWA API ──────────► sfb-ucwa-spring               │  Presence, IM, Contact
│  (REST/JSON)          (Spring Boot, K8s)            │
│                                                     │
│  Compliance Rec. ───► sfb-recording-forwarder       │  Batch STT
│  (File System)        (Spring Boot, Docker)         │
│                                                     │
│  UCMA (RTP Audio) ──► sfb-audio-bridge ──► WS ──►  │
│  (.NET Service)       sfb-realtime-stt              │  Realtime STT
│                       (Spring Boot, K8s)            │
│                                                     │
└─────────────────────────────────────────────────────┘
                          │
                          ▼
               External STT Service
          (Whisper / Azure Speech / Google)
```

## Gereksinimler

| Bilesen | Gereksinim |
|---|---|
| Java projeleri | JDK 21+, Gradle 8.x |
| .NET projeleri | .NET Framework 4.8, UCMA 5.0 SDK/Runtime |
| SfB Server | Skype for Business Server 2015/2019 |
| Auth | ADFS veya Azure AD (OAuth2) |
| Deploy | Docker, Kubernetes (opsiyonel), Windows Server (UCMA bot icin) |

## Hizli Baslangic

```bash
# sfb-ucwa-spring
cd sfb-ucwa-spring
cp .env.example .env   # SfB degerlerini yaz
./gradlew bootRun

# sfb-recording-forwarder
cd sfb-recording-forwarder
docker-compose up -d

# sfb-realtime-stt (Spring app)
cd sfb-realtime-stt/spring-app
./gradlew bootRun
```

Her projenin kendi README dosyasinda detayli kurulum, konfigürasyon ve troubleshooting bilgileri mevcuttur.
