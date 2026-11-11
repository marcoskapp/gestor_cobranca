package br.com.gestorcobranca.communication.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.util.Set;
import java.util.UUID;

/**
 * Operadores a quem a supervisão ({@code INBOX_MANAGE}) pode atribuir uma conversa, pela <b>mesma regra</b> que
 * {@link ConversationUseCases#assign} aplica ao gravar: usuário ativo que vê a caixa de entrada e responde
 * ({@code INBOX_VIEW} e {@code MESSAGE_SEND}) e, com caso ativo, que também pode trabalhar o caso ({@code CASE_WORK} e
 * acesso ao credor do caso, a regra do collection); sem caso, com acesso ao credor da conversa (conversa sem credor:
 * todos os credores). Só id e nome; sem exigir {@code USER_VIEW}; cada consulta vai para o log de acesso
 * ({@code USER_DIRECTORY_QUERIED}), sem o texto buscado.
 */
public interface ConversationAssigneeUseCases {

    /** Limite de resultados por página. */
    int MAX_PAGE_SIZE = 50;

    /** Limite de ids na resolução de nomes já escolhidos. */
    int MAX_IDS = 50;

    /**
     * @param query trecho do nome (sem diferenciar maiúsculas e acentos); vazio lista todos
     * @param ids   quando informado, só esses ids (até {@value #MAX_IDS}) entre os elegíveis
     */
    PageResponse<AssigneeView> search(UUID conversationId, String query, Set<UUID> ids, PageQuery page);

    /** Leitura mínima: id e nome de exibição. */
    record AssigneeView(UUID id, String name) {
    }
}
