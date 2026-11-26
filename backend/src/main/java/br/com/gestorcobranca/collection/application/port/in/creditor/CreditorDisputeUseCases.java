package br.com.gestorcobranca.collection.application.port.in.creditor;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contestações no portal do credor (ADR 0011, fatia 5). O credor vem sempre do ator {@code CREDITOR}; o portal só
 * mostra as contestações em que a assessoria pediu a posição do credor, e contestação de outro credor (ou sem pedido)
 * responde 404 igual, com {@code SCOPE_DENIED} quando for de outro credor. A resposta registra a posição e os documentos
 * (pela quarentena), encerra o "aguardando o credor" do pedido e <b>nunca</b> resolve a contestação: quem decide é a
 * assessoria (decisões 784 e 798).
 */
public interface CreditorDisputeUseCases {

    /** Contestações com pedido ao credor: as que aguardam a resposta primeiro (pelo prazo), depois as demais. */
    List<CreditorDisputeSummary> disputes();

    CreditorDisputeDetail dispute(UUID disputeId);

    /**
     * Contestações que aguardam a resposta do credor — o mesmo filtro da situação {@code AWAITING_YOU} da lista
     * ({@link #disputes()}): contestação aberta com pedido na fila, enviado ou com falha de envio. As contagens valem
     * para todas (até o teto de proteção da lista); os itens vêm pelo prazo, os vencidos primeiro, até {@code limit}.
     * Vence logo = prazo de hoje até {@value #DUE_SOON_DAYS} dias, ainda não vencido.
     */
    CreditorDisputesAwaiting awaiting(int limit);

    int DUE_SOON_DAYS = 2;

    /**
     * Documento enviado no pedido da assessoria ou trazido por uma resposta do credor (já liberado da quarentena);
     * registra {@code FILE_DOWNLOADED}. O chamador fecha o stream.
     */
    CreditorDocumentContent download(UUID disputeId, UUID documentId);

    /** Responde a um pedido que aguarda o credor ({@code CREDITOR_DISPUTE_RESPOND}); o instante é o do servidor. */
    CreditorDisputeDetail respond(UUID disputeId, CreditorDisputeResponseCommand command,
                                  List<CreditorDocumentUpload> documents);

    /**
     * @param type             natureza da contestação (NOT_RECOGNIZED, ALREADY_PAID, WRONG_AMOUNT, FRAUD, PRESCRIBED,
     *                         PRODUCT_OR_SERVICE ou OTHER)
     * @param situation        AWAITING_YOU (há pedido sem resposta), ANSWERED (respondida, em análise pela assessoria)
     *                         ou CLOSED (contestação encerrada)
     * @param outcome          resultado quando encerrada: UPHELD, REJECTED, PARTIALLY_UPHELD ou WITHDRAWN (o devedor
     *                         desistiu); nulo enquanto aberta
     * @param requestedDueDate prazo pedido ao credor (o do pedido que aguarda; sem pedido aguardando, o do último)
     * @param overdue          há pedido aguardando com o prazo vencido
     */
    record CreditorDisputeSummary(UUID id, String protocol, String type, String situation, String outcome,
                                  Instant openedAt, BigDecimal disputedAmount, int titleCount,
                                  LocalDate requestedDueDate, boolean overdue, Instant lastResponseAt,
                                  Instant closedAt) {
    }

    /** @param externalId número do título no credor */
    record CreditorDisputedTitle(String externalId, Integer installmentNumber, LocalDate dueDate,
                                 BigDecimal openBalance) {
    }

    record CreditorDisputeDocument(UUID id, String fileName, long size, String sha256) {
    }

    /**
     * Pedido da assessoria ao credor, com o assunto e o texto como foram enviados por e-mail.
     *
     * @param awaiting a assessoria aguarda a resposta deste pedido
     */
    record CreditorDisputeRequest(UUID id, int sequenceNumber, Instant sentAt, LocalDate requestedDueDate,
                                  boolean awaiting, boolean overdue, String subject, String body,
                                  List<CreditorDisputeDocument> documents) {
    }

    /**
     * @param position  CONFIRMS_DEBT, ACCEPTS_CLAIM, PARTIAL ou INFORMATION
     * @param origin    CREDITOR_PORTAL (no portal), INTEGRATION (pela API pública) ou AGENCY (registrada pela
     *                  assessoria a partir de outro canal)
     * @param note      resumo da resposta — só o escrito no portal (o resumo registrado pela assessoria é dela)
     * @param documents documentos já liberados da quarentena
     */
    record CreditorDisputeResponse(UUID id, UUID requestId, String position, String origin, String note,
                                   Instant receivedAt, List<CreditorDisputeDocument> documents,
                                   int documentsInVerification, int documentsRejected) {
    }

    record CreditorDisputeDetail(CreditorDisputeSummary summary, List<CreditorDisputedTitle> titles,
                                 List<CreditorDisputeRequest> requests, List<CreditorDisputeResponse> responses) {
    }

    record CreditorDisputesAwaiting(int total, int dueSoon, int overdue, List<CreditorAwaitingDispute> items) {
    }

    /**
     * @param type             natureza da contestação (como em {@link CreditorDisputeSummary#type()})
     * @param requestedDueDate prazo pedido ao credor (o mais próximo dos pedidos que aguardam)
     */
    record CreditorAwaitingDispute(UUID id, String protocol, String type, LocalDate requestedDueDate, boolean overdue,
                                   boolean dueSoon) {
    }

    /** @param position CONFIRMS_DEBT, ACCEPTS_CLAIM, PARTIAL ou INFORMATION */
    record CreditorDisputeResponseCommand(UUID requestId, String position, String note) {
    }

    record CreditorDocumentUpload(String fileName, String contentType, long size, InputStream content) {
    }

    record CreditorDocumentContent(String fileName, String contentType, long size, String sha256,
                                   InputStream content) {
    }
}
