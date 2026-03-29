using System;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Rtc.Collaboration.AudioVideo;
using Microsoft.Rtc.Collaboration;
using Newtonsoft.Json;
using NLog;

namespace SfbAudioBridge
{
    /// <summary>
    /// Tek bir conference icin audio yakalama ve WebSocket streaming.
    ///
    /// Yasam dongusu:
    ///   1. WebSocket baglan → session_start gonder
    ///   2. AudioVideoFlow'a SpeechRecognitionConnector bagla
    ///   3. Her 20ms PCM chunk → WebSocket binary frame
    ///   4. Spring'den gelen partial transkriptleri logla
    ///   5. Conference bittiginde → session_end → WebSocket kapat
    ///
    /// PCM format: 16kHz, 16-bit, mono = 640 byte / 20ms = 32KB/s
    /// </summary>
    public class ConferenceAudioHandler : IDisposable
    {
        private static readonly Logger Log = LogManager.GetCurrentClassLogger();

        private readonly AudioVideoCall _avCall;
        private readonly Conversation _conversation;
        private readonly string _springWsUrl;
        private readonly CancellationToken _parentToken;
        private readonly CancellationTokenSource _localCts;

        private ClientWebSocket _ws;
        private SpeechRecognitionConnector _connector;
        private string _sessionId;
        private long _totalBytesSent;
        private bool _disposed;

        public ConferenceAudioHandler(
            AudioVideoCall avCall,
            Conversation conversation,
            string springWsUrl,
            CancellationToken parentToken)
        {
            _avCall = avCall;
            _conversation = conversation;
            _springWsUrl = springWsUrl;
            _parentToken = parentToken;
            _localCts = CancellationTokenSource.CreateLinkedTokenSource(parentToken);
            _sessionId = "bridge-" + Guid.NewGuid().ToString("N").Substring(0, 8);
        }

        /// <summary>
        /// Audio streaming baslatir.
        /// </summary>
        public void StartStreaming()
        {
            // AudioVideoFlow hazir mi kontrol et
            if (_avCall.Flow != null)
            {
                Task.Run(() => InitializeStreaming(_avCall.Flow), _localCts.Token);
            }
            else
            {
                // Flow henuz hazir degilse event bekle
                _avCall.AudioVideoFlowConfigurationRequested += (sender, e) =>
                {
                    Log.Info("[{0}] AudioVideoFlow hazirlandi", _sessionId);
                    Task.Run(() => InitializeStreaming(e.Flow), _localCts.Token);
                };
            }

            // Conversation bittiginde temizle
            _conversation.StateChanged += (sender, e) =>
            {
                if (e.NewState == ConversationState.Terminated)
                {
                    Log.Info("[{0}] Conversation sonlandi", _sessionId);
                    _localCts.Cancel();
                }
            };
        }

        /// <summary>
        /// WebSocket bagla → Flow'a connector bagla → PCM streaming baslat.
        /// </summary>
        private async Task InitializeStreaming(AudioVideoFlow flow)
        {
            try
            {
                // 1. WebSocket baglan
                await ConnectWebSocket();

                // 2. Session baslat handshake
                await SendSessionStart();

                // 3. Audio connector bagla ve stream baslat
                StartAudioCapture(flow);

                // 4. Partial transkript alici thread
                _ = Task.Run(ReceiveTranscripts, _localCts.Token);

                Log.Info("[{0}] Audio streaming aktif", _sessionId);
            }
            catch (Exception ex)
            {
                Log.Error(ex, "[{0}] Streaming baslatilamadi", _sessionId);
                Dispose();
            }
        }

        // ─── WebSocket ───

        private async Task ConnectWebSocket()
        {
            int maxRetries = 10;
            int delayMs = 5000;

            for (int attempt = 1; attempt <= maxRetries; attempt++)
            {
                try
                {
                    _ws = new ClientWebSocket();
                    _ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(30);
                    _ws.Options.SetRequestHeader("X-Session-Id", _sessionId);

                    Log.Info("[{0}] WebSocket baglaniyor (deneme {1}/{2}): {3}",
                        _sessionId, attempt, maxRetries, _springWsUrl);

                    await _ws.ConnectAsync(new Uri(_springWsUrl), _localCts.Token);
                    Log.Info("[{0}] WebSocket baglandi", _sessionId);
                    return;
                }
                catch (Exception ex) when (attempt < maxRetries)
                {
                    Log.Warn("[{0}] WebSocket baglanti hatasi (deneme {1}): {2}",
                        _sessionId, attempt, ex.Message);
                    await Task.Delay(delayMs * attempt, _localCts.Token);
                }
            }

            throw new InvalidOperationException("WebSocket baglanamadi — " + maxRetries + " deneme basarisiz");
        }

