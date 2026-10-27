package br.com.gestorcobranca.telephony.infrastructure.provider.alvaria;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.BridgeRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.DialRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.DialResult;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupReason;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.OriginateRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderContext;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderRejectedException;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.Readiness;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.RecordingMedia;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.TransientProviderException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adaptador da Alvaria contra um servidor HTTP local que imita o contrato provisório
 * ({@link AlvariaApiContract}). Nenhuma chamada real: valida a mecânica (token OAuth, cabeçalhos,
 * corpo, classificação de erros, timeouts, métricas e validação do endereço).
 */
class AlvariaTelephonyProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final Queue<Recorded> requests = new ConcurrentLinkedQueue<>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private volatile String tokenValue = "tok-1";
    private SimpleMeterRegistry registry;
    private AlvariaTelephonyProvider provider;

    private record Recorded(String method, String path, String authorization, String idempotencyKey, String body) {
    }

    private record Reply(int status, String body, String contentType, long delayMs) {

        static Reply json(int status, String body) {
            return new Reply(status, body, "application/json", 0);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/cx";
        registry = new SimpleMeterRegistry();
        provider = provider(true, List.of("127.0.0.1"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AlvariaTelephonyProvider provider(boolean insecure, List<String> hosts) {
        TelephonyProperties properties = new TelephonyProperties(null, null, null, null, null, null, null, null, null,
                null, null, null, new TelephonyProperties.Alvaria(Duration.ofMillis(1000), Duration.ofMillis(1500), hosts,
                insecure), null, null, null);
        return new AlvariaTelephonyProvider(properties, new JsonCodec(MAPPER), registry, Clock.systemUTC());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Reply reply;
        if (path.equals("/cx/oauth2/token")) {
            tokenRequests.incrementAndGet();
            reply = replies.getOrDefault("token", Reply.json(200,
                    "{\"access_token\":\"" + tokenValue + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"));
            requests.add(new Recorded(exchange.getRequestMethod(), path, null, null, body));
        } else {
            requests.add(new Recorded(exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"), body));
            reply = replies.getOrDefault(exchange.getRequestMethod() + " " + path, Reply.json(404, "{\"errorCode\":\"not_found\"}"));
        }
        if (reply.delayMs() > 0) {
            try {
                Thread.sleep(reply.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", reply.contentType());
        exchange.sendResponseHeaders(reply.status(), reply.status() == 204 ? -1 : out.length);
        if (reply.status() != 204) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        }
        exchange.close();
    }

    private ProviderContext context() {
        return new ProviderContext(UUID.randomUUID(), Map.of("apiBaseUrl", baseUrl, "clientId", "cli",
                "clientSecret", "sec", "webhookSecret", "wh"), "+551130000000");
    }

    private static OriginateRequest originate(ProviderContext ctx, UUID callId) {
        return new OriginateRequest(ctx, callId, "ramal-101", "+5511987654321", 30, true, true);
    }

    private static JsonNode json(String body) {
        return MAPPER.readTree(body);
    }

    private List<Recorded> apiRequests() {
        return requests.stream().filter(r -> !r.path().endsWith("/oauth2/token")).toList();
    }

    @Test
    void declaresPendingContract() {
        assertThat(provider.readiness()).isEqualTo(Readiness.CONTRACT_PENDING);
    }

    @Test
    void originateSendsTokenIdempotencyKeyAndReferenceAndReusesTheToken() {
        replies.put("POST /cx/api/v1/calls/agent-first", Reply.json(201, "{\"callId\":\"alv-1\"}"));
        ProviderContext ctx = context();
        UUID callId = UUID.randomUUID();

        DialResult first = provider.originate(originate(ctx, callId));
        DialResult second = provider.originate(originate(ctx, UUID.randomUUID()));

        assertThat(first.accepted()).isTrue();
        assertThat(first.providerCallId()).isEqualTo("alv-1");
        assertThat(second.accepted()).isTrue();
        assertThat(tokenRequests.get()).isEqualTo(1);
        Recorded token = requests.stream().filter(r -> r.path().endsWith("/oauth2/token")).findFirst().orElseThrow();
        assertThat(token.body()).contains("grant_type=client_credentials").contains("client_id=cli")
                .contains("client_secret=sec");
        Recorded call = apiRequests().getFirst();
        assertThat(call.authorization()).isEqualTo("Bearer tok-1");
        assertThat(call.idempotencyKey()).isEqualTo(callId.toString());
        JsonNode body = json(call.body());
        assertThat(body.path("clientReference").asString()).isEqualTo(callId.toString());
        assertThat(body.path("agentEndpoint").asString()).isEqualTo("ramal-101");
        assertThat(body.path("destination").asString()).isEqualTo("+5511987654321");
        assertThat(body.path("callerId").asString()).isEqualTo("+551130000000");
        assertThat(body.path("announceRecording").asBoolean()).isTrue();
        assertThat(registry.counter("integration.calls", "provider", "alvaria", "operation", "originate", "outcome",
                "success").count()).isEqualTo(2.0);
    }

    @Test
    void dialBatchMapsResultsByClientReference() {
        UUID ok = UUID.randomUUID();
        UUID refused = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        replies.put("POST /cx/api/v1/calls/batch", Reply.json(200, "{\"results\":["
                + "{\"clientReference\":\"" + refused + "\",\"accepted\":false,\"errorCode\":\"invalid-number\"},"
                + "{\"clientReference\":\"" + ok + "\",\"accepted\":true,\"callId\":\"alv-9\"}]}"));

        List<DialResult> results = provider.dialBatch(context(), List.of(
                new DialRequest(ok, "+5511911110001", 25, true, false, false),
                new DialRequest(refused, "+5511911110002", 25, true, false, false),
                new DialRequest(missing, "+5511911110003", 25, true, false, false)));

        assertThat(results).extracting(DialResult::callId).containsExactly(ok, refused, missing);
        assertThat(results.get(0).accepted()).isTrue();
        assertThat(results.get(0).providerCallId()).isEqualTo("alv-9");
        assertThat(results.get(1).errorCode()).isEqualTo("ALVARIA_INVALID_NUMBER");
        assertThat(results.get(2).errorCode()).isEqualTo("ALVARIA_NO_RESULT");
        JsonNode body = json(apiRequests().getFirst().body());
        assertThat(body.path("calls")).hasSize(3);
        assertThat(body.path("calls").path(0).path("answeringMachineDetection").asBoolean()).isTrue();
        assertThat(apiRequests().getFirst().idempotencyKey()).startsWith("batch-");
    }

    @Test
    void rateLimitAndUnavailableAreTransientWithoutCreatingTheCall() {
        replies.put("POST /cx/api/v1/calls/agent-first", Reply.json(503, "{}"));
        assertThatThrownBy(() -> provider.originate(originate(context(), UUID.randomUUID())))
                .isInstanceOf(TransientProviderException.class)
                .satisfies(e -> assertThat(((TransientProviderException) e).notProcessed()).isTrue());
        replies.put("POST /cx/api/v1/calls/agent-first", Reply.json(429, "{}"));
        assertThatThrownBy(() -> provider.originate(originate(context(), UUID.randomUUID())))
                .satisfies(e -> assertThat(((TransientProviderException) e).notProcessed()).isTrue());
    }

    @Test
    void otherServerErrorsAndReadTimeoutAreAmbiguous() {
        replies.put("POST /cx/api/v1/calls/agent-first", Reply.json(500, "{}"));
        assertThatThrownBy(() -> provider.originate(originate(context(), UUID.randomUUID())))
                .isInstanceOf(TransientProviderException.class)
                .satisfies(e -> assertThat(((TransientProviderException) e).notProcessed()).isFalse());
        replies.put("POST /cx/api/v1/calls/agent-first", new Reply(201, "{\"callId\":\"tarde\"}", "application/json", 3000));
        assertThatThrownBy(() -> provider.originate(originate(context(), UUID.randomUUID())))
                .isInstanceOf(TransientProviderException.class)
                .satisfies(e -> assertThat(((TransientProviderException) e).notProcessed()).isFalse());
        assertThat(registry.counter("integration.calls", "provider", "alvaria", "operation", "originate", "outcome",
                "timeout").count()).isEqualTo(1.0);
    }

    @Test
    void connectionRefusedIsNotProcessed() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ProviderContext ctx = new ProviderContext(UUID.randomUUID(), Map.of("apiBaseUrl", "http://127.0.0.1:" + closedPort,
                "clientId", "cli", "clientSecret", "sec"), "+551130000000");
        assertThatThrownBy(() -> provider.originate(originate(ctx, UUID.randomUUID())))
                .isInstanceOf(TransientProviderException.class)
                .satisfies(e -> assertThat(((TransientProviderException) e).notProcessed()).isTrue());
    }

    @Test
    void clientErrorsAreRejectionsAndUnauthorizedRenewsTheTokenOnce() {
        replies.put("POST /cx/api/v1/calls/agent-first", Reply.json(422, "{\"errorCode\":\"agent.offline\"}"));
        DialResult refused = provider.originate(originate(context(), UUID.randomUUID()));
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.errorCode()).isEqualTo("ALVARIA_AGENT_OFFLINE");

        ProviderContext ctx = context();
        replies.put("POST /cx/api/v1/calls/agent-first", Reply.json(201, "{\"callId\":\"alv-2\"}"));
        provider.originate(originate(ctx, UUID.randomUUID()));
        int before = tokenRequests.get();
        replies.put("POST /cx/api/v1/calls/agent-first", Reply.json(401, "{}"));
        tokenValue = "tok-2";
        DialResult afterRevocation = provider.originate(originate(ctx, UUID.randomUUID()));
        assertThat(tokenRequests.get()).isEqualTo(before + 1);
        assertThat(afterRevocation.errorCode()).isEqualTo("ALVARIA_UNKNOWN");
        assertThat(apiRequests().getLast().authorization()).isEqualTo("Bearer tok-2");
    }

    @Test
    void refusedCredentialsRejectTheDialAndBridgeRefusalIsDefinitive() {
        replies.put("token", Reply.json(401, "{\"error\":\"invalid_client\"}"));
        DialResult result = provider.originate(originate(context(), UUID.randomUUID()));
        assertThat(result.errorCode()).isEqualTo("ALVARIA_AUTH_FAILED");

        replies.remove("token");
        replies.put("POST /cx/api/v1/calls/alv-3/bridge", Reply.json(409, "{\"errorCode\":\"call_ended\"}"));
        assertThatThrownBy(() -> provider.bridge(new BridgeRequest(context(), UUID.randomUUID(), "alv-3", "ramal-101")))
                .isInstanceOf(ProviderRejectedException.class)
                .hasFieldOrPropertyWithValue("code", "ALVARIA_CALL_ENDED");
    }

    @Test
    void hangupSendsTheReasonAndSkipsCallsNeverAccepted() {
        replies.put("POST /cx/api/v1/calls/alv-4/hangup", Reply.json(204, ""));
        provider.hangup(new HangupRequest(context(), UUID.randomUUID(), "alv-4", HangupReason.ABANDONED));
        provider.hangup(new HangupRequest(context(), UUID.randomUUID(), null, HangupReason.CANCELLED));

        assertThat(apiRequests()).hasSize(1);
        assertThat(json(apiRequests().getFirst().body()).path("reason").asString()).isEqualTo("ABANDONED_WITH_MESSAGE");
    }

    @Test
    void recordingIsStreamedAndDeleted() throws IOException {
        replies.put("GET /cx/api/v1/recordings/rec 1", new Reply(200, "RIFF-audio", "audio/wav", 0));
        replies.put("DELETE /cx/api/v1/recordings/rec 1", Reply.json(204, ""));
        ProviderContext ctx = context();

        RecordingMedia media = provider.fetchRecording(ctx, "rec 1");
        try (InputStream in = media.content()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("RIFF-audio");
        }
        assertThat(media.contentType()).isEqualTo("audio/wav");
        provider.deleteRecording(ctx, "rec 1");
        assertThat(apiRequests()).extracting(Recorded::method).containsExactly("GET", "DELETE");

        assertThatThrownBy(() -> provider.fetchRecording(ctx, "inexistente")).isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void baseUrlMustBeHttpsOnAnAllowedHost() {
        AlvariaTelephonyProvider strict = provider(false, List.of(".alvaria.com"));
        strict.validateCredentials(Map.of("apiBaseUrl", "https://assessoria.cx.alvaria.com/api"));
        assertThatThrownBy(() -> strict.validateCredentials(Map.of("apiBaseUrl", "http://assessoria.alvaria.com")))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "telephony_account.alvaria_url_insecure");
        assertThatThrownBy(() -> strict.validateCredentials(Map.of("apiBaseUrl", "https://alvaria.com.evil.test")))
                .hasFieldOrPropertyWithValue("code", "telephony_account.alvaria_host_not_allowed");
        assertThatThrownBy(() -> strict.validateCredentials(Map.of("apiBaseUrl", "https://169.254.169.254/latest")))
                .hasFieldOrPropertyWithValue("code", "telephony_account.alvaria_host_not_allowed");
        assertThatThrownBy(() -> strict.validateCredentials(Map.of("apiBaseUrl", "https://user:pw@x.alvaria.com")))
                .hasFieldOrPropertyWithValue("code", "telephony_account.alvaria_url_invalid");
        assertThatThrownBy(() -> strict.validateCredentials(Map.of("apiBaseUrl", "https://.alvaria.com")))
                .isInstanceOf(BusinessRuleException.class);
    }
}
