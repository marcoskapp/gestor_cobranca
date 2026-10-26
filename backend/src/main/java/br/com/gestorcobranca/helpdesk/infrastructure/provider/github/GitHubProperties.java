package br.com.gestorcobranca.helpdesk.infrastructure.provider.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * GitHub dos chamados de suporte ({@code app.helpdesk.github.*}). O token chega por variável de ambiente
 * ({@code HELPDESK_GITHUB_TOKEN}), lida pela inicialização da instância do segredo
 * {@code /gestor-cobranca/<ambiente>/app/helpdesk-github-token} (Parameter Store, ou o bucket de segredos enquanto o
 * SSM não for aprovado) — nunca em arquivo versionado. Token de permissão mínima: <em>fine-grained</em>, só o
 * repositório configurado, {@code Issues: read and write} (e o {@code Metadata: read} obrigatório).
 *
 * @param repository               {@code dono/repositório} (ex.: {@code marcoskapp/gestor_cobranca})
 * @param token                    token do GitHub; vazio = abertura de chamados indisponível
 * @param apiBaseUrl               API REST (https; {@code http} só em endereço local, para testes)
 * @param labels                   rótulos de toda issue aberta
 * @param requirePrivateRepository trava: só abre se o repositório estiver privado (desligada até a migração)
 */
@ConfigurationProperties(prefix = "app.helpdesk.github")
public record GitHubProperties(String repository, String token, URI apiBaseUrl, List<String> labels,
                               boolean requirePrivateRepository, Duration connectTimeout, Duration readTimeout) {

    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}");
    private static final Pattern LABEL = Pattern.compile("[\\p{L}\\d _.:-]{1,50}");

    public GitHubProperties {
        repository = repository == null ? null : repository.trim();
        token = token == null || token.isBlank() ? null : token.trim();
        apiBaseUrl = apiBaseUrl == null ? URI.create("https://api.github.com") : apiBaseUrl;
        labels = labels == null || labels.isEmpty() ? List.of("suporte") : List.copyOf(labels);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }

    public boolean configured() {
        return token != null && repository != null && REPOSITORY.matcher(repository).matches();
    }

    /** @return problemas de configuração (vazio = válida); só exigidos com token presente */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (token == null) {
            return problems;
        }
        if (repository == null || !REPOSITORY.matcher(repository).matches()) {
            problems.add("app.helpdesk.github.repository deve ser dono/repositório");
        }
        boolean local = "localhost".equals(apiBaseUrl.getHost()) || "127.0.0.1".equals(apiBaseUrl.getHost());
        if (!"https".equalsIgnoreCase(apiBaseUrl.getScheme()) && !local) {
            problems.add("app.helpdesk.github.api-base-url precisa ser https");
        }
        if (labels.stream().anyMatch(l -> l == null || !LABEL.matcher(l).matches())) {
            problems.add("app.helpdesk.github.labels inválidos");
        }
        if (token.chars().anyMatch(Character::isWhitespace)) {
            problems.add("app.helpdesk.github.token inválido");
        }
        return problems;
    }
}
