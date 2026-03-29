package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.exception.UcwaApiException;
import com.databoss.sfbucwa.model.PresenceInfo;
import com.databoss.sfbucwa.model.UcwaApplicationResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private final WebClient ucwaWebClient;
    private final TokenService tokenService;
    private final UcwaApplicationService appService;

    /**
     * Oturum acmis kullanicinin kendi presence bilgisini getirir.
     */
    public Mono<PresenceInfo> getMyPresence() {
        UcwaApplicationResource app = appService.getCurrentApplication().get();
        if (app == null || app.getEmbedded() == null || app.getEmbedded().getMe() == null) {
            return Mono.error(new UcwaApiException("Application baslatilmamis", 0, null));
        }

        var meLinks = app.getEmbedded().getMe().getLinks();
        if (meLinks == null || !meLinks.containsKey("presence")) {
            return Mono.error(new UcwaApiException("Me/presence link bulunamadi", 0, null));
        }

        String presenceUrl = appService.resolveUrl(meLinks.get("presence").getHref());

        return tokenService.getAccessToken()
                .flatMap(token -> ucwaWebClient.get()
                        .uri(presenceUrl)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .map(data -> PresenceInfo.builder()
                                .availability((String) data.get("availability"))
                                .activity((String) data.get("activity"))
                                .build()));
    }

    /**
     * Kendi presence durumunu gunceller.
     */
    public Mono<Void> setMyPresence(String availability) {
        UcwaApplicationResource app = appService.getCurrentApplication().get();
        if (app == null || app.getEmbedded() == null || app.getEmbedded().getMe() == null) {
            return Mono.error(new UcwaApiException("Application baslatilmamis", 0, null));
        }

        var meLinks = app.getEmbedded().getMe().getLinks();
        String presenceUrl = appService.resolveUrl(meLinks.get("presence").getHref());

        log.info("Presence guncelleniyor: {}", availability);

        return tokenService.getAccessToken()
                .flatMap(token -> ucwaWebClient.post()
                        .uri(presenceUrl)
                        .header("Authorization", "Bearer " + token)
                        .bodyValue(Map.of("availability", availability))
                        .retrieve()
                        .bodyToMono(Void.class));
    }

    /**
     * Belirli bir kisinin presence bilgisini sorgular (SIP URI ile).
     */
    public Mono<PresenceInfo> getContactPresence(String sipUri) {
        UcwaApplicationResource app = appService.getCurrentApplication().get();
        if (app == null || app.getEmbedded() == null || app.getEmbedded().getPeople() == null) {
            return Mono.error(new UcwaApiException("Application baslatilmamis", 0, null));
        }

        // People resource uzerinden presence subscription
        var peopleLinks = app.getEmbedded().getPeople().getLinks();
        if (peopleLinks == null || !peopleLinks.containsKey("presenceSubscriptions")) {
            return Mono.error(new UcwaApiException("Presence subscription desteklenmiyor", 0, null));
        }

        String subsUrl = appService.resolveUrl(peopleLinks.get("presenceSubscriptions").getHref());

        return tokenService.getAccessToken()
                .flatMap(token -> ucwaWebClient.post()
                        .uri(subsUrl)
                        .header("Authorization", "Bearer " + token)
                        .bodyValue(Map.of(
                                "duration", 10,
                                "uris", new String[]{sipUri}))
                        .retrieve()
                        .bodyToMono(Map.class)
                        .map(data -> PresenceInfo.builder()
                                .sipUri(sipUri)
                                .build()));
    }
}
