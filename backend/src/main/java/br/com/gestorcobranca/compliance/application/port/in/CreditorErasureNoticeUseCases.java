package br.com.gestorcobranca.compliance.application.port.in;

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
     */
    record NoticeView(UUID id, UUID debtorId, UUID creditorId, String creditorName, String origin, UUID originId,
                      Instant erasedAt, String status, Instant notifiedAt, String notifiedChannel, UUID notifiedBy,
                      String notifiedByName, Instant registeredAt, List<NoticeItemView> items, String message) {
    }

    /** @param reference referência do título no credor, com CPF/CNPJ embutido mascarado */
    record NoticeItemView(UUID portfolioId, String portfolioName, String reference, Integer installmentNumber) {
    }
}
