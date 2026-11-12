package br.com.gestorcobranca.organization.domain.model;

/**
 * Regime jurídico da carteira (cobrança B2B, {@code cobranca-b2b.md} §6). O tipo de pessoa diz quem é o devedor; o
 * regime diz quais regras valem. Na dúvida vale {@link #CONSUMER}: proteção nunca é retirada por inferência.
 */
public enum PortfolioRegime {
    /** Relação de consumo (CDC): pessoa física ou empresa consumidora final. */
    CONSUMER,
    /** Relação entre empresas (Código Civil e contrato): sem os tetos e institutos próprios do CDC. */
    BUSINESS;

    /** Teto de multa moratória de 2% (CDC art. 52 §1º): alerta de encargos na revisão da carga. */
    public boolean cdcFineCapApplies() {
        return this == CONSUMER;
    }

    /** Superendividamento (Lei 14.181/2021, instituto do CDC) pode ser registrado. */
    public boolean superIndebtednessApplies() {
        return this == CONSUMER;
    }
}
