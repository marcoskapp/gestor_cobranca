package br.com.gestorcobranca.settlement.domain.service;

import br.com.gestorcobranca.shared.domain.Guard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Prazo para o credor responder ao demonstrativo enviado (portal do credor, fatia 7; decisão do responsável de
 * 26/11/2026): data do envio no fuso de negócio + N dias corridos. É só lembrete — vencido, o demonstrativo continua
 * {@code SENT} ("prazo vencido"), sem efeito automático.
 */
public final class StatementResponseDeadline {

    /** Padrão do responsável (configurável em {@code app.settlement.statement-response-days}). */
    public static final int DEFAULT_DAYS = 10;
    public static final int MAX_DAYS = 90;

    private StatementResponseDeadline() {
    }

    public static LocalDate dueOn(Instant sentAt, ZoneId zone, int days) {
        Guard.require(sentAt != null, "statement.sent_at_required", "Envio sem data");
        Guard.require(days >= 1 && days <= MAX_DAYS, "statement.response_days_invalid",
                "Prazo para o credor responder: de 1 a %d dias".formatted(MAX_DAYS));
        return LocalDate.ofInstant(sentAt, zone).plusDays(days);
    }

    /** Vencido = o dia do prazo já passou (o próprio dia do prazo ainda vale). */
    public static boolean overdue(LocalDate dueOn, LocalDate today) {
        return dueOn != null && dueOn.isBefore(today);
    }
}
