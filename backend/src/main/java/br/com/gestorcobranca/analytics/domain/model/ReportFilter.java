package br.com.gestorcobranca.analytics.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * Filtros comuns a todos os painéis. Período em datas de negócio (fuso de operação), inclusive nas
 * duas pontas; conjuntos vazios = sem filtro. O escopo do usuário (credores e pessoas) é aplicado
 * depois, pelo serviço, e nunca amplia o que foi pedido.
 */
public record ReportFilter(LocalDate from, LocalDate to, Set<UUID> creditorIds, Set<UUID> portfolioIds,
                           Set<UUID> operatorIds, Set<UUID> teamIds, Set<String> channels) {

    /** Maior período aceito numa consulta (relatórios anuais com folga). */
    public static final int MAX_DAYS = 400;
    /** Maior quantidade de valores num filtro de lista. */
    public static final int MAX_VALUES = 200;

    public ReportFilter {
        Guard.require(from != null && to != null, "analytics.period_required", "Informe o período (de e até)");
        Guard.require(!to.isBefore(from), "analytics.period_invalid", "A data final é anterior à inicial");
        Guard.require(ChronoUnit.DAYS.between(from, to) < MAX_DAYS, "analytics.period_too_long",
                "Período máximo de " + MAX_DAYS + " dias");
        creditorIds = creditorIds == null ? Set.of() : Set.copyOf(creditorIds);
        portfolioIds = portfolioIds == null ? Set.of() : Set.copyOf(portfolioIds);
        operatorIds = operatorIds == null ? Set.of() : Set.copyOf(operatorIds);
        teamIds = teamIds == null ? Set.of() : Set.copyOf(teamIds);
        channels = channels == null ? Set.of() : Set.copyOf(channels);
        Guard.require(creditorIds.size() <= MAX_VALUES && portfolioIds.size() <= MAX_VALUES
                && operatorIds.size() <= MAX_VALUES && teamIds.size() <= MAX_VALUES && channels.size() <= MAX_VALUES,
                "analytics.filter_too_large", "Filtro com mais de " + MAX_VALUES + " valores");
        Guard.require(channels.stream().allMatch(c -> c.matches("[A-Z_]{1,20}")), "analytics.channel_invalid",
                "Canal inválido");
    }

    public ReportFilter withCreditors(Set<UUID> ids) {
        return new ReportFilter(from, to, ids, portfolioIds, operatorIds, teamIds, channels);
    }

    public ReportFilter withOperators(Set<UUID> ids) {
        return new ReportFilter(from, to, creditorIds, portfolioIds, ids, Set.of(), channels);
    }

    public boolean segmentsBeyondPeople() {
        return !creditorIds.isEmpty() || !portfolioIds.isEmpty() || !channels.isEmpty();
    }
}
