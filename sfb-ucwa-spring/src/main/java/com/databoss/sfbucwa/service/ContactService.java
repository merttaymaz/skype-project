package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.exception.UcwaApiException;
import com.databoss.sfbucwa.model.ContactSearchResult;
import com.databoss.sfbucwa.model.UcwaApplicationResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContactService {

    private final WebClient ucwaWebClient;
    private final TokenService tokenService;
    private final UcwaApplicationService appService;

    /**
     * UCWA people/search endpoint'i uzerinden kisi arar.
     *
     * @param query aranacak isim veya SIP URI
     * @param limit maksimum sonuc sayisi
     */
    public Mono<ContactSearchResult> searchContacts(String query, int limit) {
        log.info("Kisi aranacak: '{}' (limit={})", query, limit);

        UcwaApplicationResource app = appService.getCurrentApplication().get();
        if (app == null || app.getEmbedded() == null || app.getEmbedded().getPeople() == null) {
            return Mono.error(new UcwaApiException("Application/people baslatilmamis", 0, null));
        }

        var peopleLinks = app.getEmbedded().getPeople().getLinks();
        if (peopleLinks == null || !peopleLinks.containsKey("search")) {
            return Mono.error(new UcwaApiException("People search link bulunamadi", 0, null));
        }

        String searchUrl = appService.resolveUrl(peopleLinks.get("search").getHref());

        return tokenService.getAccessToken()
                .flatMap(token -> ucwaWebClient.get()
                        .uri(searchUrl + "?query=" + query + "&limit=" + limit)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .onStatus(status -> status.isError(), response ->
                                response.bodyToMono(String.class)
                                        .flatMap(body -> Mono.error(
                                                new UcwaApiException("Contact search hatasi",
                                                        response.statusCode().value(), body))))
                        .bodyToMono(ContactSearchResult.class)
                        .doOnSuccess(result -> {
                            int count = result.getEmbedded() != null && result.getEmbedded().getContact() != null
                                    ? result.getEmbedded().getContact().size() : 0;
                            log.info("{} kisi bulundu", count);
                        }));
    }
}
