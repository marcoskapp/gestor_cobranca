package br.com.gestorcobranca.costing.application.port.out;

import br.com.gestorcobranca.costing.domain.model.CostCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Somas dos fatos apurados para o painel e a exportação (RLS da assessoria corrente). */
public interface MarginReader {

    /**
     * Recorte dos fatos.
     *
     * @param creditorIds         nulo = todos os credores
     * @param portfolioIds        vazio = todas as carteiras
     * @param includeUnallocated  inclui as linhas sem credor
     */
    record Slice(LocalDate from, LocalDate to, Set<UUID> creditorIds, Set<UUID> portfolioIds,
                 boolean includeUnallocated) {
    }

    record CostRow(UUID creditorId, UUID portfolioId, YearMonth month, CostCategory category, BigDecimal amount,
                   BigDecimal quantity) {
    }

    record ActivityRow(UUID creditorId, UUID portfolioId, YearMonth month, BigDecimal recovered, BigDecimal revenue,
                       long settledAgreements) {
    }

    /** @param months meses distintos com o aviso */
    record WarningRow(String code, BigDecimal quantity, long months) {
    }

    List<CostRow> costs(Slice slice);

    List<ActivityRow> activity(Slice slice);

    List<WarningRow> warnings(Slice slice);

    Map<UUID, String> creditorNames(Collection<UUID> creditorIds);

    Map<UUID, String> portfolioNames(Collection<UUID> portfolioIds);
}
