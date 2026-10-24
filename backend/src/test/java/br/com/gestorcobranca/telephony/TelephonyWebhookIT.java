package br.com.gestorcobranca.telephony;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.ClickToCallCommand;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook de telefonia por HTTP (provedor simulado): assinatura com instante validada antes de
 * qualquer processamento, reentrega idêntica e evento repetido sem efeito, eventos fora da janela
 * de replay, assinatura antiga, limite de tamanho e rota desconhecida.
 */
class TelephonyWebhookIT extends TelephonyTestSupport {

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    CallView dialing;

    @BeforeEach
    void setUp() {
        provision("twh", false);
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "500.00");
        asOperator(operatorX, () -> agents.login(null));
        CallView queued = asOperator(operatorX, () -> calls.clickToCall(new ClickToCallCommand(kase.id(), null), null));
        drainTelephony();
        dialing = call(queued.id());
        assertThat(dialing.status()).isEqualTo("DIALING");
        assertThat(account.webhookPath()).startsWith("/api/v1/webhooks/telephony/");
        assertThat(account.credentialKeys()).containsExactly("webhookSecret");
        assertThat(count("select count(*) from telephony.provider_account where id = ? "
                + "and credentials_encrypted not like '%segredo%'", account.id())).isEqualTo(1);
    }

    private String providerCallId() {
        return string("select provider_call_id from telephony.phone_call where id = ?", dialing.id());
    }

    private String payload(String eventId, String type, Instant at) {
        return """
                {"events":[{"id":"%s","type":"%s","callId":"%s","timestamp":"%s","amd":"HUMAN","hangupBy":"DEBTOR"}]}
                """.formatted(eventId, type, providerCallId(), at);
    }

    private static String sign(String secret, long t, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
            return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int post(String target, String body, String signature) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + target))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            request.header("X-Sandbox-Signature", signature);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int postSigned(String body) {
        return post(account.webhookPath(), body, sign(WEBHOOK_SECRET, Instant.now().getEpochSecond(), body));
    }

    @Test
    void invalidOrOldSignaturesAreRejectedBeforeProcessing() {
        String body = payload("e-sig", "RINGING", Instant.now());
        long now = Instant.now().getEpochSecond();
        assertThat(post(account.webhookPath(), body, sign("outro-segredo", now, body))).isEqualTo(401);
        assertThat(post(account.webhookPath(), body, null)).isEqualTo(401);
        // assinatura válida, mas com instante fora da tolerância (replay de entrega antiga)
        assertThat(post(account.webhookPath(), body, sign(WEBHOOK_SECRET, now - 600, body))).isEqualTo(401);
        assertThat(call(dialing.id()).status()).isEqualTo("DIALING");
        assertThat(count("select count(*) from telephony.webhook_receipt")).isZero();
        assertThat(count("select count(*) from shared.access_log where event_type = 'WEBHOOK_REJECTED' "
                + "and details ->> 'reason' = 'invalid_signature' and details ->> 'channel' = 'telephony'")).isEqualTo(3);
        assertThat(post("/api/v1/webhooks/telephony/rota-inexistente", body, sign(WEBHOOK_SECRET, now, body)))
                .isEqualTo(404);
    }

    @Test
    void redeliveryAndRepeatedEventIdHaveNoEffect() {
        Instant at = Instant.now();
        String ringing = payload("e-1", "RINGING", at);
        assertThat(postSigned(ringing)).isEqualTo(200);
        assertThat(call(dialing.id()).status()).isEqualTo("RINGING");
        assertThat(postSigned(ringing)).isEqualTo(200);
        assertThat(count("select count(*) from telephony.webhook_receipt")).isEqualTo(1);

        // mesmo id de evento com corpo diferente (reenvio do provedor): idempotente pelo evento
        String answered = payload("e-2", "ANSWERED", at.plusSeconds(3));
        assertThat(postSigned(answered)).isEqualTo(200);
        String retried = payload("e-2", "ANSWERED", at.plusSeconds(4));
        assertThat(postSigned(retried)).isEqualTo(200);
        assertThat(count("select count(*) from telephony.provider_event where provider_event_id = 'e-2'")).isEqualTo(1);
        assertThat(count("select count(*) from telephony.webhook_receipt")).isEqualTo(3);
        assertThat(call(dialing.id()).status()).isEqualTo("ANSWERED");

        // evento fora de ordem: obsoleto
        assertThat(postSigned(payload("e-3", "RINGING", at.plusSeconds(5)))).isEqualTo(200);
        assertThat(call(dialing.id()).status()).isEqualTo("ANSWERED");

        assertThat(postSigned(payload("e-4", "COMPLETED", at.plusSeconds(60)))).isEqualTo(200);
        CallView ended = call(dialing.id());
        assertThat(ended.status()).isEqualTo("COMPLETED");
        assertThat(ended.result()).isEqualTo("ANSWERED");
        assertThat(ended.hangupSide()).isEqualTo("DEBTOR");
        assertThat(count("select count(*) from shared.business_event where event_type = 'call.completed' "
                + "and reference_id = ?", dialing.id())).isEqualTo(1);
    }

    @Test
    void staleEventsOversizedAndMalformedBodies() {
        String stale = payload("e-old", "RINGING", Instant.now().minusSeconds(3 * 24 * 3600));
        assertThat(postSigned(stale)).isEqualTo(200);
        assertThat(call(dialing.id()).status()).isEqualTo("DIALING");
        assertThat(count("select count(*) from telephony.provider_event")).isZero();

        assertThat(postSigned("{\"events\":[{\"id\":\"x\",\"type\":\"DESCONHECIDO\"}]}")).isEqualTo(400);
        String huge = "{\"events\":[],\"pad\":\"" + "a".repeat(70 * 1024) + "\"}";
        assertThat(postSigned(huge)).isEqualTo(413);
        // chamada de outra conta/inexistente: ignorada sem erro
        assertThat(postSigned("""
                {"events":[{"id":"e-unk","type":"RINGING","callId":"nao-existe","timestamp":"%s"}]}
                """.formatted(Instant.now()))).isEqualTo(200);
        assertThat(call(dialing.id()).status()).isEqualTo("DIALING");
    }
}
