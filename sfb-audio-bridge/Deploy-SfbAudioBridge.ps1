<#
.SYNOPSIS
    SfB Audio Bridge kurulum ve deploy scripti.

.DESCRIPTION
    Bu script asagidaki islemleri yapar:
    1. On-kontroller (UCMA SDK, .NET 4.8, sertifika)
    2. SfB Topology'ye Trusted Application kaydi
    3. Uygulamayi hedef dizine kopyalar
    4. Windows Service olarak kurar

.PARAMETER Action
    install   — Tam kurulum (topology + service)
    uninstall — Service ve dosyalari kaldir
    status    — Mevcut durumu goster
    topology  — Sadece SfB Topology kaydi yap

.PARAMETER InstallDir
    Kurulum dizini. Default: C:\SfbAudioBridge

.PARAMETER PoolFqdn
    Trusted Application Pool FQDN.

.PARAMETER Registrar
    SfB Front-End (Registrar) FQDN.

.PARAMETER SiteName
    SfB Site adi.

.PARAMETER CertSubject
    SSL sertifika subject name.

.EXAMPLE
    # Tam kurulum
    .\Deploy-SfbAudioBridge.ps1 -Action install `
        -PoolFqdn "sfb-audio-pool.yourdomain.com" `
        -Registrar "sfbfe01.yourdomain.com" `
        -SiteName "Istanbul" `
        -CertSubject "sfb-audio-pool.yourdomain.com"

    # Durum kontrol
    .\Deploy-SfbAudioBridge.ps1 -Action status

    # Kaldirma
    .\Deploy-SfbAudioBridge.ps1 -Action uninstall
#>

param(
    [ValidateSet("install", "uninstall", "status", "topology")]
    [string]$Action = "status",

    [string]$InstallDir = "C:\SfbAudioBridge",
    [string]$PoolFqdn = "sfb-audio-pool.yourdomain.com",
    [string]$Registrar = "sfbfe01.yourdomain.com",
    [string]$SiteName = "Istanbul",
    [string]$CertSubject = "sfb-audio-pool.yourdomain.com",
    [string]$AppId = "sfb-audio-bridge",
    [int]$AppPort = 8443,
    [string]$BuildDir = ".\bin\Release"
)

$ErrorActionPreference = "Stop"
$ServiceName = "SfbAudioBridge"

# ═══════════════════════════════════════════════════════════
#  ON-KONTROLLER
# ═══════════════════════════════════════════════════════════

