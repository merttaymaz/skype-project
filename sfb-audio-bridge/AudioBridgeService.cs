using System;
using System.Configuration;
using System.Net.WebSockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Rtc.Collaboration;
using Microsoft.Rtc.Signaling;
using NLog;

namespace SfbAudioBridge
{
    /// <summary>
    /// UCMA platform yasam dongusu yoneticisi.
    ///
    /// Baslangicta:
    ///   1. SSL sertifikayi bulur (LocalMachine\My store)
    ///   2. CollaborationPlatform olusturur (ServerPlatformSettings)
    ///   3. ApplicationEndpoint kurar
    ///   4. Gelen conference davetlerini dinlemeye baslar
    ///
    /// Her conference icin ayri bir ConferenceAudioHandler olusturur.
    /// </summary>
    public class AudioBridgeService
    {
        private static readonly Logger Log = LogManager.GetCurrentClassLogger();

        private CollaborationPlatform _platform;
        private ApplicationEndpoint _endpoint;
        private readonly CancellationTokenSource _cts = new CancellationTokenSource();

        // Config
        private readonly string _appId;
        private readonly string _trustedPoolFqdn;
        private readonly int _trustedPort;
        private readonly string _endpointGruu;
        private readonly string _registrarFqdn;
        private readonly int _registrarPort;
        private readonly string _certSubject;
        private readonly string _springWsUrl;

        public AudioBridgeService()
        {
            _appId           = Config("Sfb.ApplicationId");
            _trustedPoolFqdn = Config("Sfb.TrustedPoolFqdn");
            _trustedPort     = int.Parse(Config("Sfb.TrustedPort", "8443"));
            _endpointGruu    = Config("Sfb.EndpointGruu");
            _registrarFqdn   = Config("Sfb.RegistrarFqdn");
            _registrarPort   = int.Parse(Config("Sfb.RegistrarPort", "5061"));
            _certSubject     = Config("Sfb.CertificateSubject");
            _springWsUrl     = Config("Spring.WebSocketUrl");
        }

        public void Start()
        {
            Log.Info("═══════════════════════════════════════");
            Log.Info("  SfB Audio Bridge baslatiliyor...");
            Log.Info("  App ID:    {0}", _appId);
            Log.Info("  Pool FQDN: {0}", _trustedPoolFqdn);
            Log.Info("  Registrar: {0}:{1}", _registrarFqdn, _registrarPort);
            Log.Info("  Spring WS: {0}", _springWsUrl);
            Log.Info("═══════════════════════════════════════");

            try
            {
                InitializePlatform();
                EstablishEndpoint();
                RegisterEventHandlers();
                Log.Info("SfB Audio Bridge hazir — conference bekleniyor...");
            }
            catch (Exception ex)
            {
                Log.Fatal(ex, "Baslangic hatasi");
                throw;
            }
        }

        public void Stop()
        {
            Log.Info("SfB Audio Bridge durduruluyor...");
            _cts.Cancel();

            try
            {
                if (_endpoint != null)
                {
                    _endpoint.BeginTerminate(ar =>
                    {
                        try { _endpoint.EndTerminate(ar); }
                        catch (Exception ex) { Log.Warn(ex, "Endpoint terminate hatasi"); }
                    }, null);
                }

                if (_platform != null)
                {
                    _platform.BeginShutdown(ar =>
                    {
                        try { _platform.EndShutdown(ar); }
                        catch (Exception ex) { Log.Warn(ex, "Platform shutdown hatasi"); }
                    }, null);
                }
            }
            catch (Exception ex)
            {
                Log.Warn(ex, "Temizlik hatasi");
            }

            Log.Info("SfB Audio Bridge durduruldu.");
        }

        // ─── UCMA Platform Kurulumu ───

        /// <summary>
        /// CollaborationPlatform olusturur.
        /// ServerPlatformSettings: Trusted Application modunda calisir.
        /// </summary>
        private void InitializePlatform()
        {
            Log.Info("SSL sertifika araniyor: Subject={0}", _certSubject);
            var cert = FindCertificate(_certSubject);
            if (cert == null)
                throw new InvalidOperationException(
                    $"Sertifika bulunamadi: '{_certSubject}' — " +
                    "LocalMachine\\My store'da Subject ile eslesecek bir sertifika olmali. " +
                    "Kurulum bolumune bak.");

            Log.Info("Sertifika bulundu: {0} (Expires: {1})", cert.Subject, cert.NotAfter);

            var settings = new ServerPlatformSettings(_appId, _registrarFqdn, _registrarPort, _trustedPoolFqdn, _trustedPort)
            {
                // OutboundConnectionConfiguration icin sertifika
                // TrustedDomains = null ise tum SfB domain'lerine guvenilir
            };
            settings.SetCertificate(cert);

            _platform = new CollaborationPlatform(settings);

            var platformReady = new ManualResetEvent(false);
            _platform.BeginStartup(ar =>
            {
                try
                {
                    _platform.EndStartup(ar);
                    Log.Info("CollaborationPlatform baslatildi");
                }
                catch (Exception ex)
                {
                    Log.Fatal(ex, "Platform baslatilamadi");
                    throw;
                }
                finally
                {
                    platformReady.Set();
                }
            }, null);

            if (!platformReady.WaitOne(TimeSpan.FromSeconds(30)))
                throw new TimeoutException("Platform baslangic timeout (30s)");
        }

