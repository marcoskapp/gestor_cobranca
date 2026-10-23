package br.com.gestorcobranca.compliance.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

/**
 * Restrições de contato (M05/M09, RB02, RB07): opt-out, bloqueios jurídicos e operacionais,
 * pedidos do devedor e do credor. Criar ou revogar audita, registra evento de negócio
 * ({@code contact.opt_out} / {@code contact.restricted}) e publica {@code ContactRestrictionChanged}
 * para o recálculo de elegibilidade e a pausa de réguas.
 * <p>
 * Escopo: restrição de um credor exige o credor no escopo; restrição para todos os credores
 * exige acesso a todos os credores. Leitura exige o devedor visível; listagens sem filtro de
 * devedor exigem acesso a todos os credores.
 */
public interface ContactRestrictionUseCases {

    RestrictionView create(CreateRestrictionCommand command);

    RestrictionView revoke(UUID restrictionId, String reason);

    /** Anexa o comprovante (PDF, imagem, áudio ou texto até 20 MB) em armazenamento de evidências. */
    RestrictionView attachEvidence(UUID restrictionId, EvidenceUpload upload);

    /** Abre o comprovante para download; registra {@code FILE_DOWNLOADED}. O chamador fecha o stream. */
    FileContent openEvidence(UUID restrictionId);

    RestrictionView get(UUID restrictionId);

    PageResponse<RestrictionView> search(RestrictionFilter filter, PageQuery page);

    /**
     * <strong>Uso interno do módulo communication; não exposto via web.</strong> Registra o
     * opt-out recebido do provedor (palavra de descadastro como "SAIR" ou "PARAR") para o valor
     * de contato no canal, valendo para qualquer devedor que use aquele valor. Idempotente: um
     * opt-out vigente com o mesmo alcance é devolvido sem nova restrição.
     *
     * @param debtorId devedor associado à conversa, se identificado
     * @param channel  WHATSAPP, EMAIL, SMS ou PHONE
     * @param keyword  palavra recebida (registrada no motivo, nunca o conteúdo da mensagem)
     */
    RestrictionView registerOptOutFromChannel(UUID debtorId, String channel, String contactValue, String keyword);

    /**
     * Dossiê de evidências do devedor (JSON): restrições, inclusive revogadas, e tentativas de
     * contato nos credores do escopo. Registra {@code DATA_EXPORTED}.
     */
    FileContent exportDebtorEvidence(UUID debtorId);

    /**
     * @param creditorId   nulo = todos os credores
     * @param channel      WHATSAPP, EMAIL, SMS, PHONE, PORTAL; nulo = todos os canais
     * @param type         OPT_OUT, DEBTOR_REQUEST, LEGAL_BLOCK, CREDITOR_REQUEST, DECEASED,
     *                     FRAUD_SUSPECTED ou INVALID_CONTACT_HISTORY
     * @param contactType  PHONE ou EMAIL; obrigatório com {@code contactValue} quando o canal é nulo
     * @param contactValue telefone ou e-mail específico a restringir (opcional)
     * @param source       OPERATOR, DEBTOR, CREDITOR ou LEGAL
     * @param startsAt     início (nulo = agora)
     * @param endsAt       fim para restrição temporária (opcional)
     */
    record CreateRestrictionCommand(UUID debtorId, UUID creditorId, String channel, String type, String contactType,
                                    String contactValue, String reason, String source, Instant startsAt,
                                    Instant endsAt) {
    }

    /** Filtros opcionais; {@code status} ACTIVE ou REVOKED. */
    record RestrictionFilter(UUID debtorId, UUID creditorId, String type, String status, String channel) {
    }

    record EvidenceUpload(String fileName, String contentType, long size, InputStream content) {
    }

    /**
     * @param contactMasked valor de contato restrito, mascarado
     * @param effective     produz efeito agora (ativa e dentro da vigência)
     */
    record RestrictionView(UUID id, UUID debtorId, UUID creditorId, String channel, String type, String contactType,
                           String contactMasked, String reason, String source, Instant startsAt, Instant endsAt,
                           String status, boolean effective, Instant revokedAt, UUID revokedBy, String revokedReason,
                           UUID originRequestId, boolean hasEvidence, String evidenceFileName, String evidenceSha256,
                           Instant createdAt, UUID createdBy) {
    }
}
