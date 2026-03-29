package com.databoss.sfbucwa.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Virtual Thread tabanli executor konfigurasyonu.
 *
 * <p>JDK 21+ Virtual Threads kullanarak:</p>
 * <ul>
 *   <li>Her kayit dosyasi icin ayri bir virtual thread baslatilir</li>
 *   <li>Platform thread'lere gore ~1000x daha hafif</li>
 *   <li>I/O-bound isler (dosya okuma, HTTP, STT bekleme) icin ideal</li>
 *   <li>Semaphore ile es zamanli islem limiti kontrol edilir</li>
 * </ul>
 */
@Slf4j
@Configuration
public class VirtualThreadConfig {

    @Bean(name = "recordingExecutor", destroyMethod = "close")
    public ExecutorService recordingExecutor() {
        log.info("Virtual Thread executor olusturuluyor (JDK {})", Runtime.version());
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Es zamanli STT islemi limitler — STT servisi asiri yuklenmesin.
     * RecordingProperties.maxConcurrentProcessing degeriyle sinirlenir.
     */
    @Bean
    public Semaphore processingThrottle(RecordingProperties props) {
        log.info("Processing throttle: max {} concurrent", props.getMaxConcurrentProcessing());
        return new Semaphore(props.getMaxConcurrentProcessing());
    }
}