        /// <summary>
        /// ApplicationEndpoint olusturur ve SfB Registrar'a kaydeder.
        /// </summary>
        private void EstablishEndpoint()
        {
            var endpointSettings = new ApplicationEndpointSettings(_endpointGruu)
            {
                AutomaticPresencePublicationEnabled = true,
                Presence = new PresenceState(PresenceStateType.AggregateState, PresenceAvailability.IdleBusy, new PresenceActivity())
            };

            _endpoint = new ApplicationEndpoint(_platform, endpointSettings);

            var endpointReady = new ManualResetEvent(false);
            _endpoint.BeginEstablish(ar =>
            {
                try
                {
                    _endpoint.EndEstablish(ar);
                    Log.Info("ApplicationEndpoint kuruldu: {0}", _endpointGruu);
                }
                catch (Exception ex)
                {
                    Log.Fatal(ex, "Endpoint kurulamadi — GRUU dogru mu? Trusted App kaydi var mi?");
                    throw;
                }
                finally
                {
                    endpointReady.Set();
                }
            }, null);

            if (!endpointReady.WaitOne(TimeSpan.FromSeconds(30)))
                throw new TimeoutException("Endpoint kurulum timeout (30s)");
        }

        // ─── Event Handlers ───

        /// <summary>
        /// Gelen AV invitation'lari ve conference katilim isteklerini dinler.
        /// </summary>
        private void RegisterEventHandlers()
        {
            // Gelen AudioVideo cagrisi (biri bot'u conference'a davet ettiginde)
            _endpoint.RegisterForIncomingCall<AudioVideoCall>(OnIncomingAvCall);

            Log.Info("Incoming AV call handler kayitlandi");
        }

        /// <summary>
        /// Gelen AV call'i kabul eder ve audio streaming baslatir.
        /// </summary>
        private void OnIncomingAvCall(object sender, CallReceivedEventArgs<AudioVideoCall> e)
        {
            var call = e.Call;
            var conversation = call.Conversation;
            string remoteUri = e.RemoteParticipant?.Uri ?? "unknown";

            Log.Info("Gelen AV call: Remote={0}, ConvId={1}", remoteUri, conversation.Id);

            // Her conference icin ayri handler olustur
            var handler = new ConferenceAudioHandler(
                call, conversation, _springWsUrl, _cts.Token);

            // Call'i kabul et ve audio yakalamaya basla
            call.BeginAccept(ar =>
            {
                try
                {
                    call.EndAccept(ar);
                    Log.Info("AV call kabul edildi: {0}", remoteUri);
                    handler.StartStreaming();
                }
                catch (Exception ex)
                {
                    Log.Error(ex, "AV call kabul hatasi: {0}", remoteUri);
                }
            }, null);
        }

        /// <summary>
        /// Belirli bir conference URI'sine programatik olarak katilir.
        /// REST API veya komut satiri uzerinden tetiklenebilir.
        /// </summary>
        public void JoinConference(string conferenceUri)
        {
            Log.Info("Conference'a katiliniyor: {0}", conferenceUri);

            var conversation = new Conversation(_endpoint);
            var avCall = new AudioVideoCall(conversation);

            var joinOptions = new ConferenceJoinOptions
            {
                JoinMode = JoinMode.TrustedParticipant
            };

            conversation.ConferenceSession.BeginJoin(conferenceUri, joinOptions, ar =>
            {
                try
                {
                    conversation.ConferenceSession.EndJoin(ar);
                    Log.Info("Conference'a katilindi: {0}", conferenceUri);

                    // AV call kur
                    avCall.BeginEstablish(ar2 =>
                    {
                        try
                        {
                            avCall.EndEstablish(ar2);
                            var handler = new ConferenceAudioHandler(
                                avCall, conversation, _springWsUrl, _cts.Token);
                            handler.StartStreaming();
                        }
                        catch (Exception ex)
                        {
                            Log.Error(ex, "AV call kurulum hatasi");
                        }
                    }, null);
                }
                catch (Exception ex)
                {
                    Log.Error(ex, "Conference join hatasi: {0}", conferenceUri);
                }
            }, null);
        }

        // ─── Helpers ───

        private static X509Certificate2 FindCertificate(string subjectName)
        {
            using (var store = new X509Store(StoreName.My, StoreLocation.LocalMachine))
            {
                store.Open(OpenFlags.ReadOnly);
                var certs = store.Certificates.Find(
                    X509FindType.FindBySubjectName, subjectName, validOnly: true);
                return certs.Count > 0 ? certs[0] : null;
            }
        }

        private static string Config(string key, string defaultValue = null)
        {
            return ConfigurationManager.AppSettings[key]
                ?? Environment.GetEnvironmentVariable(key.Replace(".", "_").ToUpper())
                ?? defaultValue
                ?? throw new InvalidOperationException($"Config eksik: {key}");
        }
    }
}