function Test-Prerequisites {
    Write-Host "`n[1/6] On-kontroller yapiliyor..." -ForegroundColor Cyan

    # .NET Framework 4.8
    $dotnet = Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full" -ErrorAction SilentlyContinue
    if ($dotnet.Release -lt 528040) {
        throw ".NET Framework 4.8 gerekli. Mevcut: $($dotnet.Release). Indir: https://dotnet.microsoft.com/download/dotnet-framework/net48"
    }
    Write-Host "  [OK] .NET Framework 4.8 ($($dotnet.Release))" -ForegroundColor Green

    # UCMA SDK
    $ucmaPath = "C:\Program Files\Microsoft UCMA 5.0\SDK\Core\Bin"
    if (-not (Test-Path $ucmaPath)) {
        $ucmaPath = "C:\Program Files\Microsoft UCMA 5.0\Runtime"
        if (-not (Test-Path $ucmaPath)) {
            throw @"
UCMA 5.0 SDK bulunamadi.

Kurulum:
  1. SfB Server 2019 ISO'yu mount et
  2. Setup\amd64\UcmaSdk.msi calistir
     VEYA
  3. SfB 2015 SDK: https://www.microsoft.com/en-us/download/details.aspx?id=47345

Kurulumdan sonra bu scripti tekrar calistir.
"@
        }
    }
    Write-Host "  [OK] UCMA 5.0 SDK ($ucmaPath)" -ForegroundColor Green

    # SSL Sertifika
    $cert = Get-ChildItem Cert:\LocalMachine\My |
            Where-Object { $_.Subject -like "*$CertSubject*" -and $_.NotAfter -gt (Get-Date) } |
            Select-Object -First 1
    if (-not $cert) {
        throw @"
SSL sertifika bulunamadi: Subject='$CertSubject'
LocalMachine\My store'da gecerli bir sertifika olmali.

Sertifika olusturmak icin:
  # Varolan SfB sertifikasini kullan:
  Get-CsCertificate | fl

  # Veya yeni bir sertifika iste:
  Request-CsCertificate -New -Type Default -FriendlyName "SfB Audio Bridge" `
    -DomainName "$PoolFqdn" -CA "dc01.yourdomain.com\YourDomain-CA"
"@
    }
    Write-Host "  [OK] SSL Sertifika: $($cert.Subject) (Expires: $($cert.NotAfter))" -ForegroundColor Green

    # SfB Management Shell
    try {
        Import-Module "C:\Program Files\Common Files\Skype for Business Server 2019\Modules\SkypeForBusiness\SkypeForBusiness.psd1" -ErrorAction SilentlyContinue
        # SfB 2015 icin:
        # Import-Module Lync
    }
    catch {
        Write-Host "  [WARN] SfB Management Shell yuklenemedi — topology komutlari calismaYabilir" -ForegroundColor Yellow
    }
    Write-Host "  [OK] SfB Management Shell" -ForegroundColor Green

    # Build output
    if (-not (Test-Path "$BuildDir\SfbAudioBridge.exe")) {
        throw "Build output bulunamadi: $BuildDir\SfbAudioBridge.exe — Once 'msbuild /p:Configuration=Release' calistir"
    }
    Write-Host "  [OK] Build output: $BuildDir\SfbAudioBridge.exe" -ForegroundColor Green
}

# ═══════════════════════════════════════════════════════════
#  SFB TOPOLOGY KAYDI
# ═══════════════════════════════════════════════════════════

function Register-TrustedApplication {
    Write-Host "`n[2/6] SfB Topology'ye Trusted Application kaydediliyor..." -ForegroundColor Cyan

    # Trusted Application Pool kontrol
    $existingPool = Get-CsTrustedApplicationPool -Identity $PoolFqdn -ErrorAction SilentlyContinue
    if ($existingPool) {
        Write-Host "  [SKIP] Pool zaten mevcut: $PoolFqdn" -ForegroundColor Yellow
    }
    else {
        Write-Host "  Trusted Application Pool olusturuluyor: $PoolFqdn"
        New-CsTrustedApplicationPool `
            -Identity $PoolFqdn `
            -Registrar "Registrar:$Registrar" `
            -Site "Site:$SiteName" `
            -TreatAsAuthenticated $true `
            -ThrottleAsServer $true `
            -RequiresReplication $false
        Write-Host "  [OK] Pool olusturuldu" -ForegroundColor Green
    }

    # Trusted Application kontrol
    $existingApp = Get-CsTrustedApplication -Identity "$PoolFqdn/$AppId" -ErrorAction SilentlyContinue
    if ($existingApp) {
        Write-Host "  [SKIP] Application zaten kayitli: $AppId" -ForegroundColor Yellow
    }
    else {
        Write-Host "  Trusted Application kaydediliyor: $AppId (Port: $AppPort)"
        New-CsTrustedApplication `
            -ApplicationId $AppId `
            -TrustedApplicationPoolFqdn $PoolFqdn `
            -Port $AppPort
        Write-Host "  [OK] Application kaydedildi" -ForegroundColor Green
    }

    # Topology yayinla
    Write-Host "  Topology yayinlaniyor..."
    Enable-CsTopology
    Write-Host "  [OK] Topology guncellendi" -ForegroundColor Green

    # Endpoint olustur (GRUU)
    $existingEndpoint = Get-CsTrustedApplicationEndpoint -Identity "sip:$AppId@$($PoolFqdn.Split('.')[1..$($PoolFqdn.Split('.').Length)] -join '.')" -ErrorAction SilentlyContinue
    if (-not $existingEndpoint) {
        Write-Host "  Application Endpoint olusturuluyor..."
        $sipDomain = $PoolFqdn.Split('.')[1..($PoolFqdn.Split('.').Length)] -join '.'
        New-CsTrustedApplicationEndpoint `
            -ApplicationId $AppId `
            -TrustedApplicationPoolFqdn $PoolFqdn `
            -SipAddress "sip:$AppId@$sipDomain" `
            -DisplayName "SfB Audio Bridge"
        Write-Host "  [OK] Endpoint olusturuldu" -ForegroundColor Green
    }

    # Sonuc bilgisi
    Write-Host "`n  ─── Kayitli Bilgiler ───" -ForegroundColor Cyan
    $app = Get-CsTrustedApplication -Identity "$PoolFqdn/$AppId"
    $endpoint = Get-CsTrustedApplicationEndpoint | Where-Object { $_.DisplayName -eq "SfB Audio Bridge" }

    Write-Host "  Pool FQDN:    $PoolFqdn"
    Write-Host "  App ID:       $AppId"
    Write-Host "  Port:         $AppPort"
    if ($endpoint) {
        Write-Host "  Endpoint URI: $($endpoint.SipAddress)"
        Write-Host ""
        Write-Host "  *** App.config'e bu degerleri yaz: ***" -ForegroundColor Yellow
        Write-Host "  Sfb.EndpointGruu = $($endpoint.OwnerUrn)" -ForegroundColor Yellow
    }
}

# ═══════════════════════════════════════════════════════════
#  DOSYA KOPYALAMA
# ═══════════════════════════════════════════════════════════

function Copy-ApplicationFiles {
    Write-Host "`n[3/6] Uygulama dosyalari kopyalaniyor..." -ForegroundColor Cyan

    if (-not (Test-Path $InstallDir)) {
        New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
        New-Item -ItemType Directory -Path "$InstallDir\logs" -Force | Out-Null
    }

    # Mevcut service duruyorsa durdur
    $svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($svc -and $svc.Status -eq "Running") {
        Write-Host "  Service durduruluyor..."
        Stop-Service $ServiceName -Force
        Start-Sleep -Seconds 3
    }

    # Dosyalari kopyala
    Copy-Item "$BuildDir\*" -Destination $InstallDir -Recurse -Force
    Write-Host "  [OK] Dosyalar kopyalandi: $InstallDir" -ForegroundColor Green

    # Dosya listesi
    $files = Get-ChildItem $InstallDir -File | Select-Object Name, Length
    foreach ($f in $files) {
        Write-Host "    $($f.Name) ($([math]::Round($f.Length/1KB, 1)) KB)"
    }
}

# ═══════════════════════════════════════════════════════════
#  WINDOWS SERVICE KURULUMU
# ═══════════════════════════════════════════════════════════

function Install-WindowsService {
    Write-Host "`n[4/6] Windows Service kuruluyor..." -ForegroundColor Cyan

    $exePath = "$InstallDir\SfbAudioBridge.exe"

    # TopShelf ile kur
    & $exePath install
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  TopShelf install basarisiz, sc.exe ile deneniyor..." -ForegroundColor Yellow
        sc.exe create $ServiceName binPath= "`"$exePath`"" start= auto
        sc.exe description $ServiceName "SfB meeting audio stream to Spring Boot STT"
        sc.exe failure $ServiceName reset= 86400 actions= restart/60000/restart/300000/restart/900000
    }

    Write-Host "  [OK] Service kuruldu: $ServiceName" -ForegroundColor Green
}

function Start-BridgeService {
    Write-Host "`n[5/6] Service baslatiliyor..." -ForegroundColor Cyan

    Start-Service $ServiceName
    Start-Sleep -Seconds 5

    $svc = Get-Service $ServiceName
    if ($svc.Status -eq "Running") {
        Write-Host "  [OK] Service calisiyor" -ForegroundColor Green
    }
    else {
        Write-Host "  [FAIL] Service baslatilamadi. Log kontrol edin: $InstallDir\logs\" -ForegroundColor Red
    }
}

function Show-PostInstallInfo {
    Write-Host "`n[6/6] Kurulum tamamlandi!" -ForegroundColor Green
    Write-Host @"

═══════════════════════════════════════════════════════════
  DEPLOY OZETI
═══════════════════════════════════════════════════════════

  Kurulum dizini:  $InstallDir
  Service adi:     $ServiceName
  Config dosyasi:  $InstallDir\SfbAudioBridge.exe.config

  Log dosyasi:     $InstallDir\logs\sfb-audio-bridge.log

═══════════════════════════════════════════════════════════
  SONRAKI ADIMLAR
═══════════════════════════════════════════════════════════

  1. App.config degerlerini kontrol et:
     notepad "$InstallDir\SfbAudioBridge.exe.config"

  2. Spring WebSocket URL'sini ayarla:
     Spring.WebSocketUrl = ws://spring-server:8480/ws/audio-stream

  3. Service'i yeniden baslat:
     Restart-Service $ServiceName

  4. Loglari takip et:
     Get-Content "$InstallDir\logs\sfb-audio-bridge.log" -Wait

  5. Test icin bir conference baslatip bot'u davet et veya:
     # SfB Management Shell'den:
     # Bot otomatik olarak gelen AV call'lari kabul eder

═══════════════════════════════════════════════════════════
"@
}

# ═══════════════════════════════════════════════════════════
#  UNINSTALL
# ═══════════════════════════════════════════════════════════

function Uninstall-Bridge {
    Write-Host "`nKaldirma islemi basliyor..." -ForegroundColor Yellow

    $svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($svc) {
        if ($svc.Status -eq "Running") {
            Stop-Service $ServiceName -Force
            Start-Sleep -Seconds 3
        }
        & "$InstallDir\SfbAudioBridge.exe" uninstall 2>$null
        sc.exe delete $ServiceName 2>$null
        Write-Host "  [OK] Service kaldirildi" -ForegroundColor Green
    }

    if (Test-Path $InstallDir) {
        Remove-Item $InstallDir -Recurse -Force
        Write-Host "  [OK] Dosyalar silindi: $InstallDir" -ForegroundColor Green
    }

    Write-Host "`n  NOT: SfB Topology kaydi kaldirilmadi." -ForegroundColor Yellow
    Write-Host "  Kaldirmak icin SfB Management Shell'de:" -ForegroundColor Yellow
    Write-Host "    Remove-CsTrustedApplication -Identity `"$PoolFqdn/$AppId`""
    Write-Host "    Remove-CsTrustedApplicationPool -Identity `"$PoolFqdn`""
    Write-Host "    Enable-CsTopology"
}

# ═══════════════════════════════════════════════════════════
#  STATUS
# ═══════════════════════════════════════════════════════════

function Show-Status {
    Write-Host "`n═══ SfB Audio Bridge Durumu ═══" -ForegroundColor Cyan

    # Service
    $svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($svc) {
        $color = if ($svc.Status -eq "Running") { "Green" } else { "Red" }
        Write-Host "  Service:     $($svc.Status)" -ForegroundColor $color
    }
    else {
        Write-Host "  Service:     KURULU DEGIL" -ForegroundColor Yellow
    }

    # Dosyalar
    if (Test-Path "$InstallDir\SfbAudioBridge.exe") {
        $ver = (Get-Item "$InstallDir\SfbAudioBridge.exe").VersionInfo.FileVersion
        Write-Host "  Install Dir: $InstallDir (v$ver)"
    }

    # Topology
    try {
        $app = Get-CsTrustedApplication -Identity "$PoolFqdn/$AppId" -ErrorAction SilentlyContinue
        if ($app) {
            Write-Host "  Topology:    KAYITLI ($PoolFqdn)" -ForegroundColor Green
        }
        else {
            Write-Host "  Topology:    KAYITLI DEGIL" -ForegroundColor Yellow
        }
    }
    catch {
        Write-Host "  Topology:    Kontrol edilemedi (SfB Shell yuklu mu?)" -ForegroundColor Yellow
    }

    # Son log
    $logFile = "$InstallDir\logs\sfb-audio-bridge.log"
    if (Test-Path $logFile) {
        Write-Host "`n  Son 5 log satiri:"
        Get-Content $logFile -Tail 5 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    }
}

# ═══════════════════════════════════════════════════════════
#  ANA AKIS
# ═══════════════════════════════════════════════════════════

switch ($Action) {
    "install" {
        Test-Prerequisites
        Register-TrustedApplication
        Copy-ApplicationFiles
        Install-WindowsService
        Start-BridgeService
        Show-PostInstallInfo
    }
    "uninstall" {
        Uninstall-Bridge
    }
    "status" {
        Show-Status
    }
    "topology" {
        Register-TrustedApplication
    }
}
