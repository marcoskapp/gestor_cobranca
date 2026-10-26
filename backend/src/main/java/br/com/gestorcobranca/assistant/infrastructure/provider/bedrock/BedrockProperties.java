package br.com.gestorcobranca.assistant.infrastructure.provider.bedrock;

import br.com.gestorcobranca.assistant.domain.model.ModelTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parâmetros do adaptador Amazon Bedrock ({@code app.assistant.bedrock.*}). Sem credenciais: o SDK usa a
 * cadeia padrão (papel IAM da instância EC2). Os preços ficam aqui, nunca no código, e precisam ser conferidos
 * na conta (no Bedrock podem diferir da API da Anthropic, inclusive pelo tipo de perfil de inferência). Os
 * padrões de recurso só valem depois de {@link #validate()} (chamado na subida por {@code BedrockConfiguration}).
 *
 * @param enabled                       liga o adaptador (exclusivo com {@code app.assistant.sandbox.enabled})
 * @param region                        região do endpoint {@code bedrock-runtime} chamado (o perfil de inferência
 *                                      decide onde o modelo processa — ver docs/tecnico/desenho/assistente.md §6.5)
 * @param internationalTransferApproved confirmação de que o responsável aprovou a transferência internacional
 *                                      dos dados do turno (LGPD art. 33) para a região/perfil configurados: nenhum
 *                                      modelo Claude processa no Brasil, então sem ela o adaptador não sobe
 * @param endpointOverride              endpoint alternativo (VPC endpoint de interface); nulo = público da região
 * @param connectTimeout                conexão TCP/TLS com o endpoint
 * @param socketTimeout                 leitura do socket (teto técnico; o prazo de cada chamada vem da política)
 * @param maxConnections                conexões simultâneas do cliente HTTP por instância
 * @param retryBackoff                  espera antes da única retentativa (limite de taxa/5xx)
 * @param currency                      moeda dos preços (ISO 4217)
 * @param cacheTtl                      validade do cache de prompt: {@code 5m} (padrão) ou {@code 1h} — o preço de
 *                                      gravação configurado precisa corresponder
 * @param standard                      nível {@link ModelTier#STANDARD}
 * @param advanced                      nível {@link ModelTier#ADVANCED}
 */
@ConfigurationProperties(prefix = "app.assistant.bedrock")
public record BedrockProperties(boolean enabled, String region, boolean internationalTransferApproved,
                                URI endpointOverride, Duration connectTimeout, Duration socketTimeout,
                                Integer maxConnections, Duration retryBackoff, String currency, String cacheTtl,
                                Tier standard, Tier advanced) {

    /** Ids aceitos pelo Bedrock: base ({@code anthropic.x}), perfil de sistema ({@code us.anthropic.x}) ou ARN. */
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{2,199}");
    private static final Pattern REGION = Pattern.compile("[a-z]{2}(-gov)?-[a-z]+-\\d");

    public BedrockProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        socketTimeout = socketTimeout == null ? Duration.ofSeconds(65) : socketTimeout;
        maxConnections = maxConnections == null ? 50 : maxConnections;
        retryBackoff = retryBackoff == null ? Duration.ofMillis(400) : retryBackoff;
        currency = currency == null ? "USD" : currency.trim().toUpperCase(Locale.ROOT);
        cacheTtl = cacheTtl == null ? "5m" : cacheTtl.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @param modelId       modelo base, perfil de inferência de sistema (ex.: {@code us.anthropic.claude-haiku-5-5})
     *                      ou ARN de perfil de aplicação
     * @param promptCaching envia {@code cachePoint} depois das ferramentas e do prompt (padrão sim)
     * @param pricing       preços por milhão de tokens
     */
    public record Tier(String modelId, Boolean promptCaching, Pricing pricing) {

        public boolean caching() {
            return promptCaching == null || promptCaching;
        }
    }

    /**
     * Preços por milhão de tokens na moeda configurada. Contexto longo: quando o prompt da chamada (entrada com
     * e sem cache) passa de {@code longContextThreshold} tokens, todos os preços da chamada passam aos
     * {@code long*} (ex.: Claude Haiku 5.5 acima de 100 mil tokens). Sem limiar, preço único.
     */
    public record Pricing(BigDecimal inputPerMillion, BigDecimal outputPerMillion, BigDecimal cacheReadPerMillion,
                          BigDecimal cacheWritePerMillion, Long longContextThreshold, BigDecimal longInputPerMillion,
                          BigDecimal longOutputPerMillion, BigDecimal longCacheReadPerMillion,
                          BigDecimal longCacheWritePerMillion) {
    }

    public Tier tier(ModelTier tier) {
        return tier == ModelTier.ADVANCED ? advanced : standard;
    }

    /** @return problemas de configuração (vazio = válida) */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (region == null || !REGION.matcher(region.trim()).matches()) {
            problems.add("app.assistant.bedrock.region ausente ou inválida (ex.: us-east-1)");
        }
        if (!internationalTransferApproved) {
            problems.add("app.assistant.bedrock.international-transfer-approved=false: nenhum modelo Claude processa no "
                    + "Brasil; o responsável precisa aprovar a transferência internacional (LGPD art. 33) para a região e o "
                    + "perfil configurados antes de ligar o provedor");
        }
        if (endpointOverride != null && !"https".equalsIgnoreCase(endpointOverride.getScheme())) {
            problems.add("app.assistant.bedrock.endpoint-override precisa ser https");
        }
        positive(problems, "connect-timeout", connectTimeout);
        positive(problems, "socket-timeout", socketTimeout);
        if (retryBackoff.isNegative()) {
            problems.add("app.assistant.bedrock.retry-backoff não pode ser negativo");
        }
        if (maxConnections < 1) {
            problems.add("app.assistant.bedrock.max-connections deve ser positivo");
        }
        if (!currency.matches("[A-Z]{3}")) {
            problems.add("app.assistant.bedrock.currency deve ser um código ISO 4217 (ex.: USD)");
        }
        if (!cacheTtl.equals("5m") && !cacheTtl.equals("1h")) {
            problems.add("app.assistant.bedrock.cache-ttl deve ser 5m ou 1h");
        }
        tier(problems, "standard", standard);
        tier(problems, "advanced", advanced);
        return problems;
    }

    private static void tier(List<String> problems, String name, Tier tier) {
        String key = "app.assistant.bedrock." + name;
        if (tier == null) {
            problems.add(key + " ausente (model-id e pricing)");
            return;
        }
        if (tier.modelId() == null || !MODEL_ID.matcher(tier.modelId().trim()).matches()) {
            problems.add(key + ".model-id ausente ou inválido");
        }
        Pricing p = tier.pricing();
        if (p == null) {
            problems.add(key + ".pricing ausente: os preços ficam na configuração e precisam ser conferidos na conta");
            return;
        }
        price(problems, key + ".pricing.input-per-million", p.inputPerMillion());
        price(problems, key + ".pricing.output-per-million", p.outputPerMillion());
        price(problems, key + ".pricing.cache-read-per-million", p.cacheReadPerMillion());
        price(problems, key + ".pricing.cache-write-per-million", p.cacheWritePerMillion());
        if (p.longContextThreshold() != null) {
            if (p.longContextThreshold() < 1) {
                problems.add(key + ".pricing.long-context-threshold deve ser positivo");
            }
            price(problems, key + ".pricing.long-input-per-million", p.longInputPerMillion());
            price(problems, key + ".pricing.long-output-per-million", p.longOutputPerMillion());
            price(problems, key + ".pricing.long-cache-read-per-million", p.longCacheReadPerMillion());
            price(problems, key + ".pricing.long-cache-write-per-million", p.longCacheWritePerMillion());
        }
    }

    private static void price(List<String> problems, String key, BigDecimal value) {
        if (value == null || value.signum() < 0) {
            problems.add(key + " ausente ou negativo");
        }
    }

    private static void positive(List<String> problems, String key, Duration value) {
        if (value.isNegative() || value.isZero()) {
            problems.add("app.assistant.bedrock." + key + " deve ser positivo");
        }
    }
}
