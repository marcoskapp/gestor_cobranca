package br.com.gestorcobranca.organization.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de vida da carteira (M02). Apenas carteiras {@link #ACTIVE} têm títulos elegíveis para
 * cobrança (RB02). Devolução e encerramento não apagam histórico (RB01).
 */
public enum PortfolioStatus {
    /** Cadastrada, recebendo cargas, ainda não operada. */
    DRAFT,
    ACTIVE,
    /** Operação suspensa temporariamente (pedido do credor, auditoria). */
    SUSPENDED,
    /** Devolvida ao credor: títulos não recuperados saem da operação. */
    RETURNED,
    /** Encerrada definitivamente. */
    CLOSED;

    public Set<PortfolioStatus> allowedTransitions() {
        return switch (this) {
            case DRAFT -> EnumSet.of(ACTIVE, CLOSED);
            case ACTIVE -> EnumSet.of(SUSPENDED, RETURNED, CLOSED);
            case SUSPENDED -> EnumSet.of(ACTIVE, RETURNED, CLOSED);
            case RETURNED -> EnumSet.of(CLOSED);
            case CLOSED -> EnumSet.noneOf(PortfolioStatus.class);
        };
    }

    public boolean canTransitionTo(PortfolioStatus target) {
        return allowedTransitions().contains(target);
    }

    /** Aceita novas cargas de títulos. */
    public boolean acceptsImports() {
        return this == DRAFT || this == ACTIVE || this == SUSPENDED;
    }
}
