using System;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Rtc.Collaboration;
using Microsoft.Rtc.Collaboration.AudioVideo;
using Microsoft.Rtc.Signaling;
using Newtonsoft.Json;

namespace SfbAudioBridge
{
    /// <summary>
    /// SfB UCMA Audio Bridge
    /// 
    /// Conference'a Trusted Application olarak katilir,
    /// AudioVideoFlow'dan PCM buffer'lari yakalar ve
    /// WebSocket uzerinden Spring uygulamasina akar.
    /// 
    /// Her 20ms'de bir ~640 byte PCM chunk gelir (16kHz, 16bit, mono).
    /// </summary>
    class Program
    {
        // ─── Konfigurasyon ───
        static readonly string SpringWsUrl = Environment.GetEnvironmentVariable("SPRING_WS_URL")
            ?? "ws://localhost:8480/ws/audio-stream";
        static readonly string SfbAppId = Environment.GetEnvironmentVariable("SFB_APP_ID")
            ?? "sfb-audio-bridge";
        static readonly string SfbTrustedAppFqdn = Environment.GetEnvironmentVariable("SFB_TRUSTED_FQDN")
            ?? "sfb-ucwa-pool.yourdomain.com";
        static readonly int SfbTrustedAppPort = int.Parse(
            Environment.GetEnvironmentVariable("SFB_TRUSTED_PORT") ?? "8443");
        static readonly string SfbGruu = Environment.GetEnvironmentVariable("SFB_GRUU")
            ?? "sip:sfb-audio-bridge@yourdomain.com;gruu;opaque=srvr:sfb-audio-bridge:AAAAAA";

        static ApplicationEndpoint _appEndpoint;
        static ClientWebSocket _ws;
        static CancellationTokenSource _cts = new CancellationTokenSource();

        static async Task Main(string[] args)
        {
            if (args.Length < 1)
            {
                Console.WriteLine("Kullanim: SfbAudioBridge.exe <conference-uri>");
                Console.WriteLine("Ornek:    SfbAudioBridge.exe sip:user@domain.com;gruu;opaque=app:conf:...");
                return;
            }

            string conferenceUri = args[0];
            Console.WriteLine($"[BRIDGE] Conference URI: {conferenceUri}");
            Console.WriteLine($"[BRIDGE] Spring WS: {SpringWsUrl}");

            try
            {
                // 1. UCMA platform & endpoint baslat
                InitializePlatform();

                // 2. WebSocket baglan
                await ConnectWebSocket(conferenceUri);

                // 3. Conference'a katil ve audio aktar
                await JoinConferenceAndStream(conferenceUri);

                Console.WriteLine("[BRIDGE] Ctrl+C ile durdurun...");
                Console.CancelKeyPress += (s, e) => { e.Cancel = true; _cts.Cancel(); };
                _cts.Token.WaitHandle.WaitOne();
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[BRIDGE] HATA: {ex.Message}");
                Console.WriteLine(ex.StackTrace);
            }
            finally
            {
                await Cleanup();
            }
        }

        /// <summary>
        /// UCMA CollaborationPlatform ve ApplicationEndpoint olusturur.
        /// Trusted Application olarak SfB Server'a baglanir.
        /// </summary>
        static void InitializePlatform()
        {
            Console.WriteLine("[BRIDGE] UCMA platform baslatiliyor...");

            var settings = new ProvisionedApplicationPlatformSettings(SfbAppId, SfbTrustedAppFqdn);
            var platform = new CollaborationPlatform(settings);

            platform.BeginStartup(ar =>
            {
                platform.EndStartup(ar);
                Console.WriteLine("[BRIDGE] Platform baslatildi");
            }, null);

            // Endpoint
            var endpointSettings = new ApplicationEndpointSettings(SfbGruu);
            endpointSettings.AutomaticPresencePublicationEnabled = true;

            _appEndpoint = new ApplicationEndpoint(platform, endpointSettings);
            _appEndpoint.BeginEstablish(ar =>
            {
                _appEndpoint.EndEstablish(ar);
                Console.WriteLine("[BRIDGE] Endpoint kuruldu");
            }, null);

            Thread.Sleep(3000); // Endpoint kurulumunu bekle
        }

        /// <summary>
        /// Spring Boot WebSocket server'a baglanir.
        /// Baglanti boyunca audio chunk'lari bu kanal uzerinden akar.
        /// </summary>
        static async Task ConnectWebSocket(string conferenceUri)
        {
            _ws = new ClientWebSocket();
            _ws.Options.SetRequestHeader("X-Conference-Uri", conferenceUri);
            _ws.Options.SetRequestHeader("X-Audio-Format", "pcm-16khz-16bit-mono");

            Console.WriteLine($"[BRIDGE] WebSocket baglaniyor: {SpringWsUrl}");
            await _ws.ConnectAsync(new Uri(SpringWsUrl), _cts.Token);
            Console.WriteLine("[BRIDGE] WebSocket bagli");

            // Handshake — session bilgisi gonder
            var handshake = JsonConvert.SerializeObject(new
            {
                type = "session_start",
                conferenceUri = conferenceUri,
                audioFormat = "pcm",
                sampleRate = 16000,
                channels = 1,
                bitsPerSample = 16,
                chunkIntervalMs = 20
            });
            var handshakeBytes = Encoding.UTF8.GetBytes(handshake);
            await _ws.SendAsync(new ArraySegment<byte>(handshakeBytes),
                WebSocketMessageType.Text, true, _cts.Token);
        }

