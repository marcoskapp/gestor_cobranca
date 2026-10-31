package br.com.gestorcobranca.legal.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.util.Set;
import java.util.UUID;

/**
 * Busca restrita de usuários atribuíveis nos campos do jurídico (decisão 686, aprovada pelo responsável): quem tem
 * {@code LEGAL_MANAGE} escolhe o advogado interno do caso, o responsável por um prazo ou o advogado a vincular a um
 * escritório parceiro sem precisar de {@code USER_VIEW} (o cadastro completo de usuários). Devolve <b>somente</b> id e
 * nome de exibição — nunca e-mail, telefone, perfis, escopo ou situação — e só usuários ativos da própria assessoria
 * (RLS) elegíveis para o campo, pela mesma regra que o serviço aplica ao gravar.
 */
public interface LegalAssigneeUseCases {

    /** Limite de resultados por página. */
    int MAX_PAGE_SIZE = 50;

    /** Limite de ids na resolução de nomes já escolhidos. */
    int MAX_IDS = 50;

    /** Campo que está sendo preenchido: define quem é elegível. */
    enum Purpose {
        /**
         * Advogado interno que conduz o caso: {@code LEGAL_MANAGE}, acesso ao credor do caso e não vinculado a escritório
         * parceiro (exige o caso e usuário interno).
         */
        MATTER_RESPONSIBLE,
        /**
         * Responsável por um prazo do caso: {@code LEGAL_MANAGE} e acesso ao credor do caso; para o advogado de escritório
         * parceiro, somente os membros do próprio escritório (exige o caso).
         */
        DEADLINE_RESPONSIBLE,
        /**
         * Advogado a vincular a um escritório parceiro: {@code LEGAL_MANAGE} e ainda sem escritório (exige usuário interno
         * com todos os credores, como a gestão de escritórios).
         */
        FIRM_MEMBER
    }

    /**
     * @param matterId caso jurídico (obrigatório para os campos do caso)
     * @param query    trecho do nome (sem diferenciar maiúsculas e acentos); vazio lista todos
     * @param ids      quando informado, só esses ids (até {@value #MAX_IDS}) entre os elegíveis — para exibir o nome do
     *                 que já está escolhido
     */
    PageResponse<AssigneeView> search(Purpose purpose, UUID matterId, String query, Set<UUID> ids, PageQuery page);

    /** Leitura mínima: id e nome de exibição. */
    record AssigneeView(UUID id, String name) {
    }
}
