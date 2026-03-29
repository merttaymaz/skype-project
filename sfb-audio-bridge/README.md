# SfB Audio Bridge — Deployment Rehberi

UCMA bot'un SfB meeting audio'sunu yakalayip Spring Boot'a WebSocket ile aktaran .NET Windows Service.

---

## EXE mi DLL mi?

**EXE** üretecek. TopShelf kütüphanesi sayesinde aynı EXE hem konsol uygulaması hem Windows Service olarak çalışır. DLL olarak değil, standalone bir Windows Service olarak deploy edilir.

```
SfbAudioBridge.exe              ← Ana çalıştırılabilir (EXE)
SfbAudioBridge.exe.config       ← Konfigürasyon (App.config)
NLog.config                     ← Log ayarları
Microsoft.Rtc.Collaboration.dll ← UCMA SDK (lib/ dizininden)
Microsoft.Rtc.Signaling.dll     ← UCMA SDK
Microsoft.Rtc.Internal.dll      ← UCMA SDK
Topshelf.dll                    ← Windows Service wrapper
Newtonsoft.Json.dll             ← JSON
NLog.dll                        ← Logging
```

---

## Nereye Deploy Edilecek?

**SfB Front-End Server'ın kendisine VEYA aynı Active Directory domain'indeki ayrı bir Windows Server'a.**

```
Seçenek A (Önerilen):
┌──────────────────────────────────────────┐
│ Ayrı Windows Server 2019/2022           │
│ (sfb-audio-pool.yourdomain.com)          │
│                                          │
│  C:\SfbAudioBridge\                      │
│    SfbAudioBridge.exe  ← Windows Service │
│    logs\                                 │
│                                          │
│  Gereksinimler:                          │
│  • .NET Framework 4.8                    │
│  • UCMA 5.0 Runtime                      │
│  • SSL Sertifika (LocalMachine\My)       │
│  • SfB Front-End'e 5061/TCP erişimi      │
│  • Spring server'a WS erişimi            │
└──────────────┬───────────────────────────┘
               │ MTLS (5061)
┌──────────────▼───────────────────────────┐
│ SfB Front-End Server                     │
│ (sfbfe01.yourdomain.com)                 │
└──────────────────────────────────────────┘

Seçenek B (Küçük ortam):
  SfB Front-End Server'ın kendisine kur.
  Avantaj: Ek sunucu yok.
  Dezavantaj: FE üzerinde ek yük.
```

---

## Kurulum Adımları (Sıralı)

### Adım 1 — Hedef Sunucu Hazırlığı

```powershell
# .NET Framework 4.8 kontrol
(Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full").Release
# 528040 veya üzeri olmalı

# UCMA 5.0 Runtime kur (SDK değil, Runtime yeterli)
# SfB Server ISO → Setup\amd64\UcmaRuntime.msi
msiexec /i UcmaRuntime.msi /qb

# Veya SDK (geliştirme de yapacaksan)
msiexec /i UcmaSdk.msi /qb
```

### Adım 2 — SSL Sertifika

Bot, SfB Front-End ile MTLS (Mutual TLS) üzerinden konuşur. Makineye ait bir SSL sertifika gerekir.

```powershell
# Seçenek A: SfB'nin mevcut sertifikasını kullan (FE üzerine kuruyorsan)
Get-CsCertificate | Format-List

# Seçenek B: Yeni sertifika iste (ayrı sunucuya kuruyorsan)
# AD CS (Certificate Services) üzerinden:
$cert = New-SelfSignedCertificate `
    -DnsName "sfb-audio-pool.yourdomain.com" `
    -CertStoreLocation "Cert:\LocalMachine\My" `
    -KeyExportPolicy Exportable `
    -KeyLength 2048 `
    -NotAfter (Get-Date).AddYears(3)

# VEYA internal CA'dan iste:
# certreq ile CSR oluştur → CA'ya gönder → sertifikayı yükle

