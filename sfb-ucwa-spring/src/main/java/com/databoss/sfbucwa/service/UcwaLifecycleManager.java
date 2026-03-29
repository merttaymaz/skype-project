package com.databoss.sfbucwa.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UcwaLifecycleManager {

    private final UcwaApplicationService appService;
    private final EventChannelService eventChannelService;

    /**
     * Uygulama basladiginda UCWA bootstrap'i otomatik olarak calistirir
     * ve event channel dinlemeyi baslatir.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Uygulama hazirlandi, UCWA bootstrap tetikleniyor...");
        try {
            appService.bootstrap().block();
            eventChannelService.startListening();
            log.info("UCWA basariyla baslatildi ve event channel dinleniyor");
        } catch (Exception e) {
            log.error("UCWA bootstrap basarisiz — manuel olarak /api/v1/ucwa/bootstrap cagirin", e);
        }
    }

    /**
     * Her 30 dakikada bir UCWA application'in canli olup olmadigini kontrol eder.
     * Application expire olduysa yeniden bootstrap yapar.
     */
    @Scheduled(fixedDelay = 1800000, initialDelay = 1800000) // 30 dakika
    public void healthCheck() {
        if (appService.getCurrentApplication().get() == null) {
            log.warn("UCWA Application bulunamadi, yeniden bootstrap yapiliyor...");
            try {
                appService.bootstrap().block();
                eventChannelService.startListening();
            } catch (Exception e) {
                log.error("Yeniden bootstrap basarisiz", e);
            }
        }
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Uygulama kapatiliyor, UCWA temizligi yapiliyor...");
        eventChannelService.stopListening();
        try {
            appService.destroyApplication().block();
        } catch (Exception e) {
            log.warn("Application silme hatasi (shutdown)", e);
        }
    }
}
