package br.com.gestorcobranca.collection.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Posso contatar agora?" para o operador, a partir do caso: consulta o porteiro de contato do
 * compliance ({@code ContactGate#evaluate}, <b>sem</b> registrar tentativa) com o devedor e o
 * credor do caso, no instante atual, e combina com a elegibilidade do caso (contestação
 * bloqueante, suspensão, jurídico, carteira fora de operação). Devolve apenas a decisão, os
 * motivos e o próximo instante permitido — nunca a política de contato (janelas, limites,
 * intervalos) nem o valor do contato.
 * <p>
 * Escopo: o caso precisa estar visível ao ator (404 fora do escopo de credores).
 */
public interface ContactAvailabilityQueries {

    /**
     * @param channel   PHONE, WHATSAPP, SMS ou EMAIL; nulo = os quatro
     * @param contactId contato do devedor que se pretende usar (opcional): verifica também as
     *                  restrições daquele telefone/e-mail nos canais do mesmo tipo
     */
    ContactAvailability check(UUID caseId, String channel, UUID contactId);

    /**
     * @param caseEligible elegibilidade do caso sem o motivo {@code CONTACT_RESTRICTED} (que é
     *                     detalhado canal a canal, com a situação atual, em {@code channels})
     * @param caseReasons  motivos de inelegibilidade do caso (mesmo critério)
     */
    record ContactAvailability(UUID caseId, Instant evaluatedAt, boolean caseEligible, List<String> caseReasons,
                               List<ChannelAvailability> channels) {

        public ContactAvailability {
            caseReasons = List.copyOf(caseReasons);
            channels = List.copyOf(channels);
        }
    }

    /**
     * @param allowed       contato permitido agora: caso elegível e porteiro sem negação
     * @param reasons       motivos do porteiro ({@code ContactGate.DenialReason}); vazio quando o
     *                      porteiro permite
     * @param nextAllowedAt primeiro instante em que o porteiro passa a permitir (janela, feriado,
     *                      limites, intervalo, restrição temporária); nulo se permitido agora ou
     *                      sem fim previsto. Não considera a elegibilidade do caso.
     * @param contactId     contato avaliado neste canal (nulo = só devedor e canal)
     */
    record ChannelAvailability(String channel, boolean allowed, List<String> reasons, Instant nextAllowedAt,
                               UUID contactId) {

        public ChannelAvailability {
            reasons = List.copyOf(reasons);
        }
    }
}
