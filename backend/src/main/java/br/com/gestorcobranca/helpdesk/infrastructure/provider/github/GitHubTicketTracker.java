package br.com.gestorcobranca.helpdesk.infrastructure.provider.github;

import br.com.gestorcobranca.helpdesk.application.port.out.TicketTracker;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Chamados como issues pela API REST do GitHub ({@code /repos/{dono}/{repo}/issues}). Timeouts explícitos (conexão
 * 3 s, leitura 10 s) e nenhuma retentativa automática: a falha volta à pessoa, que pode confirmar de novo (a abertura
 * é idempotente pelo marcador). 429, 5xx, limite secundário (403 com {@code retry-after} ou cota zerada) e rede são
 * transitórios; 401/403 = token recusado; 404 = repositório inexistente ou fora do token; 410 = issues desligadas;
 * 422 = validação. Métricas {@code integration.calls{provider=github,operation,outcome}}. O token nunca vai para log.
 */
@Component
class GitHubTicketTracker implements TicketTracker {

    static final String PROVIDER = "github";
    private static final Logger LOG = LoggerFactory.getLogger(GitHubTicketTracker.class);

    private final GitHubProperties properties;
    private final RestClient client;
    private final JsonCodec json;
    private final MeterRegistry meters;

    @Autowired
    GitHubTicketTracker(GitHubProperties properties, JsonCodec json, MeterRegistry meters) {
        this(properties, json, meters, RestClient.builder().requestFactory(requestFactory(properties)));
    }

    /** @param builder cliente HTTP já configurado (timeouts; nos testes, o servidor dublê) */
    GitHubTicketTracker(GitHubProperties properties, JsonCodec json, MeterRegistry meters, RestClient.Builder builder) {
        List<String> problems = properties.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuração do GitHub dos chamados inválida: " + String.join("; ", problems));
        }
        this.properties = properties;
        this.json = json;
        this.meters = meters;
        this.client = builder.baseUrl(properties.apiBaseUrl().toString())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "gestor-cobranca-helpdesk")
                .build();
        LOG.atInfo().addKeyValue("provider", PROVIDER).addKeyValue("configured", properties.configured())
                .addKeyValue("repository", properties.repository())
                .addKeyValue("requirePrivateRepository", properties.requirePrivateRepository())
                .log(properties.configured() ? "Chamados de suporte no GitHub habilitados"
                        : "Chamados de suporte indisponíveis: token do GitHub não configurado");
    }

    private static JdkClientHttpRequestFactory requestFactory(GitHubProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    @Override
    public boolean configured() {
        return properties.configured();
    }

    @Override
    public String repository() {
        return properties.repository();
    }

    @Override
    public List<String> labels() {
        return properties.labels();
    }

    @Override
    public boolean requiresPrivateRepository() {
        return properties.requirePrivateRepository();
    }

    @Override
    public boolean repositoryIsPrivate() {
        return call("get_repository", () -> {
            String body = client.get().uri("/repos/" + properties.repository()).headers(this::auth).retrieve()
                    .body(String.class);
            return tree(body).path("private").asBoolean(false);
        });
    }

    @Override
    public Optional<CreatedIssue> findByMarker(String marker, Instant since) {
        return call("find_issue", () -> {
            String body = client.get().uri(b -> b.path("/repos/" + properties.repository() + "/issues")
                            .queryParam("state", "all").queryParam("labels", String.join(",", properties.labels()))
                            .queryParam("since", since.truncatedTo(ChronoUnit.SECONDS).toString())
                            .queryParam("per_page", 100).build())
                    .headers(this::auth).retrieve().body(String.class);
            JsonNode issues = tree(body);
            for (int i = 0; i < issues.size(); i++) {
                JsonNode issue = issues.get(i);
                if (issue.path("body").asString("").contains(marker)) {
                    return Optional.of(new CreatedIssue(issue.path("number").asLong(), issue.path("html_url").asString()));
                }
            }
            return Optional.<CreatedIssue>empty();
        });
    }

    @Override
    public CreatedIssue create(NewIssue issue) {
        return call("create_issue", () -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", issue.title());
            payload.put("body", issue.body());
            payload.put("labels", issue.labels());
            String body = client.post().uri("/repos/" + properties.repository() + "/issues").headers(this::auth)
                    .contentType(MediaType.APPLICATION_JSON).body(json.toJson(payload)).retrieve().body(String.class);
            JsonNode node = tree(body);
            long number = node.path("number").asLong(0);
            String url = node.path("html_url").asString("");
            if (number <= 0 || !url.startsWith("https://")) {
                throw new TrackerException("invalid_response", true, "Resposta do GitHub sem número da issue", null);
            }
            return new CreatedIssue(number, url);
        });
    }

    private void auth(HttpHeaders headers) {
        if (properties.token() == null) {
            throw new TrackerException("not_configured", false, "Token do GitHub não configurado", null);
        }
        headers.setBearerAuth(properties.token());
    }

    private JsonNode tree(String body) {
        return json.mapper().readTree(body == null || body.isBlank() ? "{}" : body);
    }

    private <T> T call(String operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        try {
            T result = action.get();
            outcome = "ok";
            return result;
        } catch (RestClientResponseException e) {
            TrackerException mapped = map(e);
            outcome = mapped.transientFailure() ? "transient" : "rejected";
            LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("operation", operation)
                    .addKeyValue("status", e.getStatusCode().value()).addKeyValue("errorCode", mapped.code())
                    .addKeyValue("githubRequestId", e.getResponseHeaders() == null ? null
                            : e.getResponseHeaders().getFirst("x-github-request-id"))
                    .log("GitHub recusou a chamada dos chamados de suporte");
            throw mapped;
        } catch (ResourceAccessException e) {
            outcome = "timeout";
            LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("operation", operation)
                    .log("Falha de comunicação com o GitHub");
            throw new TrackerException("unavailable", true, "Falha de comunicação com o GitHub", e);
        } catch (TrackerException e) {
            outcome = e.transientFailure() ? "transient" : "rejected";
            throw e;
        } finally {
            meters.counter("integration.calls", "provider", PROVIDER, "operation", operation, "outcome", outcome)
                    .increment();
            sample.stop(meters.timer("integration.calls.duration", "provider", PROVIDER, "operation", operation));
        }
    }

    static TrackerException map(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        HttpHeaders headers = e.getResponseHeaders();
        boolean secondaryLimit = status == 403 && headers != null && (headers.getFirst("retry-after") != null
                || "0".equals(headers.getFirst("x-ratelimit-remaining")));
        if (status == 429 || status >= 500 || secondaryLimit) {
            return new TrackerException("unavailable", true, "GitHub indisponível (HTTP " + status + ")", e);
        }
        return switch (status) {
            case 401, 403 -> new TrackerException("token_rejected", false, "Token do GitHub recusado", e);
            case 404 -> new TrackerException("repository_not_found", false, "Repositório não encontrado para o token", e);
            case 410 -> new TrackerException("issues_disabled", false, "Issues desligadas no repositório", e);
            case 422 -> new TrackerException("validation", false, "GitHub recusou os dados do chamado", e);
            default -> new TrackerException("rejected", false, "GitHub recusou a chamada (HTTP " + status + ")", e);
        };
    }
}
