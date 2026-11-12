package br.com.gestorcobranca.costing.application.port.in;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Reapuração dos custos de um período ({@code POST /api/v1/costing/refresh}), por exemplo depois de corrigir uma faixa,
 * tarifa ou câmbio. Exige {@code COST_MANAGE} (ou {@code COST_RATE_MANAGE}) e acesso a todos os credores. A apuração
 * diária agendada reprocessa os últimos dias sozinha.
 */
public interface CostRefreshUseCases {

    /**
     * @param to até ontem, inclusive (o dia de hoje ainda não fechou); no máximo {@code app.costing.max-refresh-days}
     *           dias. Com outra apuração da assessoria em andamento, falha na hora com {@code costing.refresh_running}
     *           (409), sem esperar.
     */
    RefreshResult refresh(LocalDate from, LocalDate to);

    /** @param rows fatos de custo gravados (sem contar os de atividade e avisos) */
    record RefreshResult(LocalDate from, LocalDate to, long rows, Instant refreshedAt) {
    }
}