        /// <summary>
        /// Conference'a katilir, AudioVideoCall olusturur ve
        /// AudioVideoFlow uzerinden PCM buffer'lari yakalar.
        /// </summary>
        static async Task JoinConferenceAndStream(string conferenceUri)
        {
            var conversation = new Conversation(_appEndpoint);
            var avCall = new AudioVideoCall(conversation);

            // Conference'a katil
            Console.WriteLine("[BRIDGE] Conference'a katiliyor...");
            var joinOptions = new ConferenceJoinOptions
            {
                JoinMode = JoinMode.TrustedParticipant
            };
            conversation.ConferenceSession.BeginJoin(conferenceUri, joinOptions, ar =>
            {
                conversation.ConferenceSession.EndJoin(ar);
                Console.WriteLine("[BRIDGE] Conference'a katildi");
            }, null);

            Thread.Sleep(2000);

            // AudioVideo call baslat
            avCall.BeginEstablish(ar =>
            {
                avCall.EndEstablish(ar);
                Console.WriteLine("[BRIDGE] AV call kuruldu");
            }, null);

            Thread.Sleep(2000);

            // ─── Audio Flow Event Handler ───
            // Bu event her ~20ms'de bir tetiklenir ve PCM buffer icerir
            if (avCall.Flow != null)
            {
                AttachAudioSink(avCall.Flow);
            }
            else
            {
                avCall.AudioVideoFlowConfigurationRequested += (sender, e) =>
                {
                    Console.WriteLine("[BRIDGE] AudioVideoFlow hazirlandi");
                    AttachAudioSink(e.Flow);
                };
            }

            // Partial transkript almak icin WebSocket'ten oku (ayri thread)
            _ = Task.Run(() => ReceiveTranscripts(), _cts.Token);
        }

        /// <summary>
        /// AudioVideoFlow'a AudioMediaBuffer sink'i baglar.
        /// Her buffer geldiginde WebSocket uzerinden binary olarak gonderir.
        /// 
        /// Buffer format: 16kHz, 16-bit, mono PCM = 640 byte / 20ms
        /// </summary>
        static void AttachAudioSink(AudioVideoFlow flow)
        {
            Console.WriteLine("[BRIDGE] Audio sink baglaniyor...");

            // SpeechRecognitionConnector mixed audio'yu alir (tum katilimcilar)
            var connector = new SpeechRecognitionConnector();
            connector.AttachFlow(flow);

            var stream = connector.Start();
            Console.WriteLine("[BRIDGE] Audio stream aktif — PCM chunk'lar gonderiliyor");

            // Ayri thread'de stream'den oku ve WebSocket'e yaz
            Task.Run(async () =>
            {
                byte[] buffer = new byte[640]; // 20ms @ 16kHz 16bit mono
                int bytesRead;

                while (!_cts.IsCancellationRequested)
                {
                    try
                    {
                        bytesRead = stream.Read(buffer, 0, buffer.Length);
                        if (bytesRead <= 0) break;

                        if (_ws.State == WebSocketState.Open)
                        {
                            await _ws.SendAsync(
                                new ArraySegment<byte>(buffer, 0, bytesRead),
                                WebSocketMessageType.Binary,
                                true,
                                _cts.Token);
                        }
                    }
                    catch (OperationCanceledException)
                    {
                        break;
                    }
                    catch (Exception ex)
                    {
                        Console.WriteLine($"[BRIDGE] Audio gonderim hatasi: {ex.Message}");
                        await Task.Delay(100);
                    }
                }

                Console.WriteLine("[BRIDGE] Audio stream sonlandi");
                connector.Stop();
                connector.DetachFlow();
            }, _cts.Token);
        }

        /// <summary>
        /// Spring'den gelen partial transkriptleri WebSocket uzerinden okur.
        /// </summary>
        static async Task ReceiveTranscripts()
        {
            var buffer = new byte[4096];

            while (_ws.State == WebSocketState.Open && !_cts.IsCancellationRequested)
            {
                try
                {
                    var result = await _ws.ReceiveAsync(new ArraySegment<byte>(buffer), _cts.Token);
                    if (result.MessageType == WebSocketMessageType.Text)
                    {
                        string json = Encoding.UTF8.GetString(buffer, 0, result.Count);
                        Console.WriteLine($"[TRANSKRIPT] {json}");
                    }
                    else if (result.MessageType == WebSocketMessageType.Close)
                    {
                        Console.WriteLine("[BRIDGE] WebSocket kapatildi");
                        break;
                    }
                }
                catch (OperationCanceledException) { break; }
                catch (Exception ex)
                {
                    Console.WriteLine($"[BRIDGE] WS okuma hatasi: {ex.Message}");
                }
            }
        }

        static async Task Cleanup()
        {
            Console.WriteLine("[BRIDGE] Temizlik yapiliyor...");
            _cts.Cancel();

            if (_ws?.State == WebSocketState.Open)
            {
                // Session bitis sinyali gonder
                var endMsg = Encoding.UTF8.GetBytes(
                    JsonConvert.SerializeObject(new { type = "session_end" }));
                await _ws.SendAsync(new ArraySegment<byte>(endMsg),
                    WebSocketMessageType.Text, true, CancellationToken.None);
                await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", CancellationToken.None);
            }

            _appEndpoint?.BeginTerminate(ar => _appEndpoint.EndTerminate(ar), null);
        }
    }
}
