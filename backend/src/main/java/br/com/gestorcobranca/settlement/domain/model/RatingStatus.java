package br.com.gestorcobranca.settlement.domain.model;

/** Apuração do recebimento: aguardando regra contratual vigente ou apurado (lançamento no razão de comissão). */
public enum RatingStatus {
    PENDING_RULES,
    RATED
}
