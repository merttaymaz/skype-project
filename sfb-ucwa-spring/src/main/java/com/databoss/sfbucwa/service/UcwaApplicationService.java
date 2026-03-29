package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.config.UcwaProperties;
import com.databoss.sfbucwa.exception.UcwaApiException;
import com.databoss.sfbucwa.model.UcwaApplicationResource;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class UcwaApplicationService {

    private final WebClient ucwaWebClient;
    private final UcwaProperties props;
    private final TokenService tokenService;
    private final AutodiscoverService autodiscoverService;

    @Getter
    private final AtomicReference<UcwaApplicationResource> currentApplication = new AtomicReference<>();

    @Getter
    private final AtomicReference<String> applicationBaseUrl = new AtomicReference<>();

    /**
     * UCWA bootstrap: Autodiscover -> User Resource -> Create Application.
     * Tum UCWA islemlerinden once bu metod cagrilmalidir.
     */
    public Mono<UcwaApplicationResource> bootstrap() {
        log.info("UCWA bootstrap baslatiyor...");

        return autodiscoverService.discoverUserResource()
                .flatMap(autodiscoverService::discoverApplicationsUrl)
                .flatMap(this::createApplication)
                .doOnSuccess(app -> log.info("UCWA Application basariyla olusturuldu: {}",
                        app.getEmbedded() != null && app.getEmbedded().getMe() != null
                                ? app.getEmbedded().getMe().getName() : "unknown"))
                .doOnError(e -> log.error("UCWA bootstrap basarisiz", e));
    }

    /**
     * UCWA Application olusturur (POST /applications).
     */
    private Mono<UcwaApplicationResource> createApplication(String applicationsUrl) {
        log.info("UCWA Application olusturuluyor: {}", applicationsUrl);

        // Base URL'i sakla (relative link'leri resolve etmek icin)
        String baseUrl = extractBaseUrl(applicationsUrl);
        applicationBaseUrl.set(baseUrl);

        Map<String, Object> appRequest = Map.of(
                "userAgent", props.getApplicationUserAgent(),
                "endpointId", java.util.UUID.randomUUID().toString(),
                "culture", props.getCulture()
        );

        return tokenService.getAccessToken()
                .flatMap(token -> ucwaWebClient.post()
                        .uri(applicationsUrl)
                        .header("Authorization", "Bearer " + token)
                        .bodyValue(appRequest)
                        .retrieve()
                        .onStatus(status -> status.isError(), response ->
                                response.bodyToMono(String.class)
                                        .flatMap(body -> Mono.error(
                                                new UcwaApiException("Application olusturulamadi",
                                                        response.statusCode().value(), body))))
                        .bodyToMono(UcwaApplicationResource.class)
                        .doOnSuccess(currentApplication::set));
    }

    /**
     * Mevcut application'in event channel URL'sini dondurur.
     */
    public String getEventChannelUrl() {
        UcwaApplicationResource app = currentApplication.get();
        if (app == null || app.getLinks() == null) {
            throw new UcwaApiException("Application henuz olusturulmadi", 0, null);
        }
        var eventsLink = app.getLinks().get("events");
        if (eventsLink == null) {
            throw new UcwaApiException("Events link bulunamadi", 0, null);
        }
        return resolveUrl(eventsLink.getHref());
    }

    /**
     * Relative UCWA link'lerini absolute URL'ye cevirir.
     */
    public String resolveUrl(String relativeOrAbsoluteUrl) {
        if (relativeOrAbsoluteUrl == null) return null;
        if (relativeOrAbsoluteUrl.startsWith("http")) return relativeOrAbsoluteUrl;
        String base = applicationBaseUrl.get();
        if (base == null) {
            throw new UcwaApiException("Base URL henuz belirlenmedi — bootstrap cagrildi mi?", 0, null);
        }
        return base + relativeOrAbsoluteUrl;
    }

    /**
     * Application'i yok eder (DELETE).
     */
    public Mono<Void> destroyApplication() {
        UcwaApplicationResource app = currentApplication.get();
        if (app == null || app.getLinks() == null || !app.getLinks().containsKey("self")) {
            return Mono.empty();
        }
        String selfUrl = resolveUrl(app.getLinks().get("self").getHref());
        log.info("UCWA Application siliniyor: {}", selfUrl);

        return tokenService.getAccessToken()
                .flatMap(token -> ucwaWebClient.delete()
                        .uri(selfUrl)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .bodyToMono(Void.class))
                .doOnSuccess(v -> {
                    currentApplication.set(null);
                    log.info("UCWA Application basariyla silindi");
                })
                .doOnError(e -> log.warn("Application silme hatasi", e));
    }

    private String extractBaseUrl(String fullUrl) {
        try {
            java.net.URI uri = java.net.URI.create(fullUrl);
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (Exception e) {
            throw new UcwaApiException("URL parse hatasi: " + fullUrl, e);
        }
    }
}
