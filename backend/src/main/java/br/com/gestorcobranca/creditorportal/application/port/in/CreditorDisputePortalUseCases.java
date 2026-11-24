package br.com.gestorcobranca.creditorportal.application.port.in;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contestações no portal do credor (fatia 5): as que a assessoria pediu a posição do credor, a ficha com o pedido e os
 * documentos dele, e a resposta (posição, resumo e até 5 documentos, pela quarentena). A resposta nunca resolve a
 * contestação. Records próprios, com a lista de campos permitidos ao credor.
 */
public interface CreditorDisputePortalUseCases {

    List<DisputeItem> disputes();

    DisputeDetail dispute(UUID disputeId);

    DocumentContent download(UUID disputeId, UUID documentId);

    DisputeDetail respond(UUID disputeId, RespondCommand command, List<Upload> documents);

    /** @param situation AWAITING_YOU, ANSWERED ou CLOSED; @param outcome UPHELD, REJECTED, PARTIALLY_UPHELD ou WITHDRAWN */
    record DisputeItem(UUID id, String protocol, String type, String situation, String outcome, Instant openedAt,
                       BigDecimal disputedAmount, int titleCount, LocalDate requestedDueDate, boolean overdue,
                       Instant lastResponseAt, Instant closedAt) {
    }

    record TitleItem(String externalId, Integer installmentNumber, LocalDate dueDate, BigDecimal openBalance) {
    }

    record DocumentItem(UUID id, String fileName, long size, String sha256) {
    }

    record RequestItem(UUID id, int sequenceNumber, Instant sentAt, LocalDate requestedDueDate, boolean awaiting,
                       boolean overdue, String subject, String body, List<DocumentItem> documents) {
    }

    /** @param origin CREDITOR_PORTAL, INTEGRATION ou AGENCY; @param note só a escrita no portal */
    record ResponseItem(UUID id, UUID requestId, String position, String origin, String note, Instant receivedAt,
                        List<DocumentItem> documents, int documentsInVerification, int documentsRejected) {
    }

    record DisputeDetail(DisputeItem dispute, List<TitleItem> titles, List<RequestItem> requests,
                         List<ResponseItem> responses) {
    }

    record RespondCommand(UUID requestId, String position, String note) {
    }

    record Upload(String fileName, String contentType, long size, InputStream content) {
    }

    record DocumentContent(String fileName, String contentType, long size, String sha256, InputStream content) {
    }
}
