package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.out.DebtorCaseLock;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.OwnerType;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Carregamento de casos com o escopo por credor (404 na leitura fora do escopo, 403 nos comandos)
 * e a regra de quem pode trabalhar um caso: o responsável; qualquer operador quando o caso está
 * sem operador (fila, sem dono ou com o assistente — ele passa a ser o responsável); quem tem
 * {@code CASE_ASSIGN} em qualquer caso; atores de sistema e integrações sem restrição de dono.
 */
@Component
class CaseAccess {

    private final CollectionCaseRepository cases;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final DebtorCaseLock lock;

    CaseAccess(CollectionCaseRepository cases, AccessScope accessScope, CurrentActor currentActor, DebtorCaseLock lock) {
        this.cases = cases;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.lock = lock;
    }

    /** Lock do devedor para fluxos que carregam o caso por outro caminho (promessa, evento). */
    void lockDebtor(UUID debtorId) {
        lock.lock(currentActor.tenantId(), debtorId);
    }

    /**
     * Para comandos: obtém o lock do devedor do caso <b>antes</b> de carregá-lo (serializa com os
     * consumidores de eventos do mesmo devedor), verifica a visibilidade e o escopo de comando.
     */
    CollectionCase forCommand(UUID caseId) {
        cases.findDebtorIdById(caseId).ifPresent(debtorId -> lock.lock(currentActor.tenantId(), debtorId));
        CollectionCase c = visible(caseId);
        accessScope.requireCreditor(c.getCreditorId());
        return c;
    }

    CollectionCase visible(UUID caseId) {
        CollectionCase c = cases.findById(caseId).orElseThrow(() -> ResourceNotFoundException.of("collection_case", caseId));
        accessScope.requireVisible("collection_case", caseId, c.getCreditorId());
        return c;
    }

    /**
     * Exige que o ator possa trabalhar o caso agora.
     *
     * @return true se o ator é um operador que deve assumir o caso (sem operador no momento)
     */
    boolean requireWorkable(CollectionCase c) {
        Actor actor = currentActor.get();
        if (!actor.isUser()) {
            return false;
        }
        if (c.isAssignedTo(actor.id())) {
            return false;
        }
        if (c.getAssigneeUserId() == null) {
            return c.getOwnerType() != OwnerType.USER;
        }
        if (actor.has(Permission.CASE_ASSIGN)) {
            return false;
        }
        throw new ConflictException("case.assigned_to_other", "O caso está atribuído a outro operador");
    }

    /** Restrição de credores para listagens (nulo = sem restrição). */
    Set<UUID> creditorFilter() {
        return accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
    }

    Actor actor() {
        return currentActor.get();
    }
}
