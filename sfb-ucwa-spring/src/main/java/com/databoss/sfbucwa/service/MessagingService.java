package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.exception.UcwaApiException;
import com.databoss.sfbucwa.model.SendMessageRequest;
import com.databoss.sfbucwa.model.UcwaApplicationResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessagingService {

    private final WebClient ucwaWebClient;
    private final TokenService tokenService;
    private final UcwaApplicationService appService;

    /**
     * Yeni bir IM conversation baslatir ve mesaj gonderir.
     *
     * UCWA akisi:
     * 1. POST /communication/messagingInvitations  (conversation baslatir)
     * 2. Event channel uzerinden conversation kabul edilince
     * 3. POST /conversation/{id}/messaging/messages (mesaj gonderir)
     */
    public Mono<String> startConversationAndSendMessage(SendMessageRequest request) {
        log.info("IM conversation baslatiliyor, hedef: {}", request.getToSipUri());

        UcwaApplicationResource app = appService.getCurrentApplication().get();
        if (app == null || app.getEmbedded() == null || app.getEmbedded().getCommunication() == null) {
            return Mono.error(new UcwaApiException("Application/communication baslatilmamis", 0, null));
        }

        var commLinks = app.getEmbedded().getCommunication().getLinks();
        if (commLinks == null || !commLinks.containsKey("startMessaging")) {
            return Mono.error(new UcwaApiException("startMessaging link bulunamadi", 0, null));
        }

        String messagingUrl = appService.resolveUrl(commLinks.get("startMessaging").getHref());

        Map<String, Object> invitation = Map.of(
                "operationId", java.util.UUID.randomUUID().toString(),
                "to", request.getToSipUri(),
                "subject", request.getSubject() != null ? request.getSubject() : "",
                "importance", "Normal",
                "message", Map.of(
                        "href", "data:text/plain," + request.getMessage()
                )
        );

        return tokenService.getAccessToken()
                .flatMap(token -> ucwaWebClient.post()
                        .uri(messagingUrl)
                        .header("Authorization", "Bearer " + token)
                        .bodyValue(invitation)
                        .retrieve()
                        .onStatus(status -> status.isError(), response ->
                                response.bodyToMono(String.class)
                                        .flatMap(body -> Mono.error(
                                                new UcwaApiException("Messaging invitation hatasi",
                                                        response.statusCode().value(), body))))
                        .bodyToMono(String.class)
                        .doOnSuccess(resp -> log.info("Messaging invitation gonderildi"))
                        .doOnError(e -> log.error("Messaging hatasi", e)));
    }

    /**
     * Mevcut bir conversation'a mesaj gonderir.
     *
     * @param conversationMessagingUrl conversation/.../messaging/messages URL'si
     * @param message                  gonderilecek mesaj
     * @param contentType              text/plain veya text/html
     */
    public Mono<Void> sendMessageToConversation(String conversationMessagingUrl, String message, String contentType) {
        String resolvedUrl = appService.resolveUrl(conversationMessagingUrl);
        log.info("Mesaj gonderiliyor: {}", resolvedUrl);

        MediaType mediaType = "text/html".equals(contentType)
                ? MediaType.TEXT_HTML
                : MediaType.TEXT_PLAIN;

        return tokenService.getAccessToken()
                .flatMap(token -> ucwaWebClient.post()
                        .uri(resolvedUrl)
                        .header("Authorization", "Bearer " + token)
                        .contentType(mediaType)
                        .bodyValue(message)
                        .retrieve()
                        .bodyToMono(Void.class));
    }
}
