using System;
using Topshelf;

namespace SfbAudioBridge
{
    /// <summary>
    /// TopShelf ile Windows Service olarak calisir.
    ///
    /// Kullanim:
    ///   Konsol olarak:     SfbAudioBridge.exe
    ///   Service kur:       SfbAudioBridge.exe install
    ///   Service baslat:    SfbAudioBridge.exe start
    ///   Service durdur:    SfbAudioBridge.exe stop
    ///   Service kaldir:    SfbAudioBridge.exe uninstall
    ///
    /// Veya dogrudan sc.exe ile:
    ///   sc create SfbAudioBridge binPath="C:\SfbAudioBridge\SfbAudioBridge.exe"
    /// </summary>
    class Program
    {
        static int Main(string[] args)
        {
            var exitCode = HostFactory.Run(host =>
            {
                host.Service<AudioBridgeService>(svc =>
                {
                    svc.ConstructUsing(() => new AudioBridgeService());
                    svc.WhenStarted(s => s.Start());
                    svc.WhenStopped(s => s.Stop());
                });

                // Windows Service ayarlari
                host.SetServiceName("SfbAudioBridge");
                host.SetDisplayName("SfB Audio Bridge");
                host.SetDescription(
                    "Skype for Business meeting ses streamini WebSocket uzerinden " +
                    "Spring Boot STT servisine aktarir.");

                // Local System hesabiyla calistir
                // (SfB sertifikasina erisim gerektiginden)
                host.RunAsLocalSystem();

                // Otomatik baslat
                host.StartAutomatically();

                // Hata durumunda yeniden baslat
                host.EnableServiceRecovery(recovery =>
                {
                    recovery.RestartService(delayInMinutes: 1);  // 1. hata: 1 dk sonra restart
                    recovery.RestartService(delayInMinutes: 5);  // 2. hata: 5 dk sonra restart
                    recovery.RestartService(delayInMinutes: 15); // 3. hata: 15 dk sonra restart
                    recovery.SetResetPeriod(days: 1);            // 24 saat sonra sayaci sifirla
                });
            });

            return (int)Convert.ChangeType(exitCode, exitCode.GetTypeCode());
        }
    }
}
