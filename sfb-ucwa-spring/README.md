# SfB UCWA Spring — Skype for Business UCWA Entegrasyon Uygulaması

Skype for Business Server (On-Premises) veya Skype for Business Online üzerinde **UCWA (Unified Communications Web API)** kullanarak presence yönetimi, anlık mesajlaşma (IM) ve kişi arama işlemlerini gerçekleştiren bir Spring Boot 3.3 iskelet uygulamasıdır.

---

## İçindekiler

1. [Mimari Genel Bakış](#1-mimari-genel-bakış)
2. [Ön Gereksinimler](#2-ön-gereksinimler)
3. [Proje Yapısı](#3-proje-yapısı)
4. [Konfigürasyon](#4-konfigürasyon)
5. [Build & Çalıştırma](#5-build--çalıştırma)
6. [Docker ile Çalıştırma](#6-docker-ile-çalıştırma)
7. [Kubernetes / ArgoCD Deploy](#7-kubernetes--argocd-deploy)
8. [API Referansı](#8-api-referansı)
9. [Test Stratejisi](#9-test-stratejisi)
10. [UCWA Bootstrap Akışı](#10-ucwa-bootstrap-akışı)
11. [SfB Server Tarafı Gereksinimler](#11-sfb-server-tarafı-gereksinimler)
12. [Troubleshooting](#12-troubleshooting)
13. [Güvenlik Notları](#13-güvenlik-notları)

---

## 1. Mimari Genel Bakış

```
┌─────────────────────────────────────────────────────────┐
│                  Spring Boot Application                │
│                                                         │
│  ┌──────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │Controller │→ │  Services    │→ │  WebClient       │  │
│  │(REST API) │  │              │  │  (Reactive HTTP) │  │
│  └──────────┘  │- TokenService│  └────────┬─────────┘  │
│                │- Autodiscover│           │             │
│                │- Application │           │             │
│                │- Presence    │           │             │
│                │- Messaging   │           │             │
│                │- Contact     │           │             │
│                │- EventChannel│           │             │
│                └──────────────┘           │             │
└───────────────────────────────────────────┼─────────────┘
                                            │
                    ┌───────────────────────┼──────────┐
                    │   SfB Server / O365              │
                    │                                  │
                    │  ┌────────────┐ ┌─────────────┐  │
                    │  │Autodiscover│ │ UCWA REST   │  │
                    │  └────────────┘ │ /applications│  │
                    │  ┌────────────┐ │ /events     │  │
                    │  │ADFS/AzureAD│ │ /people     │  │
                    │  │(OAuth2)    │ │ /messaging  │  │
                    │  └────────────┘ └─────────────┘  │
                    └──────────────────────────────────┘
```

**Temel bileşenler:**

- **TokenService** — OAuth2 token yaşam döngüsü (client_credentials + ROPC), otomatik cache & refresh
- **AutodiscoverService** — SfB Autodiscover akışı ile UCWA endpoint keşfi
- **UcwaApplicationService** — UCWA Application kaynağı oluşturma/silme, URL çözümleme
- **PresenceService** — Kullanıcı presence durumu okuma/yazma
- **MessagingService** — IM conversation başlatma ve mesaj gönderme
- **ContactService** — SfB dizininde kişi arama
- **EventChannelService** — Long-polling ile UCWA event dinleme, Spring Event publish
- **UcwaLifecycleManager** — Otomatik bootstrap, health check, graceful shutdown

---

## 2. Ön Gereksinimler

### Geliştirme Makinesi

| Gereksinim | Minimum Versiyon | Not |
|---|---|---|
| **JDK** | 21 | Temurin/Corretto/GraalVM |
| **Gradle** | 8.10+ | Wrapper dahil, ayrı kurulum gerekmez |
| **Docker** | 24+ | Opsiyonel, container deploy için |
| **Git** | 2.x | Versiyon kontrol |

### SfB Server Tarafı (Kritik)

| Gereksinim | Açıklama |
|---|---|
| **SfB Server 2015/2019** | UCWA destekli on-prem kurulum, VEYA SfB Online (O365) |
| **ADFS 3.0+** | On-prem OAuth2 token provider (Azure AD ise gerek yok) |
| **Trusted Application** | SfB Topology'de kayıtlı uygulama (on-prem) |
| **Azure AD App Registration** | SfB Online kullanılıyorsa |
| **DNS A/CNAME** | `lyncdiscover.domain.com` çözümlenmeli |
| **SSL Sertifika** | UCWA endpoint'leri HTTPS gerektirir |
| **Firewall** | Uygulama → SfB Server 443/TCP açık olmalı |

### SfB Online (O365) İçin Ek Gereksinimler

| Gereksinim | Açıklama |
|---|---|
| **Azure AD Tenant** | O365 tenant'ınız |
| **App Registration** | Azure Portal → App registrations |
| **API Permissions** | `Skype for Business → User.ReadWrite`, `Contacts.ReadWrite` |
| **Admin Consent** | Tenant admin onayı gerekli |

---

## 3. Proje Yapısı

```
sfb-ucwa-spring/
├── build.gradle.kts              # Gradle build tanımı
├── settings.gradle.kts
├── Dockerfile                    # Multi-stage Docker image
├── docker-compose.yml            # Local development
├── .env.example                  # Örnek environment değişkenleri
├── .gitignore
├── k8s/
│   └── deployment.yaml           # K8s Deployment + Service + Secret
├── src/
│   ├── main/
│   │   ├── java/com/databoss/sfbucwa/
│   │   │   ├── SfbUcwaApplication.java       # @SpringBootApplication
│   │   │   ├── config/
│   │   │   │   ├── UcwaProperties.java        # @ConfigurationProperties
│   │   │   │   ├── WebClientConfig.java        # WebClient bean (TLS, logging)
│   │   │   │   └── SecurityConfig.java         # Spring Security
│   │   │   ├── controller/
│   │   │   │   └── UcwaController.java         # REST API endpoint'leri
│   │   │   ├── service/
│   │   │   │   ├── TokenService.java           # OAuth2 token yönetimi
│   │   │   │   ├── AutodiscoverService.java    # UCWA keşif
│   │   │   │   ├── UcwaApplicationService.java # Application CRUD
│   │   │   │   ├── PresenceService.java        # Presence get/set
│   │   │   │   ├── MessagingService.java       # IM gönderimi
│   │   │   │   ├── ContactService.java         # Kişi arama
│   │   │   │   ├── EventChannelService.java    # Long-polling event listener
│   │   │   │   └── UcwaLifecycleManager.java   # Auto-bootstrap, health check
│   │   │   ├── model/
│   │   │   │   ├── AutodiscoverResponse.java
│   │   │   │   ├── UcwaApplicationResource.java
│   │   │   │   ├── OAuthTokenResponse.java
│   │   │   │   ├── PresenceInfo.java
│   │   │   │   ├── SendMessageRequest.java
│   │   │   │   ├── ContactSearchResult.java
│   │   │   │   └── UcwaEvent.java
│   │   │   └── exception/
│   │   │       ├── UcwaAuthenticationException.java
│   │   │       ├── UcwaApiException.java
│   │   │       └── GlobalExceptionHandler.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       └── application-test.yml
│   └── test/
│       └── java/com/databoss/sfbucwa/
│           ├── service/
│           │   └── TokenServiceTest.java         # Unit test (MockWebServer)
│           └── integration/
│               └── UcwaBootstrapIntegrationTest.java  # Full flow integration test
└── README.md
```

---

## 4. Konfigürasyon

### 4.1 Environment Değişkenleri

`.env.example` dosyasını `.env` olarak kopyalayıp gerçek değerlerle doldurun:

```bash
cp .env.example .env
```

| Değişken | Açıklama | Örnek |
|---|---|---|
| `SFB_AUTODISCOVER_URL` | SfB Autodiscover endpoint | `https://lyncdiscover.yourdomain.com` |
| `SFB_TOKEN_URL` | ADFS/AzureAD token endpoint | `https://adfs.yourdomain.com/adfs/oauth2/token` |
| `SFB_CLIENT_ID` | OAuth2 Client ID | `a1b2c3d4-...` |
| `SFB_CLIENT_SECRET` | OAuth2 Client Secret | `your-secret` |
| `SFB_RESOURCE` | OAuth2 Resource/Audience | `https://lyncdiscover.yourdomain.com` |

### 4.2 On-Prem vs SfB Online Farkları

**On-Premises (ADFS):**
```yaml
sfb:
  ucwa:
    autodiscover-url: https://lyncdiscover.yourdomain.com
    token-url: https://adfs.yourdomain.com/adfs/oauth2/token
    on-premises: true
```

**SfB Online (Azure AD):**
```yaml
sfb:
  ucwa:
    autodiscover-url: https://webdir.online.lync.com/Autodiscover/AutodiscoverService.svc/root
    token-url: https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/token
    on-premises: false
```

### 4.3 Spring Profilleri

| Profil | Kullanım | Özellikler |
|---|---|---|
| `default` | Production | Strict TLS, INFO log |
| `dev` | Geliştirme | Self-signed cert kabul, TRACE log |
| `test` | Test | MockWebServer, localhost endpoint'ler |

---

## 5. Build & Çalıştırma

### 5.1 Gradle ile Build

```bash
# Projeyi klonla
git clone https://gitlab.data-boss.com.tr/sfb/sfb-ucwa-spring.git
cd sfb-ucwa-spring

# Build (testler dahil)
./gradlew build

# Sadece JAR oluştur (testleri atla)
./gradlew bootJar -x test

# Oluşan JAR:
ls -la build/libs/sfb-ucwa-spring.jar
```

### 5.2 Doğrudan Çalıştırma

```bash
# Environment değişkenlerini ayarla
export SFB_AUTODISCOVER_URL=https://lyncdiscover.yourdomain.com
export SFB_TOKEN_URL=https://adfs.yourdomain.com/adfs/oauth2/token
export SFB_CLIENT_ID=your-client-id
export SFB_CLIENT_SECRET=your-client-secret
export SFB_RESOURCE=https://lyncdiscover.yourdomain.com

# Dev profiliyle çalıştır
./gradlew bootRun --args='--spring.profiles.active=dev'

# VEYA JAR olarak çalıştır
java -jar build/libs/sfb-ucwa-spring.jar --spring.profiles.active=dev
```

### 5.3 Doğrulama

Uygulama başladıktan sonra:

```bash
# Health check
curl http://localhost:8443/actuator/health

# Swagger UI
open http://localhost:8443/swagger-ui.html

# Manuel bootstrap (otomatik başlamadıysa)
curl -X POST http://localhost:8443/api/v1/ucwa/bootstrap

# Bağlantı durumu
curl http://localhost:8443/api/v1/ucwa/status
```

---

## 6. Docker ile Çalıştırma

### 6.1 Docker Build

```bash
# Image oluştur
docker build -t registry.data-boss.com.tr/sfb-ucwa-spring:latest .

# Boyut kontrolü (~250MB olmalı, JRE-only)
docker images | grep sfb-ucwa
```

### 6.2 Docker Compose ile Çalıştırma

```bash
# .env dosyasını hazırla
cp .env.example .env
# .env içindeki değerleri düzenle

# Başlat
docker compose up -d

# Logları takip et
docker compose logs -f sfb-ucwa-app

# Durdur
docker compose down
```

### 6.3 Docker Run (Tek Komut)

```bash
docker run -d \
  --name sfb-ucwa \
  -p 8443:8443 \
  -e SPRING_PROFILES_ACTIVE=dev \
  -e SFB_AUTODISCOVER_URL=https://lyncdiscover.yourdomain.com \
  -e SFB_TOKEN_URL=https://adfs.yourdomain.com/adfs/oauth2/token \
  -e SFB_CLIENT_ID=your-client-id \
  -e SFB_CLIENT_SECRET=your-client-secret \
  -e SFB_RESOURCE=https://lyncdiscover.yourdomain.com \
  registry.data-boss.com.tr/sfb-ucwa-spring:latest
```

---

## 7. Kubernetes / ArgoCD Deploy

### 7.1 Namespace & Secret Oluşturma

```bash
# Namespace
kubectl create namespace sfb

# Secret (gerçek değerlerle)
kubectl create secret generic sfb-ucwa-secrets \
  --namespace=sfb \
  --from-literal=autodiscover-url='https://lyncdiscover.yourdomain.com' \
  --from-literal=token-url='https://adfs.yourdomain.com/adfs/oauth2/token' \
  --from-literal=client-id='your-client-id' \
  --from-literal=client-secret='your-client-secret' \
  --from-literal=resource='https://lyncdiscover.yourdomain.com'
```

### 7.2 Image Push & Deploy

```bash
# Image'ı registry'ye push et
docker push registry.data-boss.com.tr/sfb-ucwa-spring:latest

# Deploy
kubectl apply -f k8s/deployment.yaml

# Durumu kontrol et
kubectl -n sfb get pods
kubectl -n sfb logs -f deployment/sfb-ucwa-spring
```

### 7.3 ArgoCD ile GitOps Deploy

`k8s/deployment.yaml` dosyasını GitLab repo'nuzda tutuyorsanız:

```yaml
# ArgoCD Application manifest
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: sfb-ucwa-spring
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://gitlab.data-boss.com.tr/sfb/sfb-ucwa-spring.git
    targetRevision: main
    path: k8s
  destination:
    server: https://kubernetes.default.svc
    namespace: sfb
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

### 7.4 APISIX Ingress (Opsiyonel)

Eğer APISIX kullanıyorsanız, uygulamayı dış dünyaya açmak için:

```yaml
apiVersion: apisix.apache.org/v2
kind: ApisixRoute
metadata:
  name: sfb-ucwa-route
  namespace: sfb
spec:
  http:
    - name: sfb-ucwa
      match:
        hosts: ["sfb-api.data-boss.com.tr"]
        paths: ["/api/v1/ucwa/*"]
      backends:
        - serviceName: sfb-ucwa-spring
          servicePort: 8443
```

### 7.5 Önemli K8s Notları

- **Replicas: 1** — UCWA Application, sunucu tarafında session-bound'dır. Birden fazla replica çalıştırırsanız her biri kendi session'ını yaratır. Eğer ölçeklendirme gerekiyorsa, her pod'un kendi UCWA session'ını yönetmesi gerekir.
- **Liveness/Readiness** — `/actuator/health` üzerinden kontrol edilir.
- **Graceful Shutdown** — `@PreDestroy` ile UCWA Application otomatik temizlenir.

---

## 8. API Referansı

Uygulama çalıştıktan sonra tam interaktif dökümantasyon için:
**http://localhost:8443/swagger-ui.html**

### Özet Endpoint'ler

| Method | Endpoint | Açıklama |
|---|---|---|
| `POST` | `/api/v1/ucwa/bootstrap` | UCWA oturumu başlat |
| `DELETE` | `/api/v1/ucwa/application` | UCWA oturumu sonlandır |
| `GET` | `/api/v1/ucwa/status` | Bağlantı durumu |
| `GET` | `/api/v1/ucwa/presence/me` | Kendi presence bilgim |
| `PUT` | `/api/v1/ucwa/presence/me?availability=Online` | Presence güncelle |
| `GET` | `/api/v1/ucwa/presence/{sipUri}` | Başkasının presence'ı |
| `POST` | `/api/v1/ucwa/message` | IM mesaj gönder |
| `GET` | `/api/v1/ucwa/contacts/search?query=ahmet&limit=5` | Kişi ara |

### Örnek: Mesaj Gönderme

```bash
curl -X POST http://localhost:8443/api/v1/ucwa/message \
  -H "Content-Type: application/json" \
  -d '{
    "toSipUri": "sip:ahmet.yilmaz@yourdomain.com",
    "message": "Merhaba, test mesajıdır.",
    "subject": "UCWA Test"
  }'
```

---

## 9. Test Stratejisi

### 9.1 Unit Testler

MockWebServer kullanılarak SfB Server'a bağımlılık olmadan test edilir:

```bash
# Tüm testleri çalıştır
./gradlew test

# Sadece unit testler
./gradlew test --tests "com.databoss.sfbucwa.service.*"

# Belirli bir test
./gradlew test --tests "com.databoss.sfbucwa.service.TokenServiceTest"
```

**Test kapsamı:**
- `TokenServiceTest` — Token alma, cache, invalidate, hata senaryoları

### 9.2 Entegrasyon Testleri

Tüm UCWA bootstrap akışı MockWebServer dispatcher ile simüle edilir:

```bash
./gradlew test --tests "com.databoss.sfbucwa.integration.*"
```

**Test kapsamı:**
- `UcwaBootstrapIntegrationTest` — Autodiscover → User Resource → Application oluşturma → Status kontrolü

### 9.3 Gerçek SfB Server ile Manuel Test

```bash
# 1. Dev profiliyle başlat
./gradlew bootRun --args='--spring.profiles.active=dev'

# 2. Bootstrap
curl -X POST http://localhost:8443/api/v1/ucwa/bootstrap
# Beklenen: {"status":"OK","user":"Servis Hesabi","message":"UCWA application basariyla olusturuldu"}

# 3. Presence kontrol
curl http://localhost:8443/api/v1/ucwa/presence/me
# Beklenen: {"availability":"Online","activity":"Available"}

# 4. Kişi ara
curl "http://localhost:8443/api/v1/ucwa/contacts/search?query=ahmet&limit=5"

# 5. Mesaj gönder
curl -X POST http://localhost:8443/api/v1/ucwa/message \
  -H "Content-Type: application/json" \
  -d '{"toSipUri":"sip:test@yourdomain.com","message":"Test mesaji"}'

# 6. Temizle
curl -X DELETE http://localhost:8443/api/v1/ucwa/application
```

### 9.4 Test Raporları

```bash
# HTML test raporu
open build/reports/tests/test/index.html
```

---

## 10. UCWA Bootstrap Akışı

Uygulamanın SfB ile konuşmaya başlamadan önce geçtiği adımlar:

```
1. GET  https://lyncdiscover.domain.com
   ← 200  { "_links": { "user": { "href": "/ucwa/oauth/user" } } }

2. GET  /ucwa/oauth/user  (Authorization: Bearer <token>)
   ← 200  { "_links": { "applications": { "href": "/ucwa/v1/applications" } } }
   NOT: İlk istek 401 dönebilir — WWW-Authenticate header'ından OAuth resource çıkarılır

3. POST /ucwa/v1/applications
   Body: { "userAgent": "SfbUcwaSpringApp", "endpointId": "<uuid>", "culture": "tr-TR" }
   ← 201  { "_embedded": { "me": {...}, "people": {...}, "communication": {...} },
            "_links": { "events": { "href": "/ucwa/v1/applications/123/events" } } }

4. GET  /ucwa/v1/applications/123/events  (long-polling, tekrarlı)
   ← 200  { "sender": [{ "events": [{ "type": "updated", "rel": "presence", ... }] }],
            "_links": { "next": { "href": "/ucwa/v1/applications/123/events?ack=2" } } }
```

**Önemli:** UCWA Application yaklaşık 30 dakika sonra expire olur. `UcwaLifecycleManager` bunu otomatik kontrol eder ve gerektiğinde yeniden bootstrap yapar.

---

## 11. SfB Server Tarafı Gereksinimler

### 11.1 On-Premises: Trusted Application Kaydı

SfB Management Shell'de (sunucu üzerinde):

```powershell
# 1. Trusted Application Pool oluştur
New-CsTrustedApplicationPool -Identity sfb-ucwa-pool.yourdomain.com `
  -Registrar registrar.yourdomain.com `
  -Site "Site:YourSite" `
  -TreatAsAuthenticated $true `
  -ThrottleAsServer $true `
  -RequiresReplication $false

# 2. Trusted Application kaydet
New-CsTrustedApplication -ApplicationId "sfb-ucwa-spring" `
  -TrustedApplicationPoolFqdn sfb-ucwa-pool.yourdomain.com `
  -Port 8443

# 3. Topology yayınla
Enable-CsTopology

# 4. ADFS'de Application Group oluştur (OAuth2)
# ADFS Management Console → Application Groups → Add...
# Server Application: Client ID ve Secret al
# Web API: Identifier olarak https://lyncdiscover.yourdomain.com ekle
```

### 11.2 SfB Online: Azure AD App Registration

```
1. Azure Portal → Azure Active Directory → App registrations → New registration
   Name: sfb-ucwa-spring
   Redirect URI: https://localhost:8443/callback

2. API permissions → Add permission → Skype for Business
   - User.ReadWrite
   - Contacts.ReadWrite

3. Certificates & secrets → New client secret → Değeri kopyala

4. Tenant admin'den "Grant admin consent" iste
```

### 11.3 DNS & Sertifika

```bash
# Autodiscover DNS kaydı kontrol
nslookup lyncdiscover.yourdomain.com

# Sertifika kontrol
openssl s_client -connect lyncdiscover.yourdomain.com:443 -servername lyncdiscover.yourdomain.com
```

---

## 12. Troubleshooting

| Hata | Olası Neden | Çözüm |
|---|---|---|
| `401 Unauthorized` token alırken | Client ID/Secret yanlış | ADFS/AzureAD ayarlarını kontrol et |
| `403 Forbidden` application oluştururken | Trusted Application kaydı yok | SfB Shell'de kayıt yap (Bölüm 11.1) |
| `404 Not Found` autodiscover'da | DNS yanlış veya UCWA devre dışı | `lyncdiscover` DNS kaydını kontrol et |
| `502 Bad Gateway` | SfB Front-End servisi çalışmıyor | SfB servislerini kontrol et |
| `SSL handshake failure` | Self-signed cert | Dev'de `trust-all-certificates: true` yap |
| Event channel `410 Gone` | Application expire olmuş | Otomatik re-bootstrap devrede (30dk) |
| `Connection refused` | Firewall | 443/TCP açık mı kontrol et |
| Token sürekli expire | Saat farkı | NTP senkronizasyonunu kontrol et |

### Log Seviyesi Artırma

```bash
# Çalışırken log seviyesi değiştir (Actuator)
curl -X POST http://localhost:8443/actuator/loggers/com.databoss.sfbucwa \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "TRACE"}'
```

---

## 13. Güvenlik Notları

1. **Secret'ları asla koda gömmeyin** — Environment variable veya K8s Secret kullanın
2. **`trust-all-certificates: true`** sadece dev/test ortamında kullanın, prod'da kesinlikle `false`
3. **HTTPS** — Prod'da mutlaka SSL/TLS aktif edin (`server.ssl.enabled: true`)
4. **Rate Limiting** — UCWA, application başına istek limiti uygular; fazla agresif polling yapmayın
5. **Token Güvenliği** — Token'lar memory'de tutulur, disk'e yazılmaz
6. **Replicas** — Birden fazla pod çalıştırıyorsanız, her biri bağımsız UCWA session açar; SfB Server'ın Trusted Application limitlerini aşmamaya dikkat edin
7. **Jasypt Entegrasyonu** — Hassas property'ler için `jasypt-spring-boot-starter` entegre edilebilir (mevcut altyapınızla uyumlu)

---

## Lisans

Internal use — Data Boss
