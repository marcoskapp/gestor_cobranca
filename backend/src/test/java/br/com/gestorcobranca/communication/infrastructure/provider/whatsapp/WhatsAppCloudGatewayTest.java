package br.com.gestorcobranca.communication.infrastructure.provider.whatsapp;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway.OutboundRequest;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway.ProviderTemplate;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway.SendResult;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway.TransientSendException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adaptador contra um servidor HTTP local que imita a Graph API. */
class WhatsAppCloudGatewayTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> response = new AtomicReference<>("{\"messages\":[{\"id\":\"wamid.OK\"}]}");
    private final AtomicReference<Long> delayMs = new AtomicReference<>(0L);
    private SimpleMeterRegistry registry;
    private WhatsAppCloudGateway gateway;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v21.0/", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                Thread.sleep(delayMs.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        registry = new SimpleMeterRegistry();
        CommunicationProperties properties = new CommunicationProperties(null, null, null, null,
                new CommunicationProperties.WhatsApp("http://127.0.0.1:" + server.getAddress().getPort() + "/v21.0",
                        Duration.ofSeconds(1), Duration.ofMillis(500)), null, null);
        gateway = new WhatsAppCloudGateway(properties, new JsonCodec(JsonMapper.builder().build()), registry);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static OutboundRequest request(ProviderTemplate template) {
        return new OutboundRequest(UUID.randomUUID(), Map.of("accessToken", "tok-123"), "1234567890", null, null,
                "+5511987654321", null, "Olá", template, UUID.randomUUID());
    }

    @Test
    void sendsFreeTextAndReturnsProviderId() {
        SendResult result = gateway.send(request(null));
        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("wamid.OK");
        assertThat(lastAuth.get()).isEqualTo("Bearer tok-123");
        assertThat(lastBody.get()).contains("\"to\":\"5511987654321\"").contains("\"type\":\"text\"");
        assertThat(registry.get("integration.calls").tag("provider", "whatsapp").tag("outcome", "success").counter()
                .count()).isEqualTo(1);
    }

    @Test
    void sendsApprovedTemplateWithPositionalParameters() {
        gateway.send(request(ProviderTemplate.standard("lembrete_v1", "pt_BR", List.of("Maria", "R$ 10,00"))));
        assertThat(lastBody.get()).contains("\"type\":\"template\"").contains("\"name\":\"lembrete_v1\"")
                .contains("\"code\":\"pt_BR\"").contains("\"text\":\"Maria\"").contains("\"text\":\"R$ 10,00\"");
    }

    @Test
    void standardTemplateHasNoButtonComponent() throws Exception {
        gateway.send(request(ProviderTemplate.standard("lembrete_v1", "pt_BR", List.of("Maria"))));
        JsonNode components = JsonMapper.builder().build().readTree(lastBody.get()).path("template").path("components");
        assertThat(components.size()).isEqualTo(1);
        assertThat(components.get(0).path("type").asString()).isEqualTo("body");
    }

    @Test
    void authenticationTemplateSendsTheCodeInTheBodyAndInTheCopyCodeButton() throws Exception {
        gateway.send(request(ProviderTemplate.authentication("portal_codigo", "pt_BR", "482913")));
        JsonNode template = JsonMapper.builder().build().readTree(lastBody.get()).path("template");
        assertThat(template.path("name").asString()).isEqualTo("portal_codigo");
        JsonNode components = template.path("components");
        assertThat(components.size()).isEqualTo(2);
        JsonNode body = components.get(0);
        assertThat(body.path("type").asString()).isEqualTo("body");
        assertThat(body.path("parameters").size()).isEqualTo(1);
        assertThat(body.path("parameters").get(0).path("type").asString()).isEqualTo("text");
        assertThat(body.path("parameters").get(0).path("text").asString()).isEqualTo("482913");
        JsonNode button = components.get(1);
        assertThat(button.path("type").asString()).isEqualTo("button");
        assertThat(button.path("sub_type").asString()).isEqualTo("url");
        // a Meta exige o índice como texto
        assertThat(button.path("index").isString()).isTrue();
        assertThat(button.path("index").asString()).isEqualTo("0");
        assertThat(button.path("parameters").get(0).path("text").asString()).isEqualTo("482913");
    }

    @Test
    void authenticationTemplateCarriesExactlyOneParameter() {
        assertThatThrownBy(() -> new ProviderTemplate("portal_codigo", "pt_BR", List.of("1", "10"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clientErrorIsDefinitiveRejectionWithProviderCode() {
        status.set(400);
        response.set("{\"error\":{\"code\":131026,\"message\":\"undeliverable\"}}");
        SendResult result = gateway.send(request(null));
        assertThat(result.accepted()).isFalse();
        assertThat(result.errorCode()).isEqualTo("WHATSAPP_131026");
    }

    @Test
    void serverErrorRateLimitAndTimeoutAreTransient() {
        status.set(503);
        assertThatThrownBy(() -> gateway.send(request(null))).isInstanceOf(TransientSendException.class);
        status.set(429);
        assertThatThrownBy(() -> gateway.send(request(null))).isInstanceOf(TransientSendException.class);
        status.set(200);
        delayMs.set(1500L);
        assertThatThrownBy(() -> gateway.send(request(null))).isInstanceOf(TransientSendException.class);
        assertThat(registry.get("integration.calls").tag("outcome", "timeout").counter().count()).isEqualTo(1);
    }
}
