package com.databoss.sfbucwa.integration;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;

/**
 * Entegrasyon testi: Tam UCWA bootstrap akisini MockWebServer ile simule eder.
 *
 * <p>Bu test gercek bir SfB Server gerektirmez — tum UCWA endpoint'leri mock'lanir.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class UcwaBootstrapIntegrationTest {

    static MockWebServer sfbMockServer;

    @Autowired
    WebTestClient webTestClient;

    @BeforeAll
    static void startMockSfb() throws IOException {
        sfbMockServer = new MockWebServer();

        sfbMockServer.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                String path = request.getPath();

                // Token endpoint
                if (path != null && path.contains("/token")) {
                    return new MockResponse()
                            .setBody("""
                                    {"access_token":"mock-token","token_type":"Bearer","expires_in":3600}
                                    """)
                            .addHeader("Content-Type", "application/json");
                }

                // Autodiscover
                if (path != null && path.contains("/Autodiscover")) {
                    String userUrl = sfbMockServer.url("/ucwa/oauth/user").toString();
                    return new MockResponse()
                            .setBody("""
                                    {"_links":{"user":{"href":"%s"}}}
                                    """.formatted(userUrl))
                            .addHeader("Content-Type", "application/json");
                }

                // User resource -> applications URL
                if (path != null && path.contains("/ucwa/oauth/user")) {
                    String appsUrl = sfbMockServer.url("/ucwa/v1/applications").toString();
                    return new MockResponse()
                            .setBody("""
                                    {"_links":{"applications":{"href":"%s"}}}
                                    """.formatted(appsUrl))
                            .addHeader("Content-Type", "application/json");
                }

                // Create application
                if (path != null && path.contains("/ucwa/v1/applications") && request.getMethod().equals("POST")) {
                    return new MockResponse()
                            .setBody("""
                                    {
                                        "_links":{
                                            "self":{"href":"/ucwa/v1/applications/123"},
                                            "events":{"href":"/ucwa/v1/applications/123/events"}
                                        },
                                        "_embedded":{
                                            "me":{"name":"Test User","uri":"sip:test@domain.com","_links":{}},
                                            "people":{"_links":{"search":{"href":"/ucwa/v1/applications/123/people/search"}}},
                                            "communication":{"_links":{"startMessaging":{"href":"/ucwa/v1/applications/123/communication/messagingInvitations"}}}
                                        }
                                    }
                                    """)
                            .addHeader("Content-Type", "application/json");
                }

                return new MockResponse().setResponseCode(404);
            }
        });

        sfbMockServer.start(9999);
    }

    @AfterAll
    static void stopMockSfb() throws IOException {
        sfbMockServer.shutdown();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("sfb.ucwa.autodiscover-url", () -> sfbMockServer.url("/Autodiscover").toString());
        registry.add("sfb.ucwa.token-url", () -> sfbMockServer.url("/token").toString());
        registry.add("sfb.ucwa.resource", () -> sfbMockServer.url("/").toString());
    }

    @Test
    @DisplayName("Bootstrap endpoint basarili sekilde UCWA application olusturur")
    void bootstrapShouldCreateApplication() {
        webTestClient.post()
                .uri("/api/v1/ucwa/bootstrap")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("OK")
                .jsonPath("$.user").isEqualTo("Test User");
    }

    @Test
    @DisplayName("Status endpoint baglanti durumunu dondurur")
    void statusShouldReturnConnectionState() {
        // Once bootstrap yap
        webTestClient.post().uri("/api/v1/ucwa/bootstrap").exchange();

        webTestClient.get()
                .uri("/api/v1/ucwa/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.connected").isEqualTo(true);
    }
}
