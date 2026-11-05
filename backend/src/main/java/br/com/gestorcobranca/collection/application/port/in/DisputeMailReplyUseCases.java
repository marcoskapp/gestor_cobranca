package br.com.gestorcobranca.collection.application.port.in;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorResponseView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceContent;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Respostas do credor recebidas por e-mail ("resposta recebida por e-mail, a confirmar"). O sistema nunca resolve nada
 * sozinho: o analista confirma a posição do credor (registra a resposta, origem {@code EMAIL}, e encerra o "aguardando
 * o credor" do pedido) ou descarta com motivo; a gestão ({@code DISPUTE_MANAGE}) decide os e-mails em quarentena.
 * Escopo de credores em tudo; o original (.eml) só sai com registro no log de acesso.
 */
public interface DisputeMailReplyUseCases {

    /** E-mails recebidos para os pedidos da contestação, do mais antigo ao mais novo. */
    List<MailReplyView> list(UUID disputeId);

    /** Confirma a posição do credor a partir do e-mail (os anexos liberados já são evidências do credor). */
    CreditorResponseView confirm(UUID disputeId, UUID replyId, ConfirmCommand command);

    /** Descarta com motivo: aguardando confirmação (quem trabalha a contestação) ou em quarentena (gestão). */
    MailReplyView dismiss(UUID disputeId, UUID replyId, String note);

    /** Gestão: libera para o analista um e-mail em quarentena por motivo liberável, depois de conferir o original. */
    MailReplyView release(UUID disputeId, UUID replyId, String note);

    /** Gestão: e-mails em quarentena no escopo de credores, do mais antigo ao mais novo. */
    PageResponse<MailReplyView> quarantined(PageQuery page);

    /** Original (.eml) como chegou, para conferência e cadeia de custódia (log de acesso FILE_DOWNLOADED). */
    EvidenceContent original(UUID disputeId, UUID replyId);

    /**
     * @param position CONFIRMS_DEBT, ACCEPTS_CLAIM, PARTIAL ou INFORMATION
     * @param note     resumo da resposta do credor, escrito pelo analista
     */
    record ConfirmCommand(String position, String note) {
    }

    /**
     * @param sender              remetente (contato do credor)
     * @param text                texto do e-mail convertido em texto puro (o HTML nunca é exibido)
     * @param status              PENDING_CONFIRMATION, QUARANTINED, CONFIRMED ou DISMISSED
     * @param quarantineReason    motivo da quarentena (nulo se não esteve em quarentena)
     * @param releasable          a gestão pode liberar (motivo liberável e ainda em quarentena)
     * @param authenticated       DMARC (ou SPF + DKIM) aprovado
     * @param evidenceIds         anexos liberados, já evidências do credor
     * @param attachmentsInVerification anexos aguardando a verificação de malware
     * @param attachmentsRejected anexos recusados (tipo, tamanho ou malware): ficam só no original
     */
    record MailReplyView(UUID id, UUID disputeId, UUID requestId, int requestSequence, UUID creditorId, String sender,
                         String subject, String text, boolean textTruncated, Instant receivedAt, String status,
                         String quarantineReason, boolean releasable, boolean authenticated, String spf, String dkim,
                         String dmarc, String virus, String spam, String rawSha256, long rawSize, int attachments,
                         List<UUID> evidenceIds, int attachmentsInVerification, int attachmentsRejected,
                         UUID responseId, UUID decidedBy, String decidedByName, Instant decidedAt, String decisionNote,
                         String releasedByName, Instant releasedAt, String releaseNote) {

        public MailReplyView {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }
}
