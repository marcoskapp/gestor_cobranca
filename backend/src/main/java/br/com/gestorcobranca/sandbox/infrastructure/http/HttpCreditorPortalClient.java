package br.com.gestorcobranca.sandbox.infrastructure.http;

import br.com.gestorcobranca.sandbox.application.SandboxProperties;
import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.application.port.out.CreditorPortalClient;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cliente HTTP da própria API do portal do credor ({@code /api/creditor/v1}), no endereço interno da aplicação
 * ({@code app.sandbox.seed.creditor-portal-url}), com a {@code Origin} desse endereço (a mesma origem da aplicação: o
 * servidor exige Origin nas rotas de autenticação, RS-20) e o token da sessão real no {@code Authorization}. Respostas
 * 429 esperam o {@code Retry-After} (até 3 novas tentativas, cada uma com um código novo do fornecedor). Erros viram
 * {@link CallFailedException} só com a situação HTTP e o código do problema; o log técnico nunca recebe token, código,
 * segredo, link nem e-mail.
 */
@SandboxSeedComponent
class HttpCreditorPortalClient implements CreditorPortalClient {

    private static final Logger LOG = LoggerFactory.getLogger(HttpCreditorPortalClient.class);
    private static final String API = "/api/creditor/v1";
    private static final int MAX_RETRIES = 3;
    private static final Duration DEFAULT_RETRY = Duration.ofSeconds(61); // 429 sem Retry-After: a janela do limite é de um minuto
    private static final Duration MAX_RETRY = Duration.ofMinutes(2);
    private static final String USER_AGENT = "gestor-cobranca-sandbox-seed";

    private final RestClient client;
    private final String origin;
    private final JsonCodec json;

    HttpCreditorPortalClient(SandboxProperties properties, JsonCodec json) {
        if (properties.seed().creditorPortalUrl() == null) {
            throw new IllegalStateException("app.sandbox.seed.creditor-portal-url é obrigatório com a carga do sandbox ligada");
        }
        String base = requireLocal(properties.seed().creditorPortalUrl()).toString().replaceAll("/+$", "");
        // HttpClient do JDK: o HttpURLConnection descarta o cabeçalho Origin (restrito), que o portal exige
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .proxy(java.net.http.HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.client = RestClient.builder().baseUrl(base).requestFactory(new BufferingClientHttpRequestFactory(factory))
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                // a resposta de erro é lida aqui (situação e código), sem exceção do RestClient com o corpo
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
        this.origin = base;
        this.json = json;
    }

    /** Hosts aceitos: a carga manda senha, código e token de sessão só para a própria aplicação. */
    private static final java.util.Set<String> LOCAL_HOSTS = java.util.Set.of("localhost", "127.0.0.1", "[::1]", "::1", "app");

    /**
     * Recusa endereço fora da própria aplicação (loopback ou o serviço {@code app} do Compose): a carga envia a senha de
     * demonstração, o código do autenticador e o token de sessão para esse endereço.
     */
    static java.net.URI requireLocal(java.net.URI url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || !LOCAL_HOSTS.contains(host) || url.getUserInfo() != null) {
            throw new IllegalStateException(
                    "app.sandbox.seed.creditor-portal-url deve apontar para a própria aplicação (localhost ou app)");
        }
        return url;
    }

    // ------------------------------------------------------------------------------------------- autenticação

    @Override
    public Enrollment startActivation(String invitationToken) {
        Map<String, Object> body = postJson("activation/start", API + "/auth/activation/start", null,
                () -> Map.of("token", invitationToken));
        Object secret = body.get("secret");
        if (!(secret instanceof String s) || s.isBlank()) {
            throw new CallFailedException("activation/start", 200, "secret_missing");
        }
        return new Enrollment(s);
    }

    @Override
    public Session completeActivation(String invitationToken, String password, Supplier<String> code) {
        return session("activation/complete", postJson("activation/complete", API + "/auth/activation/complete", null,
                () -> Map.of("token", invitationToken, "newPassword", password, "code", code.get())));
    }

