package br.com.gestorcobranca.publicapi.application.port.in;

import java.io.OutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Consumo da API pública para as telas de acompanhamento ({@code PUBLIC_API_USAGE_VIEW}), sempre restrito aos credores
 * do escopo. Dados das últimas horas podem levar até um minuto para aparecer (gravação em lote).
 */
public interface PublicApiUsageUseCases {

    /**
     * @param from      primeiro dia (inclusive, fuso de operação)
     * @param to        último dia (inclusive); período máximo de 366 dias
     * @param creditorId credor (opcional)
     * @param tokenId   token (opcional)
     */
    UsageReport report(LocalDate from, LocalDate to, UUID creditorId, UUID tokenId);

    /** Mesmo recorte em CSV (por dia, credor, token e rota), escrito em streaming; registra {@code DATA_EXPORTED}. */
    void exportCsv(LocalDate from, LocalDate to, UUID creditorId, UUID tokenId, OutputStream output);

    /**
     * @param billableRequests requisições cobráveis (2xx/3xx: exclui 4xx, 429 e 5xx)
     */
    record Totals(long requests, long billableRequests, long throttled, long clientErrors, long serverErrors,
                  long bytesIn, long bytesOut, long records) {
    }

    record DailyUsage(LocalDate day, Totals totals) {
    }

    record RouteUsage(String method, String route, Totals totals) {
    }

    record TokenUsage(UUID tokenId, String prefix, String name, UUID creditorId, Totals totals) {
    }

    record UsageReport(LocalDate from, LocalDate to, Totals totals, List<DailyUsage> daily, List<RouteUsage> byRoute,
                       List<TokenUsage> byToken) {
    }
}
