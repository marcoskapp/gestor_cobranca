package br.com.gestorcobranca.communication;

import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases.AccountView;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases.CreateAccountCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook do WhatsApp por HTTP: assinatura validada antes de qualquer processamento, handshake,
 * reentrega idêntica e mensagem repetida sem efeito, eventos fora da janela de replay, limite de
 * tamanho e rota desconhecida.
 */
class WhatsAppWebhookIT extends CommunicationTestSupport {

    private static final String SECRET = "app-secret-teste";

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    AccountView cloud;
    String path;

    @BeforeEach
    void setUp() {
        provision("whk");
        cloud = asAdmin(() -> channels.create(new CreateAccountCommand("WHATSAPP", "WHATSAPP_CLOUD", creditorB,
                "WhatsApp Loja B", "1029384756", null, null,
                Map.of("accessToken", "tok", "appSecret", SECRET, "verifyToken", "verif-123"))));
        path = cloud.webhookPath();
        assertThat(path).startsWith("/api/v1/webhooks/whatsapp/");
        assertThat(cloud.credentialKeys()).containsExactlyInAnyOrder("accessToken", "appSecret", "verifyToken");
        assertThat(count("select count(*) from communication.channel_account where id = ? "
                + "and credentials_encrypted not like '%app-secret%'", cloud.id())).isEqualTo(1);
    }

    private static String payload(String wamid, long epochSecond, String text) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages","value":{
                "messaging_product":"whatsapp","messages":[{"from":"5511987650000","id":"%s","timestamp":"%d",
                "type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(wamid, epochSecond, text);
    }

    private static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int post(String target, String body, String signature) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + target))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            request.header("X-Hub-Signature-256", signature);
        }
        return send(request.build()).statusCode();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long received(String wamid) {
        return count("select count(*) from communication.message where provider_message_id = ? and status = 'RECEIVED'",
                wamid);
    }

    @Test
    void handshakeRequiresTheAccountVerifyToken() {
        HttpResponse<String> ok = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path
                + "?hub.mode=subscribe&hub.verify_token=verif-123&hub.challenge=abc123")).GET().build());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("abc123");
        HttpResponse<String> wrong = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path
                + "?hub.mode=subscribe&hub.verify_token=errado&hub.challenge=abc123")).GET().build());
        assertThat(wrong.statusCode()).isEqualTo(403);
    }

    @Test
    void invalidSignatureIsRejectedBeforeProcessing() {
        String body = payload("wamid.SIG", Instant.now().getEpochSecond(), "oi");
        assertThat(post(path, body, sign("outro-segredo", body))).isEqualTo(401);
        assertThat(post(path, body, null)).isEqualTo(401);
        assertThat(received("wamid.SIG")).isZero();
        assertThat(count("select count(*) from communication.webhook_receipt")).isZero();
        assertThat(count("select count(*) from shared.access_log where event_type = 'WEBHOOK_REJECTED' "
                + "and details ->> 'reason' = 'invalid_signature'")).isEqualTo(2);
        assertThat(post("/api/v1/webhooks/whatsapp/rota-inexistente", body, sign(SECRET, body))).isEqualTo(404);
    }

    @Test
    void redeliveryAndRepeatedMessageIdHaveNoEffect() {
        long now = Instant.now().getEpochSecond();
        String body = payload("wamid.DUP", now, "Quero negociar");
        assertThat(post(path, body, sign(SECRET, body))).isEqualTo(200);
        assertThat(post(path, body, sign(SECRET, body))).isEqualTo(200);
        // mesmo id do provedor com corpo diferente (reenvio do provedor): idempotente pela mensagem
        String retried = payload("wamid.DUP", now + 1, "Quero negociar");
        assertThat(post(path, retried, sign(SECRET, retried))).isEqualTo(200);
        assertThat(received("wamid.DUP")).isEqualTo(1);
        assertThat(count("select count(*) from communication.webhook_receipt")).isEqualTo(2);
        // contato desconhecido na conta do credor B: conversa não identificada na fila do credor
        assertThat(count("select count(*) from communication.conversation where channel_account_id = ? "
                + "and debtor_id is null and creditor_id = ? and owner_type = 'QUEUE'", cloud.id(), creditorB))
                .isEqualTo(1);
    }

    @Test
    void staleEventsOversizedBodiesAndMalformedPayloads() {
        long old = Instant.now().minusSeconds(8 * 24 * 3600).getEpochSecond();
        String stale = payload("wamid.OLD", old, "antiga");
        assertThat(post(path, stale, sign(SECRET, stale))).isEqualTo(200);
        assertThat(received("wamid.OLD")).isZero();

        String huge = payload("wamid.BIG", Instant.now().getEpochSecond(), "x".repeat(300_000));
        assertThat(post(path, huge, sign(SECRET, huge))).isEqualTo(413);

        String malformed = "{\"entry\": [";
        assertThat(post(path, malformed, sign(SECRET, malformed))).isEqualTo(400);
    }
}
