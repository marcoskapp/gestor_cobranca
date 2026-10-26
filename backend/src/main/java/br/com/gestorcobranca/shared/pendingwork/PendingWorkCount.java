package br.com.gestorcobranca.shared.pendingwork;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Resultado de uma consulta de contagem de pendências.
 *
 * @param total       itens pendentes
 * @param overdue     itens com prazo vencido (ou meta estourada)
 * @param dueToday    itens que vencem hoje (e ainda não venceram)
 * @param oldestAt    desde quando o item mais antigo espera (nulo se não houver)
 * @param nextDueDate prazo mais próximo, inclusive vencido (nulo se a pendência não tem prazo)
 * @param singleId    id do único item, quando {@code total == 1} (permite abrir o item direto)
 */
public record PendingWorkCount(long total, long overdue, long dueToday, Instant oldestAt, LocalDate nextDueDate,
                               UUID singleId) {

    public static final PendingWorkCount NONE = new PendingWorkCount(0, 0, 0, null, null, null);

    public PendingWorkCount {
        if (total < 0 || overdue < 0 || dueToday < 0 || overdue + dueToday > total) {
            throw new IllegalArgumentException("Contagens de pendência inconsistentes");
        }
        singleId = total == 1 ? singleId : null;
    }

    public static PendingWorkCount of(long total, Instant oldestAt) {
        return new PendingWorkCount(total, 0, 0, total == 0 ? null : oldestAt, null, null);
    }

    public boolean isEmpty() {
        return total == 0;
    }

    public PendingWorkSeverity severity() {
        if (overdue > 0) {
            return PendingWorkSeverity.OVERDUE;
        }
        return dueToday > 0 ? PendingWorkSeverity.DUE_TODAY : PendingWorkSeverity.NORMAL;
    }
}
