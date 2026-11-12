package br.com.gestorcobranca.incentive.domain.model.tier;

/**
 * Situação da rodada: proposta (em revisão), publicada (os níveis passam a valer) ou expirada (sem publicação no prazo;
 * continua valendo a publicação anterior).
 */
public enum TierRoundStatus {
    PROPOSED, PUBLISHED, EXPIRED
}
