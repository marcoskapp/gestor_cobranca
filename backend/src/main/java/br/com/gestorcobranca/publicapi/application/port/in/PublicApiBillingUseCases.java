package br.com.gestorcobranca.publicapi.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Modelo de faturamento da API pública: tabelas de preço versionadas (padrão da assessoria e por credor) e extrato
 * mensal de consumo por credor, com detalhamento por token e rota. Não cobra: deixa o extrato pronto para faturar.
 * Tabelas exigem todos os credores ({@code PUBLIC_API_BILLING_MANAGE}); extratos respeitam o escopo por credor.
 */
public interface PublicApiBillingUseCases {

    List<PricePlanView> plans();

    /** Rascunho de nova versão (a versão é a seguinte à maior do código). */
    PricePlanView createDraft(PricePlanCommand command);

    PricePlanView updateDraft(UUID planId, PricePlanCommand command);

    /** Publica (imutável depois). Quem cria o rascunho pode publicar: preço não movimenta dinheiro sozinho. */
    PricePlanView publish(UUID planId);

    /** Prévia do mês (sem gravar), com a tabela que seria aplicada hoje. */
    StatementView preview(UUID creditorId, YearMonth month);

    /**
     * Fecha o mês do credor: grava o extrato. Mês corrente ou futuro é recusado. Já existindo extrato do mês, exige
     * {@code reason} e gera nova revisão (o anterior permanece).
     */
    StatementView close(UUID creditorId, YearMonth month, String reason);

    StatementView statement(UUID statementId);

    PageResponse<StatementView> statements(UUID creditorId, YearMonth month, PageQuery page);

    /** Extrato em CSV (linhas e detalhamento); registra {@code DATA_EXPORTED}. */
    void exportStatement(UUID statementId, OutputStream output);

    /**
     * @param creditorId nulo = tabela padrão da assessoria
     * @param effectiveFrom primeiro dia do mês de início da vigência
     */
    record PricePlanCommand(String code, UUID creditorId, String name, LocalDate effectiveFrom, BigDecimal monthlyFee,
                            long includedRequests, BigDecimal pricePerRequest, long includedMbIn, BigDecimal pricePerMbIn,
                            long includedMbOut, BigDecimal pricePerMbOut, long includedRecords,
                            BigDecimal pricePerRecord) {
    }

    record PricePlanView(UUID id, String code, int version, UUID creditorId, String name, String currency,
                         LocalDate effectiveFrom, String status, BigDecimal monthlyFee, long includedRequests,
                         BigDecimal pricePerRequest, long includedMbIn, BigDecimal pricePerMbIn, long includedMbOut,
                         BigDecimal pricePerMbOut, long includedRecords, BigDecimal pricePerRecord, Instant publishedAt,
                         UUID publishedBy) {
    }

    record StatementLine(String metric, BigDecimal quantity, BigDecimal included, BigDecimal billable,
                         BigDecimal unitPrice, BigDecimal amount) {
    }

    /** Detalhamento por token ou rota (consumo, sem valor: o preço é do credor). */
    record StatementBreakdown(String kind, String key, String label, Map<String, Long> totals) {
    }

    /**
     * @param id        nulo na prévia
     * @param revision  0 na prévia
     * @param usageHash SHA-256 do consumo usado (verificação de reprodutibilidade)
     */
    record StatementView(UUID id, UUID creditorId, LocalDate periodStart, int revision, UUID pricePlanId,
                         String pricePlanCode, Integer pricePlanVersion, long requests, long billableRequests,
                         long bytesIn, long bytesOut, long records, BigDecimal totalAmount, String currency,
                         List<StatementLine> lines, List<StatementBreakdown> breakdown, String usageHash,
                         Instant closedAt, UUID closedBy, String reason) {
    }
}
