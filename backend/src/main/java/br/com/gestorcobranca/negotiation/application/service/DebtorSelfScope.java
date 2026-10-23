package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;

import java.util.Map;
import java.util.UUID;

/**
 * Escopo estrito do devedor autenticado no portal ({@code Actor.ActorType.DEBTOR}): além do credor
 * do portal (escopo de credores do ator), só o <strong>próprio</strong> devedor. Defesa em
 * profundidade — o módulo portal já passa apenas o id da sessão —, aplicada no guard, nas leituras
 * por id e nas listagens. A recusa é 404 (não revela a existência) com {@code SCOPE_DENIED}.
 */
final class DebtorSelfScope {

    private DebtorSelfScope() {
    }

    static boolean isDebtor(Actor actor) {
        return actor.type() == Actor.ActorType.DEBTOR;
    }

    /** Sem efeito para outros atores; para o devedor, exige que o recurso seja dele. */
    static void require(Actor actor, UUID debtorId, AccessLogger accessLogger) {
        if (isDebtor(actor) && !actor.id().equals(debtorId)) {
            accessLogger.log(AccessEvent.of(AccessEventType.SCOPE_DENIED, actor,
                    Map.of("resource", "debtor", "reason", "portal_other_debtor")));
            throw new ResourceNotFoundException("debtor", debtorId);
        }
    }

    /** Leitura por id: o recurso de outro devedor "não existe" para o devedor do portal. */
    static void requireOwn(Actor actor, String resource, UUID resourceId, UUID debtorId, AccessLogger accessLogger) {
        if (isDebtor(actor) && !actor.id().equals(debtorId)) {
            accessLogger.log(AccessEvent.of(AccessEventType.SCOPE_DENIED, actor,
                    Map.of("resource", resource, "resourceId", String.valueOf(resourceId), "reason", "portal_other_debtor")));
            throw new ResourceNotFoundException(resource, resourceId);
        }
    }

    /** Filtro de devedor das listagens: o devedor do portal só lista o que é dele. */
    static UUID debtorFilter(Actor actor, UUID requested) {
        return isDebtor(actor) ? actor.id() : requested;
    }
}
