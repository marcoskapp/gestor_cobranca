package br.com.gestorcobranca.sandbox.infrastructure.mail;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.application.SandboxProperties;
import br.com.gestorcobranca.sandbox.application.port.out.ActivationMailbox;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê os links de ativação na API HTTP do Mailpit (captura de e-mails do sandbox). O token vem do fragmento
 * {@code #token=} do link, como o navegador do usuário o leria.
 */
@SandboxSeedComponent
class MailpitActivationMailbox implements ActivationMailbox {

    private static final Logger LOG = LoggerFactory.getLogger(MailpitActivationMailbox.class);
    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_\\-.]+)");
    private static final Duration POLL = Duration.ofMillis(500);
    /** Tolerância entre o relógio da aplicação e o carimbo do Mailpit. */
    private static final Duration SKEW = Duration.ofSeconds(5);

    private final RestClient client;
    private final JsonCodec json;

    MailpitActivationMailbox(SandboxProperties properties, JsonCodec json) {
        if (properties.seed().mailpitApiUrl() == null) {
            throw new IllegalStateException("app.sandbox.seed.mailpit-api-url é obrigatório com a carga do sandbox ligada");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl(properties.seed().mailpitApiUrl().toString()).requestFactory(factory)
                .build();
        this.json = json;
    }

    @Override
    public String awaitCredentialToken(String email, Instant sentAfter, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            Optional<String> token = latestToken(email, sentAfter.minus(SKEW));
            if (token.isPresent()) {
                return token.get();
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("e-mail de ativação não chegou ao Mailpit no prazo (usuário de demonstração)");
            }
            sleep();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<String> latestToken(String email, Instant notBefore) {
        try {
            String uri = UriComponentsBuilder.fromPath("/api/v1/search").queryParam("query", "to:\"" + email + "\"")
                    .queryParam("limit", 20).build().toUriString();
            Map<String, Object> result = json.toMap(client.get().uri(uri).retrieve().body(String.class));
            List<Map<String, Object>> messages = (List<Map<String, Object>>) result.getOrDefault("messages", List.of());
            Optional<Map<String, Object>> newest = messages.stream()
                    .filter(m -> !Instant.parse(String.valueOf(m.get("Created"))).isBefore(notBefore))
                    .max(Comparator.comparing(m -> Instant.parse(String.valueOf(m.get("Created")))));
            if (newest.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> message = json.toMap(client.get().uri("/api/v1/message/{id}", newest.get().get("ID"))
                    .retrieve().body(String.class));
            Matcher matcher = TOKEN.matcher(String.valueOf(message.get("Text")) + " " + message.get("HTML"));
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        } catch (RestClientException e) {
            LOG.atWarn().addKeyValue("provider", "mailpit").log("Falha ao consultar o Mailpit; nova tentativa em seguida");
            return Optional.empty();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("carga interrompida aguardando o e-mail de ativação", e);
        }
    }
}
