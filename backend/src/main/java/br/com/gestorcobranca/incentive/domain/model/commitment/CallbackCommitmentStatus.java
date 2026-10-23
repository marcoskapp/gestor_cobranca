package br.com.gestorcobranca.incentive.domain.model.commitment;

import java.util.Optional;

/**
 * Situação de um compromisso de retorno (tarefa {@code CALLBACK} × horário combinado) para os
 * indicadores. Eventos do mesmo compromisso chegam em qualquer ordem; a situação só avança para
 * uma de grau maior, de modo que o resultado não depende da ordem de entrega:
 * <ul>
 *   <li>{@link #SCHEDULED} (0) — agendado, ainda sem desfecho;</li>
 *   <li>{@link #SUPERSEDED} (1) — reagendado antes de vencer: o compromisso foi substituído pelo novo horário;</li>
 *   <li>{@link #CANCELLED} (2) — cancelado antes de vencer;</li>
 *   <li>{@link #MISSED} (3) — atrasado; um cancelamento posterior não apaga o atraso;</li>
 *   <li>{@link #COMPLETED_LATE} e {@link #KEPT} (4) — desfechos finais (mutuamente exclusivos no produtor).</li>
 * </ul>
 */
public enum CallbackCommitmentStatus {
    SCHEDULED(0), SUPERSEDED(1), CANCELLED(2), MISSED(3), COMPLETED_LATE(4), KEPT(4);

    private final int rank;

    CallbackCommitmentStatus(int rank) {
        this.rank = rank;
    }

    /** Situação correspondente a {@code CallbackStatusChanged.status}; vazio se desconhecida. */
    public static Optional<CallbackCommitmentStatus> fromEvent(String status) {
        if (status == null) {
            return Optional.empty();
        }
        return switch (status) {
            case "SCHEDULED" -> Optional.of(SCHEDULED);
            case "KEPT" -> Optional.of(KEPT);
            case "MISSED" -> Optional.of(MISSED);
            case "COMPLETED_LATE" -> Optional.of(COMPLETED_LATE);
            case "CANCELLED" -> Optional.of(CANCELLED);
            default -> Optional.empty();
        };
    }

    /** A situação resultante de receber {@code other} (a de maior grau; empate mantém a atual). */
    public CallbackCommitmentStatus merge(CallbackCommitmentStatus other) {
        return other.rank > rank ? other : this;
    }

    public boolean isOpen() {
        return this == SCHEDULED;
    }

    public CommitmentTally tally() {
        boolean scheduled = this == SCHEDULED || this == MISSED || this == COMPLETED_LATE || this == KEPT;
        return CommitmentTally.callback(scheduled, this == KEPT, this == MISSED || this == COMPLETED_LATE);
    }
}