# Kontrol:
Get-ChildItem Cert:\LocalMachine\My | Where-Object {
    $_.Subject -like "*sfb-audio-pool*" -and $_.NotAfter -gt (Get-Date)
} | Format-List Subject, Thumbprint, NotAfter
```

### Adım 3 — SfB Topology Kaydı

**Bu adım SfB Management Shell'de çalıştırılır** (SfB FE sunucusu üzerinde):

```powershell
# 1. Trusted Application Pool oluştur
New-CsTrustedApplicationPool `
    -Identity "sfb-audio-pool.yourdomain.com" `
    -Registrar "Registrar:sfbfe01.yourdomain.com" `
    -Site "Site:Istanbul" `
    -TreatAsAuthenticated $true `
    -ThrottleAsServer $true `
    -RequiresReplication $false

# 2. Trusted Application kaydet
New-CsTrustedApplication `
    -ApplicationId "sfb-audio-bridge" `
    -TrustedApplicationPoolFqdn "sfb-audio-pool.yourdomain.com" `
    -Port 8443

# 3. Topology yayınla (ZORUNLU — bu olmadan FE bot'u tanımaz)
Enable-CsTopology

# 4. Application Endpoint oluştur
New-CsTrustedApplicationEndpoint `
    -ApplicationId "sfb-audio-bridge" `
    -TrustedApplicationPoolFqdn "sfb-audio-pool.yourdomain.com" `
    -SipAddress "sip:sfb-audio-bridge@yourdomain.com" `
    -DisplayName "SfB Audio Bridge"

# 5. Sonucu doğrula
Get-CsTrustedApplication | Format-List
Get-CsTrustedApplicationEndpoint | Format-List

# 6. GRUU değerini not al — App.config'e yazılacak:
Get-CsTrustedApplicationEndpoint | Select-Object OwnerUrn, SipAddress
```

### Adım 4 — DNS Kaydı

```powershell
# Eğer ayrı sunucuya kuruyorsan, Pool FQDN'i DNS'e ekle
# A kaydı: sfb-audio-pool.yourdomain.com → sunucu IP'si
Add-DnsServerResourceRecordA `
    -Name "sfb-audio-pool" `
    -ZoneName "yourdomain.com" `
    -IPv4Address "10.0.1.50"
```

### Adım 5 — Build

Geliştirme makinende (Visual Studio 2022 veya MSBuild):

```cmd
:: Visual Studio ile
devenv SfbAudioBridge.sln /build Release

:: Veya MSBuild ile
msbuild SfbAudioBridge.csproj /p:Configuration=Release /p:Platform=AnyCPU

:: Çıktı dizini: bin\Release\
dir bin\Release\SfbAudioBridge.exe
```

### Adım 6 — Deploy (Otomatik Script)

```powershell
# Build output'u hedef sunucuya kopyala
# (veya doğrudan hedef sunucuda build et)

# Tam kurulum (topology + service)
.\Deploy-SfbAudioBridge.ps1 -Action install `
    -PoolFqdn "sfb-audio-pool.yourdomain.com" `
    -Registrar "sfbfe01.yourdomain.com" `
    -SiteName "Istanbul" `
    -CertSubject "sfb-audio-pool.yourdomain.com" `
    -BuildDir ".\bin\Release"
```

### Adım 6 Alternatif — Manuel Deploy

```powershell
# 1. Dizin oluştur
New-Item -ItemType Directory -Path "C:\SfbAudioBridge\logs" -Force

# 2. Dosyaları kopyala
Copy-Item "bin\Release\*" -Destination "C:\SfbAudioBridge\" -Recurse

# 3. App.config düzenle
notepad "C:\SfbAudioBridge\SfbAudioBridge.exe.config"
# → Sfb.* ve Spring.* değerlerini doldur

# 4. Konsol olarak test et (önce service kurmadan)
cd C:\SfbAudioBridge
.\SfbAudioBridge.exe

# 5. Çalışıyorsa Windows Service olarak kur
.\SfbAudioBridge.exe install
.\SfbAudioBridge.exe start

# VEYA sc.exe ile
sc create SfbAudioBridge binPath= "C:\SfbAudioBridge\SfbAudioBridge.exe" start= auto
sc description SfbAudioBridge "SfB Audio Bridge to Spring STT"
sc failure SfbAudioBridge reset= 86400 actions= restart/60000/restart/300000/restart/900000
net start SfbAudioBridge
```

---

## Doğrulama

