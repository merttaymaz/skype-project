package com.databoss.sfbrec.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final RecordingProperties props;

    @Bean
    public WebClient sttWebClient() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(props.getSttTimeout());

        return WebClient.builder()
                .baseUrl(props.getSttServiceUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(50 * 1024 * 1024)) // 50MB — buyuk audio icin
                .build();
    }
}
