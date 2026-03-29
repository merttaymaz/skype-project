package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.config.UcwaProperties;
import com.databoss.sfbucwa.exception.UcwaAuthenticationException;
import com.databoss.sfbucwa.model.OAuthTokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SfB UCWA için OAuth2 token yönetimi.
 *
 * <p>On-premises: ADFS token endpoint kullanır (client_credentials veya ROPC).
 * <br>SfB Online: Azure AD v2 token endpoint kullanır.</p>
 *
 * <p>Token otomatik olarak cache'lenir ve expire olmadan önce yenilenir.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final WebClient ucwaWebClient;
    private final UcwaProperties props;

    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    /**
     * Geçerli bir access token döndürür.
     * Token expire olduysa veya yoksa yeni bir token alır.
     */
    public Mono<String> getAccessToken() {
        CachedToken current = cachedToken.get();
        if (current != null && !current.isExpired()) {
            return Mono.just(current.accessToken());
        }
        return refreshToken();
    }

    /**
     * Client Credentials flow ile yeni token alır.
     * On-prem ADFS veya Azure AD'ye göre parametreler değişir.
     */
    private Mono<String> refreshToken() {
        log.info("OAuth2 token yenileniyor: {}", props.getTokenUrl());

        return ucwaWebClient.post()
                .uri(props.getTokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters
                        .fromFormData("grant_type", "client_credentials")
                        .with("client_id", props.getClientId())
                        .with("client_secret", props.getClientSecret())
                        .with("resource", props.getResource()))
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(
                                        new UcwaAuthenticationException(
                                                "Token alınamadı: HTTP " + response.statusCode() + " — " + body))))
                .bodyToMono(OAuthTokenResponse.class)
                .map(tokenResponse -> {
                    long expiresAt = Instant.now().getEpochSecond() + tokenResponse.getExpiresIn() - 60; // 60s buffer
                    cachedToken.set(new CachedToken(tokenResponse.getAccessToken(), expiresAt));
                    log.info("OAuth2 token başarıyla alındı (expires_in={}s)", tokenResponse.getExpiresIn());
                    return tokenResponse.getAccessToken();
                })
                .doOnError(e -> log.error("Token alma hatası", e));
    }

    /**
     * Resource Owner Password Credentials (ROPC) flow.
     * On-prem ADFS ortamlarında service account ile kullanılır.
     */
    public Mono<String> getAccessTokenWithPassword(String username, String password) {
        log.info("ROPC flow ile token alınıyor, user={}", username);

        return ucwaWebClient.post()
                .uri(props.getTokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters
                        .fromFormData("grant_type", "password")
                        .with("client_id", props.getClientId())
                        .with("client_secret", props.getClientSecret())
                        .with("resource", props.getResource())
                        .with("username", username)
                        .with("password", password))
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(
                                        new UcwaAuthenticationException("ROPC token hatası: " + body))))
                .bodyToMono(OAuthTokenResponse.class)
                .map(tokenResponse -> {
                    long expiresAt = Instant.now().getEpochSecond() + tokenResponse.getExpiresIn() - 60;
                    cachedToken.set(new CachedToken(tokenResponse.getAccessToken(), expiresAt));
                    return tokenResponse.getAccessToken();
                });
    }

    /** Token cache'ini temizler — yeniden auth gerektiğinde kullanılır */
    public void invalidateToken() {
        log.info("Token cache temizlendi");
        cachedToken.set(null);
    }

    private record CachedToken(String accessToken, long expiresAtEpochSecond) {
        boolean isExpired() {
            return Instant.now().getEpochSecond() >= expiresAtEpochSecond;
        }
    }
}
