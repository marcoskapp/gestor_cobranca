package br.com.gestorcobranca.incentive.application.port.in;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Períodos de apuração e fechamento (requer {@code COMMISSION_STATEMENT_MANAGE}). */
public interface PeriodUseCases {

    List<PeriodView> list();

    /**
     * Fecha o período manualmente: libera lançamentos vencidos, aplica o plano e gera os extratos
     * em {@code CALCULATED}. Só depois de encerrada a carência do último dia do período.
     */
    PeriodView close(YearMonth period);

    /**
     * @param closeableFrom primeiro dia em que o fechamento é permitido (fim + carência em dias úteis)
     */
    record PeriodView(UUID id, YearMonth period, LocalDate start, LocalDate end, String status, Instant closedAt,
                      UUID closedBy, String closeTrigger, Integer statements, LocalDate closeableFrom) {
    }
}
