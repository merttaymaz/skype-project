package com.databoss.sfbucwa.config;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final UcwaProperties ucwaProperties;

    @Bean
    public WebClient ucwaWebClient() throws SSLException {
        HttpClient httpClient = HttpClient.create()
                .followRedirect(true)
                .responseTimeout(java.time.Duration.ofSeconds(30));

        // On-prem ortamlarda self-signed cert desteği (SADECE dev/test)
        if (ucwaProperties.isTrustAllCertificates()) {
            log.warn("⚠️ TLS sertifika doğrulaması devre dışı — SADECE geliştirme ortamında kullanın!");
            SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
            httpClient = httpClient.secure(spec -> spec.sslContext(sslContext));
        }

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .filter(logRequest())
                .filter(logResponse())
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.debug("UCWA Request: {} {}", request.method(), request.url());
            return Mono.just(request);
        });
    }

    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            log.debug("UCWA Response: {}", response.statusCode());
            return Mono.just(response);
        });
    }
}
