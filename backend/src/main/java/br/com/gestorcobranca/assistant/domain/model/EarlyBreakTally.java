package br.com.gestorcobranca.assistant.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Quebra precoce de um grupo de acordos (assistente ou humanos) no mesmo critério da quebra precoce por
 * safra do analytics (decisão 158; {@code docs/negocio/indicadores.md}): só entram no denominador os
 * acordos <b>expostos ao risco</b> — formalizados há pelo menos a janela na data de referência
 * ({@code formalizado + janela ≤ agora}); entre eles, contam como quebra os quebrados até o fim da janela
 * ({@code quebra ≤ formalizado + janela}). Os demais do período estão <b>em maturação</b> e aparecem à
 * parte, para não subestimar a quebra da safra recente (decisão 712).
 *
 * @param formalized acordos formalizados no período
 * @param exposed    dos formalizados, os que já completaram a janela
 * @param broken     dos expostos, os quebrados dentro da janela
 */
public record EarlyBreakTally(long formalized, long exposed, long broken) {

    public EarlyBreakTally {
        if (formalized < 0 || exposed < 0 || broken < 0 || exposed > formalized || broken > exposed) {
            throw new IllegalArgumentException("Contagens de quebra precoce inconsistentes");
        }
    }

    /** Formalizados ainda dentro da janela (fora do denominador). */
    public long maturing() {
        return formalized - exposed;
    }

    /** Quebrados ÷ expostos (4 casas); nulo sem acordo exposto. */
    public BigDecimal rate() {
        return exposed == 0 ? null
                : BigDecimal.valueOf(broken).divide(BigDecimal.valueOf(exposed), 4, RoundingMode.HALF_EVEN);
    }
}
