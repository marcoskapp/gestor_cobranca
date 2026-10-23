package br.com.gestorcobranca.communication.application.port.in;

import java.time.Instant;
import java.util.List;

/**
 * Metas de primeira resposta (SLA da caixa de entrada) por canal, configuráveis pela assessoria.
 * Padrões: WhatsApp e SMS 15 min (aviso) e 60 min (limite); e-mail 4 h e 24 h. A consulta exige
 * {@code INBOX_VIEW}; a alteração, {@code INBOX_MANAGE} e acesso a todos os credores, com
 * auditoria. Os valores vigentes classificam as conversas da caixa de entrada
 * ({@code ConversationView#slaStatus}) e as estatísticas de primeira resposta.
 */
public interface ResponseTargetUseCases {

    /** Um item por canal (WHATSAPP, EMAIL, SMS), com os valores vigentes. */
    List<ResponseTargetView> list();

    ResponseTargetView update(String channel, int warningMinutes, int breachMinutes);

    /** @param customized falso enquanto vale o padrão da plataforma */
    record ResponseTargetView(String channel, int warningMinutes, int breachMinutes, boolean customized,
                              Instant updatedAt) {
    }
}
