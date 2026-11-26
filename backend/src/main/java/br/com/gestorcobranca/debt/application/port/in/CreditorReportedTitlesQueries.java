package br.com.gestorcobranca.debt.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "Baixas informadas pelo credor", aba "Pela carga do credor" (H07 parte 2b): títulos cujo saldo a carga do credor
 * zerou (redução de principal, {@code settled_by_import_batch_id}) e títulos suspensos porque o credor os informou como
 * pagos ou cancelados (motivo codificado + lote; o texto livre do motivo nunca é lido). Somente
 * leitura, sem exportação.
 * <p>
 * {@code PAYMENT_VIEW}; ator {@code CREDITOR} e {@code INTEGRATION} recusados (403). Escopo por credor nas linhas e nos
 * totais (RS-94); o lote do filtro é só uma condição dentro do escopo (RS-95). Não lê o schema do ingestion: o rótulo do
 * lote vem da interface do ingestion ({@code ImportBatchUseCases#labels}), pedido pela tela só com {@code IMPORT_VIEW}.
 */
public interface CreditorReportedTitlesQueries {

    int MAX_PERIOD_DAYS = 366;

    int MAX_PAGE_SIZE = 100;

    ReportedTitlesPage search(ReportedTitleFilter filter, PageQuery page);

    /**
     * @param from      primeiro dia (obrigatório; dia em que a carga zerou ou suspendeu, no fuso de operação)
     * @param to        último dia (obrigatório; até {@value #MAX_PERIOD_DAYS} dias depois de {@code from})
     * @param importBatchId só os títulos desta carga (ignorado sem {@code IMPORT_VIEW})
     * @param situation SETTLED_BY_IMPORT, REPORTED_PAID ou REPORTED_CANCELLED (nulo = todas)
     */
    record ReportedTitleFilter(LocalDate from, LocalDate to, UUID creditorId, UUID importBatchId, String situation) {
    }

    /**
     * @param titleReference referência do título no credor (nula sem {@code DEBTOR_VIEW} e sem {@code CASE_VIEW});
     *                       mascarada se for de titular anonimizado
     * @param debtorName     nome de exibição (nulo sem {@code DEBTOR_VIEW}); anonimizado = nome substituto
     * @param importBatchId  lote (nulo sem {@code IMPORT_VIEW})
     * @param amount         valor zerado pela carga (ajuste do principal) ou saldo do título suspenso
     */
    record ReportedTitleItem(UUID titleId, String titleReference, UUID creditorId, UUID portfolioId, UUID debtorId,
                                String debtorName, boolean debtorAnonymized, String situation, UUID importBatchId,
                                Instant occurredAt, BigDecimal amount, String titleStatus) {
    }

    record Totals(long count, BigDecimal amount) {
    }

    record ReportedTitlesPage(PageResponse<ReportedTitleItem> items, Totals totals) {
    }
}
