package br.com.gestorcobranca.organization.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * Contatos do credor para pedidos de contestação (canal formal com o credor dentro da rotina de
 * contestação). Leitura com {@code CREDITOR_VIEW}; manutenção com {@code CREDITOR_MANAGE}; escopo de
 * credores do ator em tudo. Os contatos de uma carteira substituem os do credor quando a carteira tem
 * ao menos um contato principal ativo.
 */
public interface CreditorDisputeContactUseCases {

    /** Todos os contatos do credor (ativos e desativados), do credor e das carteiras. */
    List<DisputeContactView> list(UUID creditorId);

    DisputeContactView create(UUID creditorId, DisputeContactCommand command);

    DisputeContactView update(UUID creditorId, UUID contactId, DisputeContactCommand command);

    DisputeContactView deactivate(UUID creditorId, UUID contactId);

    /**
     * Destinatários de um pedido de contestação: os contatos ativos da carteira, se ela tiver ao menos um
     * principal; senão, os do credor. Lista vazia em {@code to} = sem contato cadastrado.
     */
    Recipients recipients(UUID creditorId, UUID portfolioId);

    /**
     * @param portfolioId carteira do credor (nulo = vale para o credor inteiro); só na criação
     * @param role        PRIMARY (destinatário) ou COPY (cópia)
     */
    record DisputeContactCommand(UUID portfolioId, String name, String email, String phone, String role) {
    }

    record DisputeContactView(UUID id, UUID creditorId, UUID portfolioId, String name, String email, String phone,
                              String role, boolean active, long version) {
    }

    /** @param portfolioLevel os contatos são os da carteira (e não os do credor) */
    record Recipients(List<Recipient> to, List<Recipient> cc, boolean portfolioLevel) {

        public Recipients {
            to = List.copyOf(to);
            cc = List.copyOf(cc);
        }
    }

    record Recipient(UUID contactId, String name, String email) {
    }
}
