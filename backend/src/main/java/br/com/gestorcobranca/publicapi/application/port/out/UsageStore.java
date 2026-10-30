package br.com.gestorcobranca.publicapi.application.port.out;

import br.com.gestorcobranca.publicapi.domain.model.UsageTotals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Consumo agregado por hora ({@code publicapi.usage_hourly}). Gravação por soma ({@code upsert}) em lote, dentro do
 * tenant corrente; leituras agregadas para telas, exportação e extrato.
 */
public interface UsageStore {

    /** Soma os deltas do tenant corrente (uma instrução em lote). */
    void add(List<UsageDelta> deltas);

    /** Último uso e IP dos tokens do tenant corrente (só avança). */
    void touchTokens(List<LastUse> uses);

    record LastUse(UUID tokenId, Instant at, String ip) {
    }

    /**
     * Filtro das leituras.
     *
     * @param creditorIds credores permitidos; {@code null} = sem restrição
     */
    record Filter(Instant from, Instant to, Collection<UUID> creditorIds, UUID creditorId, UUID tokenId) {
    }

    UsageTotals totals(Filter filter);

    List<DailyRow> daily(Filter filter, String zoneId);

    List<RouteRow> byRoute(Filter filter);

    List<TokenRow> byToken(Filter filter);

    /** Linhas detalhadas (dia, credor, token, rota) em streaming, para exportação. */
    void detailed(Filter filter, String zoneId, Consumer<DetailRow> sink);

    record DailyRow(LocalDate day, UsageTotals totals) {
    }

    record RouteRow(String method, String route, UsageTotals totals) {
    }

    record TokenRow(UUID tokenId, UUID creditorId, UsageTotals totals) {
    }

    record DetailRow(LocalDate day, UUID creditorId, UUID tokenId, String method, String route, UsageTotals totals,
                     long latencyMsTotal) {
    }

    /**
     * Início da primeira hora com consumo (requisição ou registro) do credor a partir de {@code from}, inclusivo —
     * define o início do ciclo de consumo quando não há ciclo anterior contíguo.
     */
    java.util.Optional<Instant> firstUsageAt(UUID creditorId, Instant from);

    /** Última gravação em lote que alterou o consumo do filtro ("atualizado em" das telas de posição). */
    java.util.Optional<Instant> lastUpdatedAt(Filter filter);

    /** Consumo por dia (no fuso) e token, para o crescimento diário do ciclo. */
    List<DailyTokenRow> dailyByToken(Filter filter, String zoneId);

    record DailyTokenRow(LocalDate day, UUID tokenId, UsageTotals totals) {
    }
}
