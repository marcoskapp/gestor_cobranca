package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Carregamento de contestações com o escopo por credor (404 na leitura, 403 nos comandos) e a regra de quem trabalha
 * uma contestação: quem tem {@code DISPUTE_MANAGE}, qualquer uma do escopo; quem tem {@code DISPUTE_WORK}, as
 * atribuídas a si — e assume, ao agir, as sem responsável; atores de sistema e integração, sem regra de responsável.
 */
@Component
class DisputeAccess {

    private final DisputeRepository disputes;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;

    DisputeAccess(DisputeRepository disputes, AccessScope accessScope, CurrentActor currentActor) {
        this.disputes = disputes;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
    }

    Dispute visible(UUID disputeId) {
        Dispute dispute = disputes.findById(disputeId).orElseThrow(() -> ResourceNotFoundException.of("dispute", disputeId));
        accessScope.requireVisible("dispute", disputeId, dispute.getCreditorId());
        return dispute;
    }

    Dispute forCommand(UUID disputeId) {
        Dispute dispute = visible(disputeId);
        accessScope.requireCreditor(dispute.getCreditorId());
        return dispute;
    }

    /**
     * Exige que o ator possa trabalhar a contestação agora.
     *
     * @return true se o ator deve assumir a contestação (sem responsável e com {@code DISPUTE_WORK})
     */
    boolean requireWork(Dispute dispute) {
        Actor actor = currentActor.get();
        if (!actor.isUser() || actor.has(Permission.DISPUTE_MANAGE)) {
            return false;
        }
        if (!actor.has(Permission.DISPUTE_WORK)) {
            accessScope.requirePermission(Permission.DISPUTE_WORK);
        }
        if (dispute.isAssignedTo(actor.id())) {
            return false;
        }
        if (dispute.getAssigneeId() == null) {
            return true;
        }
        throw new ConflictException("dispute.assigned_to_other", "A contestação está com outro responsável");
    }

    void requireManage() {
        Actor actor = currentActor.get();
        if (actor.isUser()) {
            accessScope.requirePermission(Permission.DISPUTE_MANAGE);
        }
    }

    boolean canManage() {
        Actor actor = currentActor.get();
        return !actor.isUser() || actor.has(Permission.DISPUTE_MANAGE);
    }

    Actor actor() {
        return currentActor.get();
    }
}
