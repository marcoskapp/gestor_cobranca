package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseAssigneeUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.security.Permission;

import java.util.Set;
import java.util.UUID;

/**
 * Regras de quem pode receber casos, usadas tanto pela lista de usuários atribuíveis ({@link CaseAssigneeService})
 * quanto pela gravação ({@link CaseWorkService}, {@link TaskService}) — a tela nunca oferece quem o servidor recusaria.
 */
final class CaseWorkers {

    private CaseWorkers() {
    }

    /** Operador do caso ou responsável por tarefa: ativo, {@code CASE_WORK} e acesso ao credor do caso. */
    static boolean canWork(UserSummary user, UUID creditorId) {
        return CaseAssigneeUseCases.isCaseWorker(user, creditorId);
    }

    /**
     * Destino de redistribuição de casos: ativo, {@code CASE_WORK}, diferente da origem e com acesso a ao menos um
     * credor do escopo de quem redistribui (sem isso não receberia nenhum caso).
     *
     * @param actorCreditors credores do escopo de quem redistribui (nulo = todos)
     */
    static boolean canReceiveRedistribution(UserSummary user, UUID fromUserId, Set<UUID> actorCreditors) {
        if (user == null || !user.active() || !user.permissions().contains(Permission.CASE_WORK.name())
                || user.id().equals(fromUserId)) {
            return false;
        }
        if (user.allCreditors()) {
            return actorCreditors == null || !actorCreditors.isEmpty();
        }
        return actorCreditors == null ? !user.creditorIds().isEmpty()
                : user.creditorIds().stream().anyMatch(actorCreditors::contains);
    }
}
