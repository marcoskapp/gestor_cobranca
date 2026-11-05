package br.com.gestorcobranca.collection.application.port.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resposta da contestação ao devedor como mensagem <b>transacional</b> (implementada pelo communication): passa pelo
 * porteiro de contato com a categoria {@code TRANSACTIONAL} — a suspensão do contato de cobrança durante a
 * contestação não impede a resposta, mas opt-out, demais restrições, janela de horário e feriados continuam valendo;
 * a tentativa não conta para os limites de cobrança. A mensagem fica na conversa e na linha do tempo do devedor e é
 * registrada no caso como interação. Exige o devedor e o credor no escopo do ator; o autor é o usuário da sessão.
 */
public interface DisputeDebtorMessenger {

    /** Canais e contatos que a resposta usaria agora (sem registrar tentativa). */
    List<ChannelCheck> check(UUID debtorId, UUID creditorId);

    /** Enfileira a mensagem (ou a registra como bloqueada pelo porteiro). */
    Sent send(Notice notice);

    /** Situação atual da mensagem (para o consumo de {@code MessageStatusChanged}). */
    Optional<Status> status(UUID messageId);

    /**
     * @param channel   EMAIL ou WHATSAPP
     * @param contactId contato do devedor (nulo = o principal do canal)
     * @param subject   assunto (e-mail)
     */
    record Notice(UUID disputeId, UUID debtorId, UUID creditorId, UUID caseId, String channel, UUID contactId,
                  String subject, String body) {
    }

    /**
     * @param status        QUEUED ou FAILED (bloqueada na verificação prévia)
     * @param failureCode   {@code CONTACT_DENIED} e motivos em {@code failureDetail}, quando bloqueada
     */
    record Sent(UUID messageId, String status, String failureCode, String failureDetail) {
    }

    record Status(UUID messageId, String status, String failureCode, String failureDetail, Instant statusAt) {
    }

    /**
     * @param allowed       o porteiro permite agora (categoria transacional)
     * @param reasons       motivos da negação
     * @param contactId     contato que seria usado (nulo = sem contato utilizável no canal)
     * @param maskedContact contato mascarado
     */
    record ChannelCheck(String channel, boolean allowed, List<String> reasons, Instant nextAllowedAt, UUID contactId,
                        String maskedContact) {
    }
}
