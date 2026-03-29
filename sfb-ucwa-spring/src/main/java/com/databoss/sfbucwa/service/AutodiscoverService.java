package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.config.UcwaProperties;
import com.databoss.sfbucwa.exception.UcwaApiException;
import com.databoss.sfbucwa.model.AutodiscoverResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutodiscoverService {

    private final WebClient ucwaWebClient;
    private final UcwaProperties props;
    private final TokenService tokenService;

    public Mono<String> discoverUserResource() {
        String autodiscoverUrl = props.getAutodiscoverUrl();
        log.info("Autodiscover baslatiliyor: {}", autodiscoverUrl);

        return ucwaWebClient.get()
                .uri(autodiscoverUrl)
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(
                                        new UcwaApiException("Autodiscover hatasi", response.statusCode().value(), body))))
                .bodyToMono(AutodiscoverResponse.class)
                .map(response -> {
                    String userResourceUrl = response.getUserResourceUrl();
                    if (userResourceUrl == null) {
                        throw new UcwaApiException("Autodiscover yanitinda user resource bulunamadi", 0, null);
                    }
                    log.info("User resource kesfedildi: {}", userResourceUrl);
                    return userResourceUrl;
                });
    }

    public Mono<String> discoverApplicationsUrl(String userResourceUrl) {
        log.info("Applications URL kesfediliyor: {}", userResourceUrl);

        return tokenService.getAccessToken()
                .flatMap(token -> ucwaWebClient.get()
                        .uri(userResourceUrl)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .onStatus(status -> status.isError(), response ->
                                response.bodyToMono(String.class)
                                        .flatMap(body -> Mono.error(
                                                new UcwaApiException("User resource hatasi", response.statusCode().value(), body))))
                        .bodyToMono(AutodiscoverResponse.class)
                        .map(response -> {
                            var links = response.getLinks();
                            if (links != null && links.containsKey("applications")) {
                                String appsUrl = links.get("applications").getHref();
                                log.info("Applications URL bulundu: {}", appsUrl);
                                return appsUrl;
                            }
                            throw new UcwaApiException("Applications link bulunamadi", 0, null);
                        }));
    }
}
