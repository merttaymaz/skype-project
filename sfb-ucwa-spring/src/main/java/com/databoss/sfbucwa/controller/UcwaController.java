package com.databoss.sfbucwa.controller;

import com.databoss.sfbucwa.model.ContactSearchResult;
import com.databoss.sfbucwa.model.PresenceInfo;
import com.databoss.sfbucwa.model.SendMessageRequest;
import com.databoss.sfbucwa.model.UcwaApplicationResource;
import com.databoss.sfbucwa.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/ucwa")
@RequiredArgsConstructor
@Tag(name = "SfB UCWA", description = "Skype for Business UCWA entegrasyon API")
public class UcwaController {

    private final UcwaApplicationService appService;
    private final PresenceService presenceService;
    private final MessagingService messagingService;
    private final ContactService contactService;
    private final EventChannelService eventChannelService;

    // ─────────────── Bootstrap ───────────────

    @PostMapping("/bootstrap")
    @Operation(summary = "UCWA bootstrap",
            description = "Autodiscover + Application olusturma + Event channel baslatma")
    public Mono<ResponseEntity<Map<String, String>>> bootstrap() {
        return appService.bootstrap()
                .doOnSuccess(app -> eventChannelService.startListening())
                .map(app -> {
                    String userName = app.getEmbedded() != null && app.getEmbedded().getMe() != null
                            ? app.getEmbedded().getMe().getName() : "unknown";
                    return ResponseEntity.ok(Map.of(
                            "status", "OK",
                            "user", userName,
                            "message", "UCWA application basariyla olusturuldu"));
                });
    }

    @DeleteMapping("/application")
    @Operation(summary = "UCWA Application sil")
    public Mono<ResponseEntity<Void>> destroyApplication() {
        eventChannelService.stopListening();
        return appService.destroyApplication()
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @GetMapping("/status")
    @Operation(summary = "UCWA baglanti durumu")
    public ResponseEntity<Map<String, Object>> getStatus() {
        UcwaApplicationResource app = appService.getCurrentApplication().get();
        boolean connected = app != null;
        String user = connected && app.getEmbedded() != null && app.getEmbedded().getMe() != null
                ? app.getEmbedded().getMe().getName() : null;

        return ResponseEntity.ok(Map.of(
                "connected", connected,
                "user", user != null ? user : "N/A",
                "baseUrl", appService.getApplicationBaseUrl().get() != null
                        ? appService.getApplicationBaseUrl().get() : "N/A"));
    }

    // ─────────────── Presence ───────────────

    @GetMapping("/presence/me")
    @Operation(summary = "Kendi presence bilgimi getir")
    public Mono<ResponseEntity<PresenceInfo>> getMyPresence() {
        return presenceService.getMyPresence()
                .map(ResponseEntity::ok);
    }

    @PutMapping("/presence/me")
    @Operation(summary = "Kendi presence durumumu guncelle",
            description = "Gecerli degerler: Online, Away, Busy, DoNotDisturb, BeRightBack")
    public Mono<ResponseEntity<Map<String, String>>> setMyPresence(@RequestParam String availability) {
        return presenceService.setMyPresence(availability)
                .then(Mono.just(ResponseEntity.ok(Map.of(
                        "status", "OK",
                        "availability", availability))));
    }

    @GetMapping("/presence/{sipUri}")
    @Operation(summary = "Belirli bir kisinin presence bilgisini getir")
    public Mono<ResponseEntity<PresenceInfo>> getContactPresence(@PathVariable String sipUri) {
        return presenceService.getContactPresence(sipUri)
                .map(ResponseEntity::ok);
    }

    // ─────────────── Messaging ───────────────

    @PostMapping("/message")
    @Operation(summary = "Yeni bir IM conversation baslat ve mesaj gonder")
    public Mono<ResponseEntity<Map<String, String>>> sendMessage(@Valid @RequestBody SendMessageRequest request) {
        return messagingService.startConversationAndSendMessage(request)
                .map(resp -> ResponseEntity.ok(Map.of(
                        "status", "OK",
                        "to", request.getToSipUri(),
                        "message", "Mesaj gonderildi")));
    }

    // ─────────────── Contacts ───────────────

    @GetMapping("/contacts/search")
    @Operation(summary = "SfB dizininde kisi ara")
    public Mono<ResponseEntity<ContactSearchResult>> searchContacts(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit) {
        return contactService.searchContacts(query, limit)
                .map(ResponseEntity::ok);
    }
}
