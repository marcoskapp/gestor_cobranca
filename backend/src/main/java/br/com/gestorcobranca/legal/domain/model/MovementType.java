package br.com.gestorcobranca.legal.domain.model;

/**
 * Natureza do andamento processual (etapas 6 e 7). {@code CITATION} interrompe a prescrição
 * (CC art. 202, I; CPC art. 240 §1º — retroage à data do despacho que ordenou a citação, que é a
 * data a lançar) e leva o caso de FILED a IN_PROGRESS; {@code FUNDS_RELEASED} soma ao valor
 * recuperado.
 */
public enum MovementType {
    DISTRIBUTION,
    CITATION_ORDERED,
    CITATION,
    DEFENSE_FILED,
    DECISION,
    SENTENCE,
    APPEAL,
    JUDGMENT_ENFORCEMENT,
    SEIZURE,
    APPRAISAL,
    AUCTION,
    ADJUDICATION,
    FUNDS_RELEASED,
    SETTLEMENT_HOMOLOGATED,
    SUSPENSION,
    ARCHIVING,
    PUBLICATION,
    OTHER;

    /** Andamentos que levam valor (avaliação, arrematação, levantamento). */
    public boolean carriesAmount() {
        return this == APPRAISAL || this == AUCTION || this == ADJUDICATION || this == FUNDS_RELEASED;
    }
}
