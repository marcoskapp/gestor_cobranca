package br.com.gestorcobranca.collection.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Contestações (F21). Abrir uma contestação bloqueante vincula os títulos no módulo debt
 * (fora de réguas e negociações), coloca o caso em {@code DISPUTED} e suspende o contato de
 * cobrança (elegibilidade {@code DISPUTE_OPEN}) até a resolução ou desistência. Evidências vão
 * para o armazenamento de evidências (categoria EVIDENCE) com SHA-256. O prazo de resposta é o
 * informado ou o padrão da assessoria; o job diário sinaliza as vencidas. Publica
 * {@code DisputeStatusChanged}.
 */
public interface DisputeUseCases {

    DisputeView open(UUID caseId, OpenDisputeCommand command);

    /** Encaminha para análise (credor / jurídico). */
    DisputeView startReview(UUID disputeId);

    /** @param outcome UPHELD, REJECTED ou PARTIALLY_UPHELD */
    DisputeView resolve(UUID disputeId, String outcome, String note);

    DisputeView withdraw(UUID disputeId, String note);

    /** PDF, imagem, áudio ou texto até o tamanho máximo configurado; até 20 por contestação. */
    EvidenceView attachEvidence(UUID disputeId, EvidenceUpload upload);

    List<EvidenceView> listEvidence(UUID disputeId);

    /** Abre a evidência para download; registra {@code FILE_DOWNLOADED}. O chamador fecha o stream. */
    EvidenceContent openEvidence(UUID disputeId, UUID evidenceId);

    DisputeView get(UUID disputeId);

    List<DisputeView> listByCase(UUID caseId);

    /** @param statuses OPEN, UNDER_REVIEW, RESOLVED, WITHDRAWN (vazio = todos) */
    PageResponse<DisputeView> search(UUID creditorId, Set<String> statuses, Boolean overdue, PageQuery page);

    /**
     * @param titleIds         títulos do caso contestados (vazio = todos os títulos em aberto do caso)
     * @param type             NOT_RECOGNIZED, ALREADY_PAID, WRONG_AMOUNT, FRAUD, PRESCRIBED,
     *                         PRODUCT_OR_SERVICE ou OTHER
     * @param origin           OPERATOR, DEBTOR, CREDITOR ou LEGAL
     * @param blocksCollection suspende a cobrança dos títulos e o contato (padrão: sim)
     * @param responseDueDate  prazo de resposta (nulo = prazo padrão da assessoria)
     */
    record OpenDisputeCommand(Set<UUID> titleIds, String type, String origin, String description,
                              Boolean blocksCollection, LocalDate responseDueDate) {

        public OpenDisputeCommand {
            titleIds = titleIds == null ? Set.of() : Set.copyOf(titleIds);
        }
    }

    record EvidenceUpload(String fileName, String contentType, long size, InputStream content) {
    }

    /** O chamador fecha o stream. @param size -1 se desconhecido */
    record EvidenceContent(String fileName, String contentType, long size, InputStream content) {
    }

    record DisputeView(UUID id, UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId, Set<UUID> titleIds,
                       String type, String origin, String status, String description, boolean blocksCollection,
                       BigDecimal disputedAmount, Instant openedAt, LocalDate responseDueDate, boolean overdue,
                       String outcome, String resolutionNote, Instant resolvedAt, UUID resolvedBy, int evidenceCount,
                       long version) {
    }

    record EvidenceView(UUID id, UUID disputeId, String fileName, String contentType, long size, String sha256,
                        Instant createdAt, UUID createdBy) {
    }
}
