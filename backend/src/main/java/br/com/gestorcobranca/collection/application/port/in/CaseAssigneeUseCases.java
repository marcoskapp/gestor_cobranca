package br.com.gestorcobranca.collection.application.port.in;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.Permission;

import java.util.Set;
import java.util.UUID;

/**
 * Busca restrita de usuários atribuíveis nos campos de responsável da cobrança (atribuir caso, responsável da tarefa,
 * redistribuição de casos, grupo e redistribuição de contestações). Devolve só id, nome de exibição e se o usuário está
 * ativo — nunca e-mail, perfis ou escopo — e só quem pode ser gravado no campo, pela <b>mesma regra</b> que o serviço
 * aplica ao gravar ({@link #isCaseWorker} e as regras de {@code CaseWorkers}/{@code DisputeAssignments}). Não exige
 * {@code USER_VIEW}; cada consulta vai para o log de acesso ({@code USER_DIRECTORY_QUERIED}), sem o texto buscado.
 */
public interface CaseAssigneeUseCases {

    /** Limite de resultados por página. */
    int MAX_PAGE_SIZE = 50;

    /** Limite de ids na resolução de nomes já escolhidos. */
    int MAX_IDS = 50;

    /** Campo que está sendo preenchido: define quem é elegível e a permissão exigida de quem consulta. */
    enum Purpose {
        /**
         * Operador do caso ou responsável por uma tarefa do caso: ativo, {@code CASE_WORK} e acesso ao credor do caso
         * (exige o caso; quem consulta precisa de {@code CASE_ASSIGN}).
         */
        CASE_WORKER,
        /**
         * Origem da redistribuição de casos: quem tem casos ativos atribuídos nos credores do escopo de quem consulta,
         * ativo ou não (carteira de quem saiu); {@code CASE_ASSIGN}.
         */
        CASE_REDISTRIBUTION_SOURCE,
        /**
         * Destino da redistribuição de casos: ativo, {@code CASE_WORK}, com acesso a ao menos um credor do escopo de
         * quem consulta e diferente da origem ({@code fromUserId}); {@code CASE_ASSIGN}.
         */
        CASE_REDISTRIBUTION_TARGET,
        /**
         * Analista a incluir no grupo de distribuição de contestações: ativo, {@code DISPUTE_WORK} ou
         * {@code DISPUTE_MANAGE}, acesso ao credor do grupo ({@code creditorId}; vazio = grupo geral) e ainda fora do
         * grupo; {@code DISPUTE_MANAGE} e o credor no escopo (grupo geral: todos os credores).
         */
        DISPUTE_POOL_MEMBER,
        /**
         * Origem da redistribuição de contestações: responsáveis por contestações abertas nos credores do escopo, ativos
         * ou não; {@code DISPUTE_MANAGE}.
         */
        DISPUTE_REDISTRIBUTION_SOURCE,
        /**
         * Destino da redistribuição de contestações: analista elegível (ativo, {@code DISPUTE_WORK}/{@code DISPUTE_MANAGE})
         * com acesso a todos os credores das contestações abertas da origem ({@code fromUserId}) no escopo, diferente
         * da origem; {@code DISPUTE_MANAGE}.
         */
        DISPUTE_REDISTRIBUTION_TARGET
    }

    /**
     * @param caseId     caso (obrigatório em {@link Purpose#CASE_WORKER})
     * @param creditorId credor do grupo em {@link Purpose#DISPUTE_POOL_MEMBER} (nulo = grupo geral)
     * @param fromUserId origem já escolhida nos destinos de redistribuição (opcional)
     * @param query      trecho do nome (sem diferenciar maiúsculas e acentos); vazio lista todos
     * @param ids        quando informado, só esses ids (até {@value #MAX_IDS}) entre os elegíveis
     */
    record AssigneeQuery(Purpose purpose, UUID caseId, UUID creditorId, UUID fromUserId, String query, Set<UUID> ids) {
    }

    PageResponse<AssigneeView> search(AssigneeQuery query, PageQuery page);

    /** Leitura mínima: id, nome de exibição e situação (inativos só aparecem como origem de redistribuição). */
    record AssigneeView(UUID id, String name, boolean active) {
    }

    /**
     * Regra única de quem pode trabalhar (receber) um caso do credor: usuário ativo, com {@code CASE_WORK} e acesso ao
     * credor. Vale na atribuição do caso, no responsável da tarefa, na lista {@link Purpose#CASE_WORKER} e na atribuição
     * de conversa com caso (communication).
     */
    static boolean isCaseWorker(UserSummary user, UUID creditorId) {
        return user != null && user.active() && user.permissions().contains(Permission.CASE_WORK.name())
                && user.canAccessCreditor(creditorId);
    }
}
