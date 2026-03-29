package com.databoss.sfbstream.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final StreamingProperties props;

    @Bean
    public WebClient sttWebClient() {
        return WebClient.builder()
                .baseUrl(props.getSttServiceUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }
}
