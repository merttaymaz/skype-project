package com.databoss.sfbucwa.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageRequest {

    /** Hedef kullanıcının SIP URI'si — örn: sip:user@domain.com */
    @NotBlank(message = "Hedef SIP URI boş olamaz")
    private String toSipUri;

    /** Gönderilecek mesaj içeriği */
    @NotBlank(message = "Mesaj boş olamaz")
    private String message;

    /** Mesaj formatı: text/plain veya text/html */
    private String contentType = "text/plain";

    /** Konu (opsiyonel) */
    private String subject;
}
