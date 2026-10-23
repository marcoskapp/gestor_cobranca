package br.com.gestorcobranca.legal.domain.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Fases do caso jurídico (docs/design/juridico.md §B.2):
 * <pre>
 * ELIGIBLE → UNDER_REVIEW → AWAITING_AUTHORIZATION → EXTRAJUDICIAL → FILED → IN_PROGRESS → ENFORCEMENT
 *                                                          │            │         │             │
 *                                                          └────────────┴─► SETTLED_IN_COURT ◄─┘ → CLOSED
 * UNDER_REVIEW → NOT_VIABLE (reabre em UNDER_REVIEW) · qualquer fase não final → CLOSED / RETURNED_TO_CREDITOR*
 * </pre>
 * (*) a devolução ao credor só antes do ajuizamento; depois dele o encerramento exige motivo
 * (desistência, pedido do credor…). {@code CLOSED} e {@code RETURNED_TO_CREDITOR} são finais. A
 * quebra de um acordo judicial devolve {@code SETTLED_IN_COURT} à fase processual guardada.
 */
public enum LegalStage {
    ELIGIBLE,
    UNDER_REVIEW,
    AWAITING_AUTHORIZATION,
    EXTRAJUDICIAL,
    FILED,
    IN_PROGRESS,
    ENFORCEMENT,
    SETTLED_IN_COURT,
    CLOSED,
    NOT_VIABLE,
    RETURNED_TO_CREDITOR;

    private static final Map<LegalStage, Set<LegalStage>> NEXT = Map.ofEntries(
            Map.entry(ELIGIBLE, EnumSet.of(UNDER_REVIEW, RETURNED_TO_CREDITOR, CLOSED)),
            Map.entry(UNDER_REVIEW, EnumSet.of(AWAITING_AUTHORIZATION, NOT_VIABLE, RETURNED_TO_CREDITOR, CLOSED)),
            Map.entry(AWAITING_AUTHORIZATION, EnumSet.of(EXTRAJUDICIAL, FILED, UNDER_REVIEW, NOT_VIABLE,
                    RETURNED_TO_CREDITOR, CLOSED)),
            Map.entry(EXTRAJUDICIAL, EnumSet.of(FILED, RETURNED_TO_CREDITOR, CLOSED)),
            Map.entry(FILED, EnumSet.of(IN_PROGRESS, SETTLED_IN_COURT, CLOSED)),
            Map.entry(IN_PROGRESS, EnumSet.of(ENFORCEMENT, SETTLED_IN_COURT, CLOSED)),
            Map.entry(ENFORCEMENT, EnumSet.of(SETTLED_IN_COURT, CLOSED)),
            Map.entry(SETTLED_IN_COURT, EnumSet.of(CLOSED)),
            Map.entry(NOT_VIABLE, EnumSet.of(UNDER_REVIEW, RETURNED_TO_CREDITOR, CLOSED)),
            Map.entry(CLOSED, EnumSet.noneOf(LegalStage.class)),
            Map.entry(RETURNED_TO_CREDITOR, EnumSet.noneOf(LegalStage.class)));

    private static final Set<LegalStage> JUDICIAL = EnumSet.of(FILED, IN_PROGRESS, ENFORCEMENT, SETTLED_IN_COURT);

    public Set<LegalStage> next() {
        return NEXT.get(this);
    }

    public boolean canMoveTo(LegalStage target) {
        return NEXT.get(this).contains(target);
    }

    /** Fases finais: nada mais muda no caso jurídico. */
    public boolean isTerminal() {
        return this == CLOSED || this == RETURNED_TO_CREDITOR;
    }

    /** Ajuizado (processo distribuído) — inclusive em acordo judicial. */
    public boolean isJudicial() {
        return JUDICIAL.contains(this);
    }

    /** Fases de atuação jurídica efetiva (após a autorização do credor). */
    public boolean isLegalAction() {
        return this == EXTRAJUDICIAL || isJudicial();
    }

    /** Fases em que se registram medidas, processos, prazos e custas da atuação. */
    public boolean acceptsActions() {
        return isLegalAction() || this == AWAITING_AUTHORIZATION;
    }
}
