package br.com.gestorcobranca.compliance.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.util.Set;
import java.util.UUID;

/**
 * Busca restrita de usuários atribuíveis como responsável por uma solicitação de titular (LGPD art. 18): quem atende
 * solicitações escolhe o responsável sem precisar de {@code USER_VIEW} (o cadastro completo de usuários). Devolve
 * <b>somente</b> id e nome de exibição — nunca e-mail, telefone, perfis, escopo ou situação — e só usuários elegíveis
 * pela mesma regra que {@link DataSubjectRequestUseCases#assign} e {@link DataSubjectRequestUseCases#start} aplicam ao
 * gravar: usuário ativo, com {@code DATA_SUBJECT_REQUEST_MANAGE} e acesso a todos os credores (os comandos da
 * solicitação alcançam todos os dados do titular na assessoria). Exige de quem consulta o mesmo alcance exigido para
 * atribuir.
 */
public interface DataSubjectRequestAssigneeUseCases {

    /** Limite de resultados por página. */
    int MAX_PAGE_SIZE = 50;

    /** Limite de ids na resolução de nomes já escolhidos. */
    int MAX_IDS = 50;

    /**
     * @param query trecho do nome (sem diferenciar maiúsculas e acentos); vazio lista todos
     * @param ids   quando informado, só esses ids (até {@value #MAX_IDS}) entre os elegíveis — para exibir o nome do
     *              que já está escolhido
     */
    PageResponse<AssigneeView> search(String query, Set<UUID> ids, PageQuery page);

    /** Leitura mínima: id e nome de exibição. */
    record AssigneeView(UUID id, String name) {
    }
}
