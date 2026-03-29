package com.databoss.sfbucwa.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Skype for Business UCWA bağlantı konfigürasyonu.
 *
 * <p>On-premises SfB Server veya SfB Online (O365) için gerekli
 * OAuth2 + autodiscover parametrelerini barındırır.</p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "sfb.ucwa")
public class UcwaProperties {

    /** SfB Autodiscover URL — on-prem: https://lyncdiscover.domain.com */
    @NotBlank
    private String autodiscoverUrl;

    /** Azure AD / ADFS OAuth2 token endpoint */
    @NotBlank
    private String tokenUrl;

    /** Azure AD / ADFS client (application) ID */
    @NotBlank
    private String clientId;

    /** Client secret */
    @NotBlank
    private String clientSecret;

    /** OAuth2 resource / audience — genellikle https://lyncdiscover.domain.com  */
    @NotBlank
    private String resource;

    /** Redirect URI (authorization_code flow için) */
    private String redirectUri = "https://localhost:8443/callback";

    /** UCWA application user-agent adı */
    private String applicationUserAgent = "SfbUcwaSpringApp";

    /** UCWA application culture */
    private String culture = "tr-TR";

    /** Event channel polling interval (ms) */
    private int eventPollingIntervalMs = 2000;

    /** Event channel timeout (saniye) */
    private int eventTimeoutSeconds = 180;

    /** Maksimum yeniden bağlanma denemesi */
    private int maxReconnectAttempts = 5;

    /** On-prem deploy ise true, SfB Online ise false */
    private boolean onPremises = true;

    /** TLS sertifika doğrulamasını devre dışı bırak (SADECE dev/test) */
    private boolean trustAllCertificates = false;
}