    @Override
    public Session login(String tenant, String email, String password, Supplier<String> code) {
        Map<String, Object> first = postJson("login", API + "/auth/login", null,
                () -> Map.of("tenant", tenant, "email", email, "password", password));
        if (!"MFA_REQUIRED".equals(first.get("status")) || first.get("accessToken") != null) {
            // MFA sempre obrigatório para a pessoa do credor (RS-85): qualquer outra resposta é defeito
            throw new CallFailedException("login", 200, "mfa_not_required");
        }
        String step = String.valueOf(first.get("stepToken"));
        return session("mfa", postJson("mfa", API + "/auth/mfa", null, () -> Map.of("stepToken", step, "code", code.get())));
    }

    @Override
    public void logout(Session session) {
        exchange("logout", () -> client.post().uri(API + "/session/logout").headers(h -> auth(h, session))
                .retrieve().toEntity(String.class));
    }

    // ------------------------------------------------------------------------------------------- leituras

    @Override
    public List<Item> statements(Session session) {
        return items(getList("statements", API + "/statements", session));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> statementFileHashes(Session session, UUID statementId) {
        Map<String, Object> detail = getMap("statement", API + "/statements/" + statementId, session);
        List<String> hashes = new ArrayList<>();
        for (Object f : (List<Object>) detail.getOrDefault("files", List.of())) {
            hashes.add(String.valueOf(((Map<String, Object>) f).get("sha256")));
        }
        return hashes;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<UUID> awaitingRequest(Session session, UUID disputeId) {
        Map<String, Object> detail = getMap("dispute", API + "/disputes/" + disputeId, session);
        return ((List<Object>) detail.getOrDefault("requests", List.of())).stream()
                .map(r -> (Map<String, Object>) r).filter(r -> Boolean.TRUE.equals(r.get("awaiting")))
                .map(r -> UUID.fromString(String.valueOf(r.get("id")))).findFirst();
    }

    @Override
    public List<Item> reportedPayments(Session session) {
        return items(getList("payments", API + "/payments", session));
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean titleAvailable(Session session, String titleNumber) {
        String uri = UriComponentsBuilder.fromPath(API + "/payments/titles").queryParam("number", titleNumber)
                .build().toUriString();
        return getList("payment titles", uri, session).stream().map(t -> (Map<String, Object>) t)
                .anyMatch(t -> !Boolean.TRUE.equals(t.get("underAgreement")));
    }

    // ------------------------------------------------------------------------------------------- ações

    @Override
    public void acknowledgeStatement(Session session, UUID statementId, String note, List<String> fileSha256,
                                     Supplier<String> code) {
        postJson("acknowledge", API + "/statements/" + statementId + "/acknowledge", session, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", code.get());
            body.put("note", note);
            body.put("fileSha256", fileSha256);
            return body;
        });
    }

    @Override
    public void respondDispute(Session session, UUID disputeId, UUID requestId, String position, String note,
                               Document document) {
        exchange("dispute response", () -> {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("response", jsonPart(Map.of("requestId", requestId.toString(), "position", position, "note", note)));
            parts.add("files", filePart(document));
            return client.post().uri(API + "/disputes/" + disputeId + "/responses").headers(h -> auth(h, session))
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().toEntity(String.class);
        });
    }

    @Override
    public UUID reportPayment(Session session, PaymentReport report, Document evidence, String idempotencyKey,
                              Supplier<String> code) {
        ResponseEntity<String> response = exchange("payment report", () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("titleNumbers", report.titleNumbers());
            body.put("amount", report.amount());
            body.put("paidOn", report.paidOn().toString());
            body.put("method", report.method());
            body.put("reference", report.reference());
            body.put("note", report.note());
            body.put("code", code.get());
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("report", jsonPart(body));
            parts.add("evidence", filePart(evidence));
            return client.post().uri(API + "/payments").headers(h -> {
                auth(h, session);
                h.set("Idempotency-Key", idempotencyKey);
            }).contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().toEntity(String.class);
        });
        return UUID.fromString(String.valueOf(json.toMap(response.getBody()).get("id")));
    }

    // ------------------------------------------------------------------------------------------- apoio

    private Session session(String operation, Map<String, Object> body) {
        Object token = body.get("accessToken");
        if (!"AUTHENTICATED".equals(body.get("status")) || !(token instanceof String t) || t.isBlank()) {
            throw new CallFailedException(operation, 200, "not_authenticated");
        }
        return new Session(t);
    }

    private Map<String, Object> postJson(String operation, String uri, Session session,
                                         Supplier<Map<String, Object>> body) {
        ResponseEntity<String> response = exchange(operation, () -> client.post().uri(uri).headers(h -> {
            h.set(HttpHeaders.ORIGIN, origin);
            if (session != null) {
                auth(h, session);
            }
        }).contentType(MediaType.APPLICATION_JSON).body(json.toJson(body.get())).retrieve().toEntity(String.class));
        String text = response.getBody();
        return text == null || text.isBlank() ? Map.of() : json.toMap(text);
    }

    private Map<String, Object> getMap(String operation, String uri, Session session) {
        return json.toMap(get(operation, uri, session));
    }

    private List<Object> getList(String operation, String uri, Session session) {
        return json.fromJson(get(operation, uri, session), new tools.jackson.core.type.TypeReference<List<Object>>() { });
    }

    private String get(String operation, String uri, Session session) {
        return exchange(operation, () -> client.get().uri(uri).headers(h -> auth(h, session)).retrieve()
                .toEntity(String.class)).getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Item> items(List<Object> list) {
        return list.stream().map(o -> (Map<String, Object>) o)
                .map(m -> new Item(UUID.fromString(String.valueOf(m.get("id"))), String.valueOf(m.get("status")))).toList();
    }

    /**
     * Executa a chamada; 429 espera o {@code Retry-After} e repete (a requisição é remontada: um código novo a cada
     * tentativa); outra situação de erro vira {@link CallFailedException}.
     */
    private ResponseEntity<String> exchange(String operation, Supplier<ResponseEntity<String>> call) {
        for (int attempt = 0; ; attempt++) {
            ResponseEntity<String> response = call.get();
            int status = response.getStatusCode().value();
            if (status == 429 && attempt < MAX_RETRIES) {
                Duration wait = retryAfter(response);
                LOG.atInfo().addKeyValue("operation", operation).addKeyValue("waitSeconds", wait.toSeconds())
                        .log("Portal do credor pediu para esperar (429); nova tentativa da carga do sandbox");
                sleep(wait);
                continue;
            }
            if (response.getStatusCode().isError()) {
                throw new CallFailedException(operation, status, problemCode(response.getBody()));
            }
            return response;
        }
    }

    private String problemCode(String body) {
        try {
            Object code = body == null || !body.startsWith("{") ? null : json.toMap(body).get("code");
            return code == null ? null : String.valueOf(code);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Duration retryAfter(ResponseEntity<String> response) {
        String header = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        try {
            Duration d = header == null ? DEFAULT_RETRY : Duration.ofSeconds(Long.parseLong(header.trim()) + 1);
            return d.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : d;
        } catch (NumberFormatException e) {
            return DEFAULT_RETRY;
        }
    }

    private static void auth(HttpHeaders headers, Session session) {
        headers.setBearerAuth(session.accessToken());
    }

    private HttpEntity<String> jsonPart(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json.toJson(body), headers);
    }

    private static HttpEntity<ByteArrayResource> filePart(Document document) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(document.contentType()));
        // o nome da parte e o do arquivo vão no Content-Disposition montado pelo conversor multipart
        return new HttpEntity<>(new ByteArrayResource(document.content()) {
            @Override
            public String getFilename() {
                return document.fileName();
            }
        }, headers);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("carga interrompida aguardando o portal do credor", e);
        }
    }
}
