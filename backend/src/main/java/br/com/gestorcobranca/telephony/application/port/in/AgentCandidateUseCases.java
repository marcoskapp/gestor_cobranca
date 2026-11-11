package br.com.gestorcobranca.telephony.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.util.Set;
import java.util.UUID;

/**
 * Busca restrita de usuários que podem ser habilitados como operador na telefonia: quem configura ramais
 * ({@code TELEPHONY_MANAGE}) escolhe o usuário sem precisar de {@code USER_VIEW} (o cadastro completo de usuários).
 * Devolve <b>somente</b> id e nome de exibição — nunca e-mail, telefone, perfis, escopo ou situação — e só usuários
 * elegíveis pela mesma regra que {@link AgentUseCases#configure} aplica ao gravar: usuário ativo com
 * {@code TELEPHONY_USE}.
 */
public interface AgentCandidateUseCases {

    /** Limite de resultados por página. */
    int MAX_PAGE_SIZE = 50;

    /** Limite de ids na resolução de nomes já escolhidos. */
    int MAX_IDS = 50;

    /**
     * @param query trecho do nome (sem diferenciar maiúsculas e acentos); vazio lista todos
     * @param ids   quando informado, só esses ids (até {@value #MAX_IDS}) entre os elegíveis — para exibir o nome do
     *              que já está escolhido
     */
    PageResponse<CandidateView> search(String query, Set<UUID> ids, PageQuery page);

    /** Leitura mínima: id e nome de exibição. */
    record CandidateView(UUID id, String name) {
    }
}
