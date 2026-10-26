package br.com.gestorcobranca.shared.ai.bedrock;

import br.com.gestorcobranca.shared.ai.LanguageModel.Usage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Custo estimado de uma chamada pela tabela configurada ({@link BedrockModelSettings.Pricing}): tokens de
 * entrada sem cache, de saída, lidos e gravados no cache, cada um pelo seu preço por milhão. Quando o prompt
 * (entrada com e sem cache) passa do limiar de contexto longo, todos os preços da chamada são os de contexto
 * longo. Seis casas decimais (colunas {@code cost_amount numeric(19,6)} das trilhas).
 */
public final class BedrockPricing {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private BedrockPricing() {
    }

    public static boolean longContext(BedrockModelSettings.Pricing p, Usage usage) {
        return p.longContextThreshold() != null && usage.promptTokens() > p.longContextThreshold();
    }

    public static BigDecimal cost(BedrockModelSettings.Pricing p, Usage usage) {
        boolean longContext = longContext(p, usage);
        BigDecimal total = rate(longContext ? p.longInputPerMillion() : p.inputPerMillion(), usage.inputTokens())
                .add(rate(longContext ? p.longOutputPerMillion() : p.outputPerMillion(), usage.outputTokens()))
                .add(rate(longContext ? p.longCacheReadPerMillion() : p.cacheReadPerMillion(), usage.cacheReadInputTokens()))
                .add(rate(longContext ? p.longCacheWritePerMillion() : p.cacheWritePerMillion(),
                        usage.cacheWriteInputTokens()));
        return total.divide(MILLION, 6, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal rate(BigDecimal perMillion, long tokens) {
        return tokens <= 0 ? BigDecimal.ZERO : perMillion.multiply(BigDecimal.valueOf(tokens));
    }
}
