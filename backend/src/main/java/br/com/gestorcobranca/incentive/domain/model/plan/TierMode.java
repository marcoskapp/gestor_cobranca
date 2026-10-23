package br.com.gestorcobranca.incentive.domain.model.plan;

/** Aplicação das faixas do percentual sobre recuperado (§4.2). */
public enum TierMode {
    /** Escalonado progressivo: cada faixa paga a sua taxa sobre o volume dentro dela. */
    PROGRESSIVE,
    /** Por faixa atingida: a taxa da maior faixa alcançada vale para todo o volume. */
    REACHED_TIER
}
