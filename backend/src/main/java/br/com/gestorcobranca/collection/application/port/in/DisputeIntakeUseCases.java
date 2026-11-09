package br.com.gestorcobranca.collection.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Entrada de contestações abertas fora da assessoria (I14 da revisão de produto de 11/2026): hoje, a contestação que o
 * devedor registra no portal de autonegociação. Chamado pelo job do portal ({@code portal.dispute_intake}) como ator
 * de sistema; não exige permissão de usuário.
 * <p>
 * Regras: o prazo de resposta do CDC conta de {@code receivedAt} (quando o devedor contestou), pela mesma hierarquia
 * de prazos da contestação manual; origem {@code DEBTOR}; bloqueia a cobrança dos títulos; uma contestação por caso
 * ativo do devedor nas carteiras informadas (as que o devedor vê no portal). Idempotente pelo pedido: repetir a chamada não
 * abre outra contestação no mesmo caso.
 */
public interface DisputeIntakeUseCases {

    PortalDisputeResult openFromPortal(PortalDisputeCommand command);

    /**
     * @param requestId   pedido do portal (chave de idempotência)
     * @param protocol    protocolo mostrado ao devedor
     * @param portfolioIds carteiras contestadas: a escolhida pelo devedor ou, sem escolha, as habilitadas no portal
     *                     (vazio = nenhuma; o pedido não abre contestação)
     * @param type        NOT_RECOGNIZED, ALREADY_PAID, WRONG_AMOUNT ou OTHER (nulo = OTHER)
     * @param withNote    o devedor deixou relato no pedido do portal (o texto fica só no portal, com a permissão e o
     *                    log de acesso de lá — não é copiado para a contestação)
     * @param receivedAt  quando o devedor contestou no portal
     */
    record PortalDisputeCommand(UUID requestId, String protocol, UUID debtorId, UUID creditorId,
                                java.util.Set<UUID> portfolioIds, String type, boolean withNote, Instant receivedAt) {

        public PortalDisputeCommand {
            portfolioIds = portfolioIds == null ? java.util.Set.of() : java.util.Set.copyOf(portfolioIds);
        }
    }

    /**
     * @param outcome OPENED (ao menos uma aberta agora ou antes por este pedido), ALREADY_OPEN (os casos já tinham
     *                contestação em aberto), NO_CASE (o devedor não tem caso ativo no credor) ou NO_TITLES (sem
     *                títulos em cobrança para contestar)
     * @param disputeIds contestações abertas por este pedido (inclusive em chamadas anteriores)
     * @param existingDisputeIds contestações em aberto que já existiam nos casos (não foram duplicadas)
     */
    record PortalDisputeResult(String outcome, List<UUID> disputeIds, List<UUID> existingDisputeIds) {
    }
}
