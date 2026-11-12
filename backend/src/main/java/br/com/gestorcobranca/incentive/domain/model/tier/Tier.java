package br.com.gestorcobranca.incentive.domain.model.tier;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;

/**
 * Nível de desempenho do negociador (distribuicao-por-desempenho.md §3.2): A é o mais alto. Só o nível sai do módulo
 * ({@code NegotiatorTierChanged}).
 */
public enum Tier {
    A, B, C, D;

    /** Nível informado como texto (só A–D, sem diferenciar maiúsculas). */
    public static Tier parse(String value, String code) {
        if (value != null) {
            for (Tier tier : values()) {
                if (tier.name().equalsIgnoreCase(value.strip())) {
                    return tier;
                }
            }
        }
        throw new BusinessRuleException(code, "Nível inválido: use A, B, C ou D");
    }
}
