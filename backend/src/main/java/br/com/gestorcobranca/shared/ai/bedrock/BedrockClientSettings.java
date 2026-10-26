package br.com.gestorcobranca.shared.ai.bedrock;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parâmetros do cliente {@code bedrock-runtime} de um produto de IA, com a <strong>guarda de transferência
 * internacional</strong>: nenhum modelo Claude processa no Brasil, então o cliente só é criado com a aprovação
 * explícita do responsável (LGPD art. 33). Sem credenciais: o SDK usa a cadeia padrão (papel IAM da instância
 * EC2). Os padrões de recurso só valem depois de {@link #validate(String, List)}.
 *
 * @param region                        região do endpoint chamado (o perfil de inferência decide onde o modelo
 *                                      processa — ver docs/tecnico/desenho/assistente.md §6.6)
 * @param internationalTransferApproved o responsável aprovou a transferência internacional para a região/perfil
 * @param endpointOverride              endpoint alternativo (VPC endpoint de interface); nulo = público da região
 * @param connectTimeout                conexão TCP/TLS com o endpoint
 * @param socketTimeout                 leitura do socket (teto técnico; o prazo de cada chamada vem do produto)
 * @param maxConnections                conexões simultâneas do cliente HTTP por instância
 * @param retryBackoff                  espera antes da única retentativa (limite de taxa/5xx)
 * @param currency                      moeda dos preços (ISO 4217)
 * @param cacheTtl                      validade do cache de prompt: {@code 5m} (padrão) ou {@code 1h}
 */
public record BedrockClientSettings(String region, boolean internationalTransferApproved, URI endpointOverride,
                                    Duration connectTimeout, Duration socketTimeout, Integer maxConnections,
                                    Duration retryBackoff, String currency, String cacheTtl) {

    private static final Pattern REGION = Pattern.compile("[a-z]{2}(-gov)?-[a-z]+-\\d");

    public BedrockClientSettings {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        socketTimeout = socketTimeout == null ? Duration.ofSeconds(65) : socketTimeout;
        maxConnections = maxConnections == null ? 50 : maxConnections;
        retryBackoff = retryBackoff == null ? Duration.ofMillis(400) : retryBackoff;
        currency = currency == null ? "USD" : currency.trim().toUpperCase(Locale.ROOT);
        cacheTtl = cacheTtl == null ? "5m" : cacheTtl.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @param prefix   prefixo das chaves na configuração (ex.: {@code app.assistant.bedrock})
     * @param problems acumula as mensagens
     */
    public void validate(String prefix, List<String> problems) {
        if (region == null || !REGION.matcher(region.trim()).matches()) {
            problems.add(prefix + ".region ausente ou inválida (ex.: us-east-1)");
        }
        if (!internationalTransferApproved) {
            problems.add(prefix + ".international-transfer-approved=false: nenhum modelo Claude processa no "
                    + "Brasil; o responsável precisa aprovar a transferência internacional (LGPD art. 33) para a região e o "
                    + "perfil configurados antes de ligar o provedor");
        }
        if (endpointOverride != null && !"https".equalsIgnoreCase(endpointOverride.getScheme())) {
            problems.add(prefix + ".endpoint-override precisa ser https");
        }
        positive(problems, prefix + ".connect-timeout", connectTimeout);
        positive(problems, prefix + ".socket-timeout", socketTimeout);
        if (retryBackoff.isNegative()) {
            problems.add(prefix + ".retry-backoff não pode ser negativo");
        }
        if (maxConnections < 1) {
            problems.add(prefix + ".max-connections deve ser positivo");
        }
        if (!currency.matches("[A-Z]{3}")) {
            problems.add(prefix + ".currency deve ser um código ISO 4217 (ex.: USD)");
        }
        if (!cacheTtl.equals("5m") && !cacheTtl.equals("1h")) {
            problems.add(prefix + ".cache-ttl deve ser 5m ou 1h");
        }
    }

    private static void positive(List<String> problems, String key, Duration value) {
        if (value.isNegative() || value.isZero()) {
            problems.add(key + " deve ser positivo");
        }
    }
}
