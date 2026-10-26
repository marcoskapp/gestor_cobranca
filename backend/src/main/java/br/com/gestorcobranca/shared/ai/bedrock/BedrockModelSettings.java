package br.com.gestorcobranca.shared.ai.bedrock;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Modelo de um nível ({@link br.com.gestorcobranca.shared.ai.ModelTier}) no Amazon Bedrock: id do modelo ou
 * perfil de inferência, cache de prompt e tabela de preços. Cada produto de IA declara os seus níveis na própria
 * configuração (ex.: {@code app.assistant.bedrock.standard}, {@code app.helpdesk.bedrock.model}).
 *
 * @param modelId       modelo base, perfil de inferência de sistema (ex.: {@code us.anthropic.claude-haiku-5-5})
 *                      ou ARN de perfil de aplicação
 * @param promptCaching envia {@code cachePoint} depois das ferramentas e do prompt (padrão sim)
 * @param pricing       preços por milhão de tokens
 */
public record BedrockModelSettings(String modelId, Boolean promptCaching, Pricing pricing) {

    /** Ids aceitos pelo Bedrock: base ({@code anthropic.x}), perfil de sistema ({@code us.anthropic.x}) ou ARN. */
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{2,199}");

    public boolean caching() {
        return promptCaching == null || promptCaching;
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

    /**
     * Valida um nível.
     *
     * @param key      chave completa do nível na configuração (ex.: {@code app.assistant.bedrock.standard})
     * @param settings nível (nulo = ausente)
     * @param problems acumula as mensagens
     */
    public static void validate(String key, BedrockModelSettings settings, List<String> problems) {
        if (settings == null) {
            problems.add(key + " ausente (model-id e pricing)");
            return;
        }
        if (settings.modelId() == null || !MODEL_ID.matcher(settings.modelId().trim()).matches()) {
            problems.add(key + ".model-id ausente ou inválido");
        }
        Pricing p = settings.pricing();
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
}
