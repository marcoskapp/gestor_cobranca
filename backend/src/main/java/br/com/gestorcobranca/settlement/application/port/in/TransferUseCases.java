package br.com.gestorcobranca.settlement.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Repasses ao credor do líquido de demonstrativos aprovados (ou recebimentos do credor quando o líquido é
 * negativo), com comprovante obrigatório, e saldo a repassar por credor. Registrar/cancelar:
 * {@code SETTLEMENT_TRANSFER}; consultar: {@code SETTLEMENT_VIEW}. Escopo por credor.
 */
public interface TransferUseCases {

    /** {@code Idempotency-Key}: mesma chave e conteúdo → o mesmo repasse. */
    TransferView register(TransferCommand command, EvidenceUpload evidence, String idempotencyKey);

    TransferView cancel(UUID transferId, String reason);

    TransferView get(UUID transferId);

    PageResponse<TransferView> search(UUID creditorId, PageQuery page);

    /** Comprovante do repasse. Registra {@code FILE_DOWNLOADED}. */
    EvidenceContent openEvidence(UUID transferId);

    /** Saldo a repassar por credor (demonstrativos aprovados menos repasses). */
    List<BalanceView> balances(UUID creditorId);

    record TransferCommand(UUID statementId, BigDecimal amount, LocalDate transferredOn, String bankReference,
                           String note) {
    }

    record EvidenceUpload(String fileName, String contentType, long size, InputStream content) {
    }

    record EvidenceContent(String fileName, String contentType, long size, InputStream content) {
    }

    /** @param direction AGENCY_TO_CREDITOR ou CREDITOR_TO_AGENCY */
    record TransferView(UUID id, UUID statementId, UUID creditorId, String direction, BigDecimal amount,
                        LocalDate transferredOn, String bankReference, String note, String evidenceName,
                        String evidenceSha256, UUID registeredBy, Instant registeredAt, String status,
                        Instant cancelledAt, UUID cancelledBy, String cancelReason) {
    }

    /**
     * @param toTransfer  a repassar ao credor (demonstrativos com líquido positivo)
     * @param toReceive   a receber do credor (líquido negativo: comissão/honorários sobre pagamentos feitos a ele)
     */
    record BalanceView(UUID creditorId, BigDecimal toTransfer, BigDecimal toReceive, List<StatementBalance> statements) {
    }

    record StatementBalance(UUID statementId, int sequenceNumber, LocalDate periodStart, LocalDate periodEnd,
                            String status, BigDecimal netTransfer, BigDecimal transferred, BigDecimal outstanding) {
    }
}
