package br.com.gestorcobranca.compliance.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porteiro de contato (RB02, RB07): contrato central consultado por communication, campaign,
 * telephony e collection antes de <strong>qualquer</strong> contato com o devedor. Considera
 * restrições (devedor, canal, credor, valor de contato, vigência), política vigente do credor ou
 * da assessoria (janela de horário e dias no fuso de operação, feriados cadastrados, limites
 * diário e semanal, intervalo mínimo) e a anonimização do devedor.
 * <p>
 * Limites e intervalo contam as tentativas do devedor no canal em todos os credores.
 * Exige que o devedor e o credor estejam no escopo do ator (consumidores e jobs executam como
 * sistema, com escopo total).
 */
public interface ContactGate {

    /** Somente consulta: não registra tentativa (ex.: operador verificando antes de ligar). */
    ContactDecision evaluate(ContactRequest request);

    /**
     * Avalia e, se permitido, registra a tentativa na mesma transação, sob lock por
     * (assessoria, devedor): chamadas simultâneas para o mesmo devedor são serializadas e nunca
     * ultrapassam os limites. Deve ser chamado imediatamente antes do envio/discagem; o instante
     * da tentativa é o do pedido (nulo = agora), que precisa estar a até 5 minutos do relógio.
     */
    ContactDecision authorizeAndRecord(ContactRequest request);

    /**
     * @param channel       WHATSAPP, EMAIL, SMS, PHONE ou PORTAL
     * @param contactValue  telefone ou e-mail a usar (opcional; verifica restrições daquele valor)
     * @param at            instante pretendido (nulo = agora)
     * @param origin        OPERATOR, CAMPAIGN ou SYSTEM
     * @param referenceType agregado que origina o contato (ex.: {@code message}); opcional
     * @param referenceId   id do agregado; obrigatório com {@code referenceType}
     */
    record ContactRequest(UUID debtorId, UUID creditorId, String channel, String contactValue, Instant at,
                          String origin, String referenceType, UUID referenceId) {

        public static ContactRequest of(UUID debtorId, UUID creditorId, String channel, String contactValue, Instant at,
                                        String origin) {
            return new ContactRequest(debtorId, creditorId, channel, contactValue, at, origin, null, null);
        }
    }

    /**
     * @param reasons       motivos da negação (vazio quando permitido)
     * @param nextAllowedAt primeiro instante em que o contato passa a ser permitido; nulo quando
     *                      permitido agora ou quando o bloqueio não tem fim previsto
     * @param attemptId     tentativa registrada ({@code authorizeAndRecord} permitido); senão nulo
     */
    record ContactDecision(boolean allowed, List<DenialReason> reasons, Instant nextAllowedAt, UUID attemptId) {

        public ContactDecision {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    enum DenialReason {
        RESTRICTED_DEBTOR,
        RESTRICTED_CHANNEL,
        RESTRICTED_CONTACT_VALUE,
        OUTSIDE_WINDOW,
        HOLIDAY,
        DAILY_LIMIT,
        WEEKLY_LIMIT,
        MIN_INTERVAL,
        DEBTOR_ANONYMIZED
    }
}
