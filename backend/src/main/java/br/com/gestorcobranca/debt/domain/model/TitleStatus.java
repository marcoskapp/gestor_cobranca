package br.com.gestorcobranca.debt.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de vida do título (M02). Transições:
 * <pre>
 * OPEN      → SUSPENDED, CANCELLED, RETURNED (manuais) | SETTLED (automática: saldo zerado)
 * SUSPENDED → OPEN, CANCELLED, RETURNED (manuais)       | SETTLED (automática)
 * SETTLED   → OPEN (automática: estorno torna o saldo positivo)
 * CANCELLED, RETURNED → finais (o histórico permanece — RB01)
 * </pre>
 */
public enum TitleStatus {
    /** Em cobrança. */
    OPEN,
    /** Quitado: saldo zerado por pagamentos, descontos ou baixa. */
    SETTLED,
    /** Cancelado pelo credor (ex.: lançamento indevido). */
    CANCELLED,
    /** Cobrança suspensa temporariamente (pedido do credor, análise). */
    SUSPENDED,
    /** Devolvido ao credor (fim de vigência, devolução de carteira). */
    RETURNED;

    public Set<TitleStatus> allowedTransitions() {
        return switch (this) {
            case OPEN -> EnumSet.of(SETTLED, SUSPENDED, CANCELLED, RETURNED);
            case SUSPENDED -> EnumSet.of(OPEN, SETTLED, CANCELLED, RETURNED);
            case SETTLED -> EnumSet.of(OPEN);
            case CANCELLED, RETURNED -> EnumSet.noneOf(TitleStatus.class);
        };
    }

    public boolean canTransitionTo(TitleStatus target) {
        return allowedTransitions().contains(target);
    }

    /** Aceita pagamentos, encargos, descontos e ajustes (estornos são aceitos em qualquer status). */
    public boolean acceptsPostings() {
        return this == OPEN || this == SUSPENDED;
    }

    /** Fora da operação definitivamente. */
    public boolean isClosed() {
        return this == CANCELLED || this == RETURNED;
    }
}
