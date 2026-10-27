package br.com.gestorcobranca.telephony.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de chamada normalizado a partir do provedor (webhook ou simulador).
 *
 * @param eventId              id do evento no provedor (idempotência); obrigatório
 * @param type                 {@link Type}
 * @param providerCallId       id da chamada no provedor
 * @param callReference        id interno enviado como referência do cliente (pode faltar)
 * @param amd                  HUMAN, MACHINE ou UNKNOWN (no atendimento, quando houver AMD)
 * @param hangupBy             DEBTOR, OPERATOR, SYSTEM ou PROVIDER (no encerramento)
 * @param cause                código do provedor para falha/encerramento
 * @param from                 número de origem (somente {@code INBOUND}); nunca registrar em log
 * @param recordingRef         referência da gravação ({@code RECORDING_AVAILABLE})
 * @param providerLegId        id da perna da chamada no provedor, quando difere do id devolvido no
 *                             pedido de discagem (ex.: {@code CallUUID} da Plivo); guardado na
 *                             chamada para encerrar/transferir depois
 * @param awaitingInstructions o provedor está esperando, na resposta deste mesmo webhook, as
 *                             instruções da chamada (TwiML/Plivo XML): conexão ao operador e
 *                             encerramento seguem na resposta, sem chamada à API do provedor
 */
public record ProviderCallEvent(String eventId, Type type, String providerCallId, UUID callReference,
                                Instant occurredAt, String amd, String hangupBy, String cause, String from,
                                String recordingRef, Integer recordingDurationSeconds, String providerLegId,
                                boolean awaitingInstructions) {

    public ProviderCallEvent(String eventId, Type type, String providerCallId, UUID callReference, Instant occurredAt,
                             String amd, String hangupBy, String cause, String from, String recordingRef,
                             Integer recordingDurationSeconds) {
        this(eventId, type, providerCallId, callReference, occurredAt, amd, hangupBy, cause, from, recordingRef,
                recordingDurationSeconds, null, false);
    }

    public enum Type {
        RINGING,
        ANSWERED,
        COMPLETED,
        NO_ANSWER,
        BUSY,
        FAILED,
        CANCELLED,
        /** Chamada recebida (retorno do devedor). */
        INBOUND,
        RECORDING_AVAILABLE
    }
}
