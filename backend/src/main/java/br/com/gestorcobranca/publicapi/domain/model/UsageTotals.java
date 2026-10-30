package br.com.gestorcobranca.publicapi.domain.model;

/**
 * Consumo somado num período (por credor, token ou rota).
 *
 * @param requests     requisições autenticadas
 * @param throttled    recusadas pelo limite de taxa (429) — não faturadas
 * @param clientErrors respostas 4xx (exceto 429) — faturadas: consumiram processamento
 * @param serverErrors respostas 5xx — não faturadas (falha da plataforma)
 * @param bytesIn      corpo das requisições (bytes)
 * @param bytesOut     corpo das respostas antes da compressão HTTP (bytes)
 * @param records      registros processados (títulos importados, pagamentos e contatos gravados…)
 */
public record UsageTotals(long requests, long throttled, long clientErrors, long serverErrors, long bytesIn,
                          long bytesOut, long records) {

    public static final UsageTotals ZERO = new UsageTotals(0, 0, 0, 0, 0, 0, 0);

    /** Requisições cobráveis: todas as autenticadas menos as limitadas (429) e as falhas da plataforma (5xx). */
    public long billableRequests() {
        return Math.max(0, requests - throttled - serverErrors);
    }

    public UsageTotals plus(UsageTotals o) {
        return new UsageTotals(requests + o.requests, throttled + o.throttled, clientErrors + o.clientErrors,
                serverErrors + o.serverErrors, bytesIn + o.bytesIn, bytesOut + o.bytesOut, records + o.records);
    }
}
