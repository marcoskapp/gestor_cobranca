package br.com.gestorcobranca.shared.events.debt;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Recuperação judicial, recuperação extrajudicial ou falência do devedor pessoa jurídica registrada, convolada ou
 * encerrada (Lei 11.101/2005; cobrança B2B §7), publicado pelo módulo debt na transação da mudança. O collection
 * reavalia a elegibilidade dos casos ativos do devedor em todos os credores (motivo {@code DEBTOR_INSOLVENCY}),
 * relendo o estado atual no debt — o booleano do evento não é a fonte (eventos podem chegar fora de ordem).
 * Sem dado pessoal: só ids e o tipo do processo.
 *
 * @param active o devedor tem processo ativo depois da mudança
 * @param kind   tipo do processo afetado ({@code JUDICIAL_RECOVERY}, {@code EXTRAJUDICIAL_RECOVERY},
 *               {@code BANKRUPTCY})
 */
public record DebtorInsolvencyChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID debtorId,
        boolean active,
        String kind) implements IntegrationEvent {
}
