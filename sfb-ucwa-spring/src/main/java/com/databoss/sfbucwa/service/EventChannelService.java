package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.model.UcwaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UCWA Event Channel servisi.
 *
 * <p>UCWA, WebSocket yerine long-polling ile event bildirimi yapar.
 * Bu servis event channel'i surekli dinler ve gelen event'leri
 * Spring ApplicationEvent olarak publish eder.</p>
 *
 * <p>Event tipleri:</p>
 * <ul>
 *   <li>added — yeni kaynak olusturuldu (yeni mesaj, yeni conversation)</li>
 *   <li>updated — mevcut kaynak guncellendi (presence degisikligi)</li>
 *   <li>deleted — kaynak silindi</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventChannelService {

    private final WebClient ucwaWebClient;
    private final TokenService tokenService;
    private final UcwaApplicationService appService;
    private final ApplicationEventPublisher eventPublisher;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    private volatile String nextEventUrl;

    /**
     * Event channel dinlemeyi baslatir.
     * Async olarak calisir — bootstrap tamamlandiktan sonra cagrilmalidir.
     */
    @Async
    public void startListening() {
        if (running.compareAndSet(false, true)) {
            log.info("Event channel dinleme basladi");
            nextEventUrl = appService.getEventChannelUrl();
            pollLoop();
        } else {
            log.warn("Event channel zaten dinleniyor");
        }
    }

    public void stopListening() {
        log.info("Event channel durduruluyor");
        running.set(false);
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                pollOnce().block(Duration.ofSeconds(200));
                reconnectCount.set(0); // basarili poll sonrasi reset
            } catch (WebClientResponseException.Gone e) {
                // 410 Gone — application expire olmus, yeniden bootstrap gerekli
                log.warn("Event channel 410 Gone — application yeniden baslatilmali");
                running.set(false);
                // Uygulama seviyesinde re-bootstrap tetiklenmeli
            } catch (Exception e) {
                int retries = reconnectCount.incrementAndGet();
                if (retries > 5) {
                    log.error("Maksimum yeniden baglanti denemesi asildi, event channel kapatiliyor");
                    running.set(false);
                    break;
                }
                long backoff = Math.min(1000L * retries * retries, 30000L);
                log.warn("Event polling hatasi (deneme {}/5), {}ms sonra tekrar deneniyor: {}",
                        retries, backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("Event channel dinleme sona erdi");
    }

    private Mono<Void> pollOnce() {
        if (nextEventUrl == null) {
            return Mono.error(new IllegalStateException("Event URL tanimli degil"));
        }

        String url = appService.resolveUrl(nextEventUrl);

        return tokenService.getAccessToken()
                .flatMap(token -> ucwaWebClient.get()
                        .uri(url)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .bodyToMono(UcwaEvent.class)
                        .doOnNext(event -> {
                            // Sonraki poll icin next link'i guncelle
                            if (event.getLinks() != null && event.getLinks().containsKey("next")) {
                                nextEventUrl = event.getLinks().get("next").getHref();
                            }
                            // Event'leri isle
                            processEvents(event);
                        })
                        .then());
    }

    private void processEvents(UcwaEvent event) {
        if (event.getSender() == null) return;

        for (UcwaEvent.Sender sender : event.getSender()) {
            if (sender.getEvents() == null) continue;
            for (UcwaEvent.EventData data : sender.getEvents()) {
                log.debug("UCWA Event: rel={}, type={}, link={}",
                        data.getRel(), data.getType(), data.getLink());

                // Spring event olarak publish et — dinleyiciler @EventListener ile yakalar
                eventPublisher.publishEvent(new UcwaEventReceived(sender.getRel(), data));
            }
        }
    }

    /**
     * Spring Application Event wrapper'i.
     * Diger servisler @EventListener ile bu event'leri dinleyebilir.
     */
    public record UcwaEventReceived(String senderRel, UcwaEvent.EventData eventData) {}
}
