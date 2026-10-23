package br.com.gestorcobranca.incentive.domain.model.statement;

/**
 * Ciclo do extrato (§5): {@code CALCULATED → DISPUTED → ADJUSTED → APPROVED → EXPORTED}.
 * Contestações resolvidas sem ajuste devolvem o extrato a {@code CALCULATED}; aprovado é imutável.
 */
public enum StatementStatus {
    CALCULATED, DISPUTED, ADJUSTED, APPROVED, EXPORTED;

    public boolean isFinal() {
        return this == APPROVED || this == EXPORTED;
    }
}
