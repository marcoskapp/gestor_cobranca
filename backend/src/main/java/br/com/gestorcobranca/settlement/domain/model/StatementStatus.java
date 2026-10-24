package br.com.gestorcobranca.settlement.domain.model;

import java.util.Set;

/**
 * Ciclo do demonstrativo: {@code DRAFT → CALCULATED → REVIEWED → APPROVED → SENT → ACKNOWLEDGED | CONTESTED};
 * {@code DRAFT/CALCULATED/REVIEWED → CANCELLED}; {@code CONTESTED → ACKNOWLEDGED} após a correção no período
 * seguinte. A partir de {@code APPROVED} os valores são imutáveis.
 */
public enum StatementStatus {
    DRAFT,
    CALCULATED,
    REVIEWED,
    APPROVED,
    SENT,
    ACKNOWLEDGED,
    CONTESTED,
    CANCELLED;

    public static final Set<StatementStatus> OPEN = Set.of(DRAFT, CALCULATED, REVIEWED);
    public static final Set<StatementStatus> CLOSED = Set.of(APPROVED, SENT, ACKNOWLEDGED, CONTESTED);

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public boolean isClosed() {
        return CLOSED.contains(this);
    }
}