        private async Task SendSessionStart()
        {
            string confUri = _conversation.ConferenceSession?.ConferenceUri ?? "direct-call";
            string organizer = _conversation.ConferenceSession?.Organizer?.Uri ?? "unknown";

            var handshake = JsonConvert.SerializeObject(new
            {
                type = "session_start",
                sessionId = _sessionId,
                conferenceUri = confUri,
                organizer = organizer,
                audioFormat = "pcm",
                sampleRate = 16000,
                channels = 1,
                bitsPerSample = 16,
                chunkIntervalMs = 20
            });

            await SendText(handshake);
            Log.Info("[{0}] Session start gonderildi: conf={1}", _sessionId, confUri);
        }

        // ─── Audio Capture ───

        /// <summary>
        /// SpeechRecognitionConnector ile mixed audio (tum katilimcilar) yakalayip
        /// WebSocket uzerinden binary frame olarak gonderir.
        ///
        /// Her 20ms'de 640 byte PCM chunk gelir.
        /// </summary>
        private void StartAudioCapture(AudioVideoFlow flow)
        {
            _connector = new SpeechRecognitionConnector();
            _connector.AttachFlow(flow);

            var stream = _connector.Start();
            Log.Info("[{0}] Audio capture baslatildi (SpeechRecognitionConnector)", _sessionId);

            // Ayri thread'de stream oku ve WS'e gonder
            Task.Run(async () =>
            {
                byte[] buffer = new byte[640]; // 20ms @ 16kHz 16-bit mono

                try
                {
                    while (!_localCts.IsCancellationRequested)
                    {
                        int bytesRead = stream.Read(buffer, 0, buffer.Length);
                        if (bytesRead <= 0) break;

                        if (_ws?.State == WebSocketState.Open)
                        {
                            await _ws.SendAsync(
                                new ArraySegment<byte>(buffer, 0, bytesRead),
                                WebSocketMessageType.Binary,
                                endOfMessage: true,
                                _localCts.Token);

                            _totalBytesSent += bytesRead;
                        }
                        else
                        {
                            Log.Warn("[{0}] WebSocket kapali, audio gonderilemedi", _sessionId);
                            break;
                        }
                    }
                }
                catch (OperationCanceledException) { /* Normal kapanis */ }
                catch (IOException ex)
                {
                    Log.Warn("[{0}] Audio stream IO hatasi: {1}", _sessionId, ex.Message);
                }
                catch (Exception ex)
                {
                    Log.Error(ex, "[{0}] Audio capture hatasi", _sessionId);
                }
                finally
                {
                    Log.Info("[{0}] Audio capture sona erdi. Toplam: {1:N0} bytes ({2:N1} dakika)",
                        _sessionId, _totalBytesSent, _totalBytesSent / 32000.0 / 60.0);

                    await SendSessionEnd();
                    Dispose();
                }
            }, _localCts.Token);
        }

        // ─── Partial Transkript Alma ───

        private async Task ReceiveTranscripts()
        {
            var buffer = new byte[8192];

            try
            {
                while (_ws?.State == WebSocketState.Open && !_localCts.IsCancellationRequested)
                {
                    var result = await _ws.ReceiveAsync(
                        new ArraySegment<byte>(buffer), _localCts.Token);

                    if (result.MessageType == WebSocketMessageType.Text)
                    {
                        string json = Encoding.UTF8.GetString(buffer, 0, result.Count);
                        Log.Info("[{0}] TRANSKRIPT: {1}", _sessionId, json);

                        // Burada isterseniz transkripti baska bir yere (DB, dosya, event) yazabilirsiniz
                    }
                    else if (result.MessageType == WebSocketMessageType.Close)
                    {
                        Log.Info("[{0}] WebSocket server tarafindan kapatildi", _sessionId);
                        break;
                    }
                }
            }
            catch (OperationCanceledException) { /* Normal */ }
            catch (Exception ex)
            {
                Log.Warn("[{0}] Transkript alma hatasi: {1}", _sessionId, ex.Message);
            }
        }

        // ─── Helpers ───

        private async Task SendSessionEnd()
        {
            try
            {
                if (_ws?.State == WebSocketState.Open)
                {
                    await SendText(JsonConvert.SerializeObject(new { type = "session_end" }));
                    await _ws.CloseAsync(
                        WebSocketCloseStatus.NormalClosure, "session ended", CancellationToken.None);
                }
            }
            catch (Exception ex)
            {
                Log.Warn("[{0}] Session end gonderim hatasi: {1}", _sessionId, ex.Message);
            }
        }

        private async Task SendText(string text)
        {
            if (_ws?.State != WebSocketState.Open) return;
            var bytes = Encoding.UTF8.GetBytes(text);
            await _ws.SendAsync(
                new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, _localCts.Token);
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;

            _localCts.Cancel();

            try { _connector?.Stop(); _connector?.DetachFlow(); }
            catch { /* ignore */ }

            try { _ws?.Dispose(); }
            catch { /* ignore */ }

            Log.Info("[{0}] Handler disposed", _sessionId);
        }
    }
}
