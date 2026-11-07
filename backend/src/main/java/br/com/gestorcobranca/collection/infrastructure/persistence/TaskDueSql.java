package br.com.gestorcobranca.collection.infrastructure.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Regra única de "retornos em aberto até o fim do dia" usada por "Minhas pendências" e pelo "Seu dia"
 * (alias {@code t} para {@code collection.task}; índice {@code ix_task_agenda}):
 *
 * <ul>
 *   <li>entram os retornos pendentes com horário até {@code :tomorrow} e os perdidos ({@code MISSED}) a partir de
 *       {@code :since} (a janela de 30 dias da agenda, {@code CollectionPendingWork.MISSED_WINDOW});</li>
 *   <li>atrasado ({@link #OVERDUE}): perdido ou pendente com horário anterior a {@code :now};</li>
 *   <li>de hoje ({@link #DUE_TODAY}): pendente com horário a partir de {@code :now}.</li>
 * </ul>
 */
final class TaskDueSql {

    static final String OVERDUE = "(t.status = 'MISSED' or t.due_at < :now)";
    static final String DUE_TODAY = "(t.status = 'PENDING' and t.due_at >= :now)";

    private TaskDueSql() {
    }

    /**
     * {@code from ... where} dos retornos em aberto do dia, com a condição de responsável informada
     * (ex.: {@code t.assignee_user_id = :me}).
     */
    static String fromOpenUntilTomorrow(String assigneeCondition) {
        return """
                  from collection.task t
                 where %s and t.status in ('PENDING', 'MISSED') and t.due_at < :tomorrow
                   and (t.status = 'PENDING' or t.due_at >= :since)
                """.formatted(assigneeCondition);
    }

    static MapSqlParameterSource params(Instant now, Instant startOfTomorrow, Instant missedSince) {
        return new MapSqlParameterSource().addValue("now", Timestamp.from(now))
                .addValue("tomorrow", Timestamp.from(startOfTomorrow))
                .addValue("since", Timestamp.from(missedSince));
    }
}