```powershell
# Service durumu
Get-Service SfbAudioBridge

# Log takip
Get-Content "C:\SfbAudioBridge\logs\sfb-audio-bridge.log" -Wait -Tail 50

# Beklenen log çıktısı:
# ═══════════════════════════════════════
#   SfB Audio Bridge baslatiliyor...
#   App ID:    sfb-audio-bridge
#   Pool FQDN: sfb-audio-pool.yourdomain.com
# ═══════════════════════════════════════
# SSL sertifika bulundu: CN=sfb-audio-pool.yourdomain.com
# CollaborationPlatform baslatildi
# ApplicationEndpoint kuruldu: sip:sfb-audio-bridge@yourdomain.com;gruu;...
# SfB Audio Bridge hazir — conference bekleniyor...

# Spring tarafında WebSocket bağlantısı kontrol
curl http://spring-server:8480/api/v1/stream/stats
```

---

## Firewall Kuralları

| Kaynak | Hedef | Port | Protokol | Açıklama |
|---|---|---|---|---|
| Bot sunucusu | SfB FE | 5061/TCP | MTLS | SIP signaling |
| Bot sunucusu | SfB FE | 5062/TCP | MTLS | UCMA (varsa) |
| Bot sunucusu | SfB Mediation | 49152-57500/UDP | SRTP | Audio media (RTP) |
| Bot sunucusu | Spring server | 8480/TCP | WS | WebSocket |
| SfB FE | Bot sunucusu | 8443/TCP | MTLS | Trusted App callback |

```powershell
# Windows Firewall kuralları
New-NetFirewallRule -DisplayName "SfB Audio Bridge - SIP" `
    -Direction Outbound -Protocol TCP -RemotePort 5061 -Action Allow

New-NetFirewallRule -DisplayName "SfB Audio Bridge - Media" `
    -Direction Inbound -Protocol UDP -LocalPort 49152-57500 -Action Allow

New-NetFirewallRule -DisplayName "SfB Audio Bridge - Trusted App" `
    -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow
```

---

## Mimari Bütünlük

```
Windows Server (Domain-joined)           Kubernetes (data-boss.com.tr)
┌────────────────────────────────┐       ┌──────────────────────────────┐
│ C:\SfbAudioBridge\             │       │ sfb-realtime-stt pod         │
│                                │  WS   │                              │
│ SfbAudioBridge.exe ────────────┼──────▶│ :8480/ws/audio-stream        │
│ (Windows Service)              │ PCM   │                              │
│                                │ chunk │ Virtual Thread (flusher)     │
│ UCMA 5.0 Runtime               │       │   ↓                         │
│ .NET Framework 4.8             │       │ StreamingSttService          │
│                                │       │   ↓                         │
│ ◀──── MTLS (5061) ────▶        │       │ External STT API             │
│ SfB Front-End Server           │       │ (Whisper / Azure Speech)     │
└────────────────────────────────┘       └──────────────────────────────┘
```

**Önemli:** UCMA botu mutlaka Windows'ta kalacak — Linux'a taşınamaz. Ama sadece bir "köprü" görevi görüyor (PCM → WebSocket), tüm akıllı iş Spring tarafında.

---

## Güncelleme

```powershell
# 1. Yeni build'i hazırla
msbuild /p:Configuration=Release

# 2. Service durdur
Stop-Service SfbAudioBridge

# 3. Dosyaları güncelle
Copy-Item "bin\Release\*" -Destination "C:\SfbAudioBridge\" -Force

# 4. Service başlat
Start-Service SfbAudioBridge
```

---

## Troubleshooting

| Hata | Neden | Çözüm |
|---|---|---|
| "Sertifika bulunamadı" | Cert store'da yok | `Get-ChildItem Cert:\LocalMachine\My` kontrol et |
| "Platform başlatılamadı" | UCMA Runtime eksik | `UcmaRuntime.msi` kur |
| "Endpoint kurulamadı" | Topology kaydı yok | `Get-CsTrustedApplication` kontrol et |
| "Connection refused" (5061) | Firewall | FE'ye 5061/TCP aç |
| "WebSocket bağlanamadı" | Spring kapalı veya erişilemez | Spring health check + network |
| "AudioVideoFlow null" | Bot conference'a ses ile katılamamış | AV call establish loglarını incele |
| Service başlamıyor | Config hatası | Önce konsol modunda çalıştır: `SfbAudioBridge.exe` |
