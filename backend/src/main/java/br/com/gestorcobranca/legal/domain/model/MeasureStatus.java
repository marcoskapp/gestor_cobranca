package br.com.gestorcobranca.legal.domain.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Situação da medida extrajudicial:
 * <pre>
 * PLANNED → SUBMITTED → EFFECTIVE → REMOVED (baixa)
 *    │          ├─► FAILED
 *    └──────────┴─► CANCELLED
 * </pre>
 * EFFECTIVE: protesto lavrado, negativação incluída, notificação entregue, propriedade consolidada.
 */
public enum MeasureStatus {
    PLANNED,
    SUBMITTED,
    EFFECTIVE,
    FAILED,
    CANCELLED,
    REMOVED;

    private static final Map<MeasureStatus, Set<MeasureStatus>> NEXT = Map.of(
            PLANNED, EnumSet.of(SUBMITTED, CANCELLED),
            SUBMITTED, EnumSet.of(EFFECTIVE, FAILED, CANCELLED),
            EFFECTIVE, EnumSet.of(REMOVED),
            FAILED, EnumSet.noneOf(MeasureStatus.class),
            CANCELLED, EnumSet.noneOf(MeasureStatus.class),
            REMOVED, EnumSet.noneOf(MeasureStatus.class));

    public boolean canMoveTo(MeasureStatus target) {
        return NEXT.get(this).contains(target);
    }

    public boolean isFinal() {
        return NEXT.get(this).isEmpty();
    }
}
