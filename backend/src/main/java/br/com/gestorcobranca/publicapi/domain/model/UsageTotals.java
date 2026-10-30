package br.com.gestorcobranca.publicapi.domain.model;

/**
 * Consumo somado num período (por credor, token ou rota), medido em bytes (medição técnica).
 *
 * @param requests         requisições autenticadas
 * @param throttled        recusadas pelo limite de taxa (429) — não cobradas
 * @param clientErrors     respostas 4xx exceto 429 — não cobradas
 * @param serverErrors     respostas 5xx — não cobradas (falha da plataforma)
 * @param bytesIn          corpo de todas as requisições (bytes)
 * @param bytesOut         corpo de todas as respostas antes da compressão HTTP (bytes)
 * @param billableBytesIn  corpo das requisições cobráveis (2xx/3xx)
 * @param billableBytesOut corpo das respostas das requisições cobráveis (2xx/3xx)
 * @param records          registros processados (títulos importados, pagamentos e contatos gravados…)
 */
public record UsageTotals(long requests, long throttled, long clientErrors, long serverErrors, long bytesIn,
                          long bytesOut, long billableBytesIn, long billableBytesOut, long records) {

    public static final UsageTotals ZERO = new UsageTotals(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /**
     * Requisições cobráveis: as autenticadas com resposta 2xx/3xx — exclui as limitadas (429), os demais erros do
     * cliente (4xx) e as falhas da plataforma (5xx). Decisão 607.
     */
    public long billableRequests() {
        return Math.max(0, requests - throttled - clientErrors - serverErrors);
    }

    public UsageTotals plus(UsageTotals o) {
        return new UsageTotals(requests + o.requests, throttled + o.throttled, clientErrors + o.clientErrors,
                serverErrors + o.serverErrors, bytesIn + o.bytesIn, bytesOut + o.bytesOut,
                billableBytesIn + o.billableBytesIn, billableBytesOut + o.billableBytesOut, records + o.records);
    }
}
