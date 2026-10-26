package br.com.gestorcobranca.assistant.infrastructure.provider.bedrock;

import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.Usage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Custo estimado de uma chamada pela tabela configurada ({@link BedrockProperties.Pricing}): tokens de
 * entrada sem cache, de saída, lidos e gravados no cache, cada um pelo seu preço por milhão. Quando o prompt
 * (entrada com e sem cache) passa do limiar de contexto longo, todos os preços da chamada são os de contexto
 * longo. Seis casas decimais (coluna {@code cost_amount numeric(19,6)} da trilha).
 */
final class BedrockPricing {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private BedrockPricing() {
    }

    static boolean longContext(BedrockProperties.Pricing p, Usage usage) {
        return p.longContextThreshold() != null && usage.promptTokens() > p.longContextThreshold();
    }

    static BigDecimal cost(BedrockProperties.Pricing p, Usage usage) {
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
