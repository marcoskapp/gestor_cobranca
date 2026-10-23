package br.com.gestorcobranca.payment.infrastructure.provider.pix;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.ChargeRequest;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.ChargeResult;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.Payer;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.PixConnection;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.PixTransaction;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.ProviderException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Contrato da API Pix do BACEN, sem rede: servidor simulado do Spring ligado ao {@code RestClient}. */
class BacenPixProviderTest {

    private static final String BASE = "https://psp.example/api/v2";
    private static final String TOKEN = "https://psp.example/oauth/token";

    private MockRestServiceServer server;
    private BacenPixProvider provider;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PixConnection connection = new PixConnection(UUID.randomUUID(), BASE, TOKEN, "chave@psp.example",
            Map.of("clientId", "id", "clientSecret", "segredo"), null, Instant.parse("2026-10-01T00:00:00Z"));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        provider = new BacenPixProvider(c -> client, new PaymentProperties(null, null, null, null, null, null, null),
                new JsonCodec(JsonMapper.builder().build()), meters, Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"),
                ZoneOffset.UTC));
    }

    private void expectToken() {
        server.expect(requestTo(TOKEN)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic aWQ6c2VncmVkbw=="))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=client_credentials")))
                .andRespond(withSuccess("{\"access_token\":\"tok\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
    }

    @Test
    void immediateChargeIsCreatedWithOAuthTokenReusedFromCache() {
        expectToken();
        server.expect(requestTo(BASE + "/cob/" + "a".repeat(32))).andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(jsonPath("$.calendario.expiracao").value(3600))
                .andExpect(jsonPath("$.valor.original").value("123.40"))
                .andExpect(jsonPath("$.chave").value("chave@psp.example"))
                .andExpect(jsonPath("$.devedor").doesNotExist())
                .andRespond(withSuccess("""
                        {"txid":"%s","revisao":0,"loc":{"id":7,"location":"psp.example/qr/v2/abc"},
                         "status":"ATIVA","pixCopiaECola":"00020101021226..."}""".formatted("a".repeat(32)),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/cob/" + "a".repeat(32))).andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(jsonPath("$.status").value("REMOVIDA_PELO_USUARIO_RECEBEDOR"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        ChargeResult result = provider.createCharge(connection, new ChargeRequest("a".repeat(32), false,
                new BigDecimal("123.4"), 3600L, null, null, null, "Parcela 1 do acordo"));
        assertThat(result.copyPaste()).startsWith("000201");
        assertThat(result.location()).isEqualTo("psp.example/qr/v2/abc");
        assertThat(result.revision()).isZero();
        provider.cancelCharge(connection, "a".repeat(32), false);
        server.verify();
        assertThat(meters.counter("integration.calls", "provider", "pix_bacen", "operation", "create_cob", "outcome",
                "success").count()).isEqualTo(1);
    }

    @Test
    void dueChargeCarriesThePayer() {
        expectToken();
        server.expect(requestTo(BASE + "/cobv/" + "b".repeat(32))).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.calendario.dataDeVencimento").value("2026-11-10"))
                .andExpect(jsonPath("$.calendario.validadeAposVencimento").value(30))
                .andExpect(jsonPath("$.devedor.cpf").value("52998224725"))
                .andExpect(jsonPath("$.devedor.nome").value("Maria"))
                .andRespond(withSuccess("{\"txid\":\"x\",\"revisao\":1,\"pixCopiaECola\":\"000201X\"}",
                        MediaType.APPLICATION_JSON));
        provider.createCharge(connection, new ChargeRequest("b".repeat(32), true, new BigDecimal("10.00"), null,
                LocalDate.of(2026, 11, 10), 30, new Payer("52998224725", "Maria"), null));
        server.verify();
    }

    @Test
    void findPixParsesRefundsAndNotFoundIsEmpty() {
        expectToken();
        server.expect(requestTo(BASE + "/pix/E1")).andRespond(withSuccess("""
                {"endToEndId":"E1","txid":"t1","valor":"50.00","horario":"2026-10-23T10:00:00.000Z",
                 "devolucoes":[{"id":"D1","rtrId":"D1234","valor":"10.00","status":"DEVOLVIDO",
                   "horario":{"solicitacao":"2026-10-23T11:00:00Z","liquidacao":"2026-10-23T11:00:05Z"}}]}""",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/pix/E2")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        Optional<PixTransaction> tx = provider.findPix(connection, "E1");
        assertThat(tx).hasValueSatisfying(t -> {
            assertThat(t.amount()).isEqualByComparingTo("50.00");
            assertThat(t.paidAt()).isEqualTo(Instant.parse("2026-10-23T10:00:00Z"));
            assertThat(t.refunds()).singleElement().satisfies(r -> {
                assertThat(r.status()).isEqualTo("DEVOLVIDO");
                assertThat(r.at()).isEqualTo(Instant.parse("2026-10-23T11:00:05Z"));
            });
        });
        assertThat(provider.findPix(connection, "E2")).isEmpty();
        server.verify();
    }

    @Test
    void errorsAreTransientOrPermanentWithTheProviderCode() {
        expectToken();
        server.expect(requestTo(BASE + "/cob/" + "c".repeat(32))).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"type\":\"https://pix.bcb.gov.br/api/v2/error/CobOperacaoInvalida\",\"status\":400}"));
        server.expect(requestTo(BASE + "/cob/" + "c".repeat(32))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        ChargeRequest request = new ChargeRequest("c".repeat(32), false, BigDecimal.ONE, 300L, null, null, null, null);
        assertThatThrownBy(() -> provider.createCharge(connection, request)).isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    assertThat(((ProviderException) e).code()).isEqualTo("PIX_CobOperacaoInvalida");
                    assertThat(((ProviderException) e).isTransientFailure()).isFalse();
                });
        assertThatThrownBy(() -> provider.createCharge(connection, request)).isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).isTransientFailure()).isTrue());
        server.verify();
    }

    @Test
    void mutualTlsContextLoadsTheClientCertificate() throws Exception {
        byte[] p12;
        try (InputStream in = getClass().getResourceAsStream("/payment/psp-client-test.p12")) {
            p12 = in.readAllBytes();
        }
        SSLContext context = MutualTlsClientFactory.sslContext(p12, "test-only-pass");
        assertThat(context.getProtocol()).isEqualTo("TLS");
        assertThatThrownBy(() -> MutualTlsClientFactory.sslContext(p12, "errada")).isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> new MutualTlsClientFactory(new PaymentProperties(null, null, null, null, null, null, null))
                .client(connection)).isInstanceOf(ProviderException.class);
    }
}
