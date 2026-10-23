package br.com.gestorcobranca.payment.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Conciliação (F15, {@code RECONCILIATION_MANAGE}): importação de extrato/arquivo de retorno
 * (CSV genérico; CNAB 240/400 como porta futura), conciliação automática por identificador da
 * transação, instrução, pagamento ou parcela, e fila de divergências com resolução auditada.
 * Linhas repetidas (mesma transação em outro arquivo) não geram nova baixa.
 */
public interface ReconciliationUseCases {

    /** Grava o arquivo e agenda o processamento (assíncrono). Mesmo arquivo duas vezes → 409. */
    StatementImportView importStatement(StatementUpload upload);

    StatementImportView getImport(UUID importId);

    PageResponse<StatementImportView> imports(PageQuery page);

    PageResponse<StatementLineView> lines(UUID importId, PageQuery page);

    PageResponse<DivergenceView> divergences(DivergenceFilter filter, PageQuery page);

    DivergenceView getDivergence(UUID divergenceId);

    /**
     * @param resolution MATCHED_PAYMENT (com {@code paymentId}: concilia o pagamento com a linha),
     *                   ACCEPTED ou DISMISSED; justificativa obrigatória
     */
    DivergenceView resolve(UUID divergenceId, ResolveCommand command);

    /** @param format CSV_GENERIC (CNAB_240/CNAB_400: sem leitor disponível) */
    record StatementUpload(String format, UUID creditorId, String fileName, String contentType, long size,
                           InputStream content) {
    }

    record StatementImportView(UUID id, String format, String status, UUID creditorId, String fileName, long fileSize,
                               String fileSha256, UUID requestedBy, Instant createdAt, Instant startedAt,
                               Instant finishedAt, int totalLines, int reconciledLines, int createdPayments,
                               int divergentLines, int ignoredLines, String failureCode) {
    }

    record StatementLineView(UUID id, int lineNumber, LocalDate paidOn, BigDecimal amount, String direction,
                             String transactionId, String reference, String description, String status,
                             UUID paymentId) {
    }

    record DivergenceFilter(String status, String type) {
    }

    record ResolveCommand(String resolution, UUID paymentId, String note) {
    }

    /** @param description descrição da linha do extrato (documentos e contatos mascarados) */
    record DivergenceView(UUID id, String type, String source, String status, UUID creditorId, UUID paymentId,
                          UUID instructionId, UUID statementLineId, String providerReference, BigDecimal amount,
                          BigDecimal expectedAmount, LocalDate occurredOn, String description, String resolution,
                          String resolutionNote, UUID resolvedPaymentId, UUID resolvedBy, Instant resolvedAt,
                          Instant createdAt) {
    }
}
