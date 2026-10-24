package br.com.gestorcobranca.ingestion.application.port.out;

import br.com.gestorcobranca.ingestion.domain.model.review.ComputedTotals;
import br.com.gestorcobranca.ingestion.domain.model.review.RowValue;
import br.com.gestorcobranca.ingestion.domain.model.review.TotalsMeasure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Valores por linha da prévia de conferência ({@code ingestion.import_row_value}) e as agregações da conferência:
 * totais, quebras, linhas suspeitas, insumos da análise de valores e impacto da efetivação. Linhas excluídas na
 * conferência são as com alerta {@code EXCLUDED}.
 */
public interface RowValueStore {

    /** Grava os valores do bloco (idempotente: linha já gravada numa execução interrompida é ignorada). */
    void insertAll(UUID batchId, List<RowValue> rows);

    /** Totais de todas as linhas de dados da prévia (válidas ou não, excluídas ou não). */
    ComputedTotals totals(UUID batchId);

    /** Quebras por faixa de vencimento, situação, produto e validade da linha. */
    List<BreakdownRow> breakdown(UUID batchId, LocalDate today);

    /**
     * Linhas para localizar uma diferença de totais: valores ambíguos e maiores valores da coluna, e as linhas cujo
     * valor é igual à diferença.
     */
    Suspects suspects(UUID batchId, TotalsMeasure measure, BigDecimal difference, int limit);

    /** Linhas válidas com principal positivo, em ordem de linha, lidas em fluxo (transação de leitura obrigatória). */
    void forEachValid(UUID batchId, Consumer<AnalysisRow> consumer);

    /**
     * Títulos cujo principal está a {@code ratio} vezes (ou 1/ratio) da mediana inferior dos títulos do mesmo devedor
     * no arquivo.
     */
    List<DebtorOutlier> debtorOutliers(UUID batchId, double ratio, int limit);

    /** Contratos com duas ou mais parcelas e o mesmo valor de contrato em todas, com soma fora da faixa usual. */
    List<ContractSum> contractCandidates(UUID batchId, double overRatio, int limit);

    /** Impacto da efetivação (linhas válidas não excluídas). */
    Impact impact(UUID batchId, UUID creditorId);

    /**
     * @param dimension AGING, SITUATION, PRODUCT ou VALIDITY
     * @param key       faixa ({@code NOT_DUE}, {@code D1_30}…, {@code INVALID}), situação, produto ({@code OTHERS} para
     *                  o restante) ou validade ({@code VALID}, {@code ERROR}, {@code EXCLUDED})
     */
    record BreakdownRow(String dimension, String key, long rows, BigDecimal principal, BigDecimal balance) {
    }

    record SuspectRow(long rowNumber, String externalId, BigDecimal amount, boolean ambiguous) {
    }

    record Suspects(List<SuspectRow> largestOrAmbiguous, List<SuspectRow> equalToDifference) {
    }

    record AnalysisRow(long rowNumber, String externalId, BigDecimal principal, BigDecimal balance, LocalDate dueDate,
                       String product) {
    }

    record DebtorOutlier(long rowNumber, String externalId, BigDecimal principal, BigDecimal debtorMedian) {
    }

    record ContractSum(String contractNumber, long lines, BigDecimal installments, BigDecimal contractAmount,
                       long firstRow, String firstExternalId) {
    }

    /**
     * @param rowsToImport      linhas válidas não excluídas
     * @param newTitles         dessas, sem título carregado no credor e sem situação PAGO/CANCELADO (serão criados)
     * @param existingTitles    dessas, com título já carregado no credor (atualizados se mudaram)
     * @param holdBySituation   com situação SUSPENSO/CONTESTADO (o título fica suspenso)
     * @param closedBySituation com situação PAGO/CANCELADO (não cria; suspende o existente para conciliação)
     */
    record Impact(long rows, long rowsToImport, long rowsWithError, long rowsExcluded, long newTitles,
                  long existingTitles, long holdBySituation, long closedBySituation, BigDecimal principalToImport,
                  BigDecimal balanceToImport) {
    }
}
