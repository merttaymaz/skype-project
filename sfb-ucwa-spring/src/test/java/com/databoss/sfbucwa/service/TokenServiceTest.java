package com.databoss.sfbucwa.service;

import com.databoss.sfbucwa.config.UcwaProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private MockWebServer mockServer;
    private TokenService tokenService;

    @BeforeEach
    void setup() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        UcwaProperties props = new UcwaProperties();
        props.setTokenUrl(mockServer.url("/token").toString());
        props.setClientId("test-client");
        props.setClientSecret("test-secret");
        props.setResource("https://test.example.com");
        props.setAutodiscoverUrl("https://test.example.com");

        WebClient webClient = WebClient.builder().build();
        tokenService = new TokenService(webClient, props);
    }

    @AfterEach
    void teardown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Basarili token alimi")
    void shouldGetAccessToken() {
        mockServer.enqueue(new MockResponse()
                .setBody("""
                        {
                            "access_token": "test-token-12345",
                            "token_type": "Bearer",
                            "expires_in": 3600,
                            "scope": "openid"
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        String token = tokenService.getAccessToken().block();

        assertThat(token).isEqualTo("test-token-12345");
    }

    @Test
    @DisplayName("Token cache calisiyor — ikinci cagri network'e gitmiyor")
    void shouldCacheToken() {
        mockServer.enqueue(new MockResponse()
                .setBody("""
                        {
                            "access_token": "cached-token",
                            "token_type": "Bearer",
                            "expires_in": 3600
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        String token1 = tokenService.getAccessToken().block();
        String token2 = tokenService.getAccessToken().block();

        assertThat(token1).isEqualTo(token2);
        assertThat(mockServer.getRequestCount()).isEqualTo(1); // Tek istek gitti
    }

    @Test
    @DisplayName("Token invalidate sonrasi yeni token alinir")
    void shouldRefreshAfterInvalidate() {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"access_token\":\"token-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}")
                .addHeader("Content-Type", "application/json"));
        mockServer.enqueue(new MockResponse()
                .setBody("{\"access_token\":\"token-2\",\"token_type\":\"Bearer\",\"expires_in\":3600}")
                .addHeader("Content-Type", "application/json"));

        String t1 = tokenService.getAccessToken().block();
        tokenService.invalidateToken();
        String t2 = tokenService.getAccessToken().block();

        assertThat(t1).isEqualTo("token-1");
        assertThat(t2).isEqualTo("token-2");
        assertThat(mockServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Token endpoint 401 donerse UcwaAuthenticationException firlatir")
    void shouldThrowOnAuthFailure() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\":\"invalid_client\"}")
                .addHeader("Content-Type", "application/json"));

        Assertions.assertThrows(Exception.class, () ->
                tokenService.getAccessToken().block());
    }
}
