package br.com.gestorcobranca.compliance.application.port.in;

import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Avisos ao credor da anonimização do titular (LGPD art. 18 §6º; SEG-20261124-D01h, decisão 1478). O aviso é aberto
 * só pelo portão da eliminação, na transação da anonimização (não há criação por esta porta); aqui a equipe de
 * atendimento ao titular consulta, copia o texto pronto e registra que avisou o credor (canal e data, sem texto
 * livre). Todos os métodos exigem {@code DATA_SUBJECT_REQUEST_MANAGE} e acesso a todos os credores, como as
 * solicitações de titulares.
 */
public interface CreditorErasureNoticeUseCases {

    PageResponse<NoticeSummary> search(NoticeSearch search, PageQuery page);

    /** Aviso com os títulos citados e o texto pronto. Inexistente → 404. */
    NoticeView get(UUID id);

    /**
     * Registra que o credor foi avisado. Uma vez só ({@code creditor_erasure_notice.already_notified}, 409); data
     * futura ou anterior à anonimização → 422. Grava auditoria.
     */
    NoticeView registerNotified(UUID id, RegisterNotifiedCommand command);

    /** Credores com avisos pendentes, do aviso mais antigo ao mais novo (decisão 1484). */
    List<CreditorPending> pendingByCreditor();

    /**
     * Remessa ao credor (decisão 1484): os avisos pendentes do credor, da eliminação mais antiga à mais nova, até
     * {@link #MAX_REMITTANCE} avisos e {@link #MAX_REMITTANCE_TITLES} títulos (sempre ao menos um aviso), num texto só. Sem pendentes → lista vazia e texto nulo.
     */
    RemittanceView remittance(UUID creditorId);

    /**
     * Registra de uma vez o aviso de vários avisos pendentes do mesmo credor (1 a {@link #MAX_REMITTANCE}); um já
     * registrado → {@code creditor_erasure_notice.already_notified} (409) e nada é gravado; credores diferentes →
     * {@code creditor_erasure_notice.batch_mixed_creditors} (422). Uma auditoria por aviso.
     */
    List<NoticeSummary> registerNotifiedBatch(RegisterBatchCommand command);

    int MAX_REMITTANCE = CreditorErasureNotice.MAX_PER_REMITTANCE;

    /** Teto de títulos citados numa remessa (texto praticável num e-mail); corta no aviso que passaria do teto. */
    int MAX_REMITTANCE_TITLES = 2000;

    /**
     * @param status     PENDING ou NOTIFIED (nulo = todos)
     * @param creditorId nulo = todos
     * @param originId   solicitação do titular ou execução de retenção (nulo = todas)
     */
    record NoticeSearch(String status, UUID creditorId, UUID originId) {
    }

    /** @param channel EMAIL, PHONE, LETTER, MEETING ou OTHER */
    record RegisterNotifiedCommand(Instant notifiedAt, String channel) {
    }

    record RegisterBatchCommand(List<UUID> noticeIds, Instant notifiedAt, String channel) {
    }

    /** @param oldestErasedAt anonimização mais antiga ainda sem aviso registrado */
    record CreditorPending(UUID creditorId, String creditorName, long pending, Instant oldestErasedAt) {
    }

    /**
     * @param noticeIds    avisos que o texto cita (os que o registro em lote deve receber)
     * @param pendingTotal pendentes do credor (maior que {@code noticeIds.size()} quando passa do limite da remessa)
     * @param latestErasedAt anonimização mais recente da remessa (o aviso não pode ser anterior; nula sem pendentes)
     * @param message      texto pronto da remessa (nulo sem pendentes)
     */
    record RemittanceView(UUID creditorId, String creditorName, List<UUID> noticeIds, int titles, long pendingTotal,
                          Instant latestErasedAt, String message) {
    }

    /**
     * @param origin DATA_SUBJECT_REQUEST ou RETENTION
     * @param status PENDING ou NOTIFIED
     */
    record NoticeSummary(UUID id, UUID debtorId, UUID creditorId, String creditorName, String origin, UUID originId,
                         Instant erasedAt, String status, Instant notifiedAt, String notifiedChannel) {
    }

    /**
     * @param message      texto pronto do aviso ao credor (sem nome, documento ou contato do titular)
     * @param notifiedBy   quem registrou o aviso (nulo enquanto pendente)
     * @param registeredAt quando o aviso foi registrado no sistema
     * @param fileRequests lotes da carga bloqueados com o titular deste aviso e o pedido de novo arquivo de cada um
     *                     (evidência, decisões 1497 e 1623), até {@code NewFileRequests.MAX_BATCHES}: primeiro os sem
     *                     pedido, do mais recente ao mais antigo
     * @param fileRequestsTotal quantos lotes há (maior que {@code fileRequests.size()} quando a lista foi cortada)
     */
    record NoticeView(UUID id, UUID debtorId, UUID creditorId, String creditorName, String origin, UUID originId,
                      Instant erasedAt, String status, Instant notifiedAt, String notifiedChannel, UUID notifiedBy,
                      String notifiedByName, Instant registeredAt, List<NoticeItemView> items, String message,
                      List<FileRequestView> fileRequests, int fileRequestsTotal) {
    }

    /**
     * Lote bloqueado com o titular do aviso e o registro do pedido de novo arquivo ao credor.
     *
     * @param protocol   protocolo do arquivo citado no pedido (início do SHA-256)
     * @param inProgress o lote ainda processa (o pedido é registrado depois)
     * @param sentAt     quando o pedido foi enviado (nulo enquanto não registrado)
     * @param sentByName quem registrou o envio (nulo sem o registro ou sem o usuário)
     */
    record FileRequestView(UUID batchId, String protocol, Instant blockedAt, int lines, boolean inProgress,
                           Instant sentAt, String channel, UUID sentBy, String sentByName) {
    }

    /** @param reference referência do título no credor, com CPF/CNPJ embutido mascarado */
    record NoticeItemView(UUID portfolioId, String portfolioName, String reference, Integer installmentNumber) {
    }
}
