package br.com.gestorcobranca.portal.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.UUID;

/**
 * Operação interna do portal:
 * <ul>
 *   <li>{@link #sendAccessLink}: o operador envia ao devedor um link de acesso de uso único pelo
 *       communication ({@code MESSAGE_SEND}, credor no escopo; o envio passa pelo porteiro);</li>
 *   <li>{@link #serviceRequests}: pedidos que vieram do portal ({@code INBOX_VIEW}), inclusive os que
 *       não puderam abrir conversa ({@code UNDELIVERED}).</li>
 * </ul>
 */
public interface PortalOperationsUseCases {

    AccessLinkView sendAccessLink(SendLinkCommand command);

    PageResponse<ServiceRequestSummary> serviceRequests(UUID creditorId, String status, PageQuery page);

    /** @param channel WHATSAPP, SMS ou EMAIL */
    record SendLinkCommand(UUID debtorId, UUID creditorId, String channel) {
    }

    /** @param messageStatus situação da mensagem no communication (QUEUED, FAILED…) */
    record AccessLinkView(UUID linkId, UUID debtorId, UUID creditorId, String channel, Instant expiresAt,
                          UUID messageId, String messageStatus, String messageFailure) {
    }

    /** @param note mensagem do próprio devedor (guardada criptografada; exibida a quem atende a fila) */
    record ServiceRequestSummary(UUID id, String protocol, UUID creditorId, UUID portfolioId, UUID debtorId,
                                 String reason, String preferredChannel, String status, String note,
                                 UUID conversationId, String failureCode, Instant createdAt, Instant forwardedAt) {
    }
}
