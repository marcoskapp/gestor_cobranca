package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.telephony.domain.model.Call;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Escopo das chamadas: o credor da chamada no escopo do ator (404 fora dele, com
 * {@code SCOPE_DENIED}); chamada recebida ainda sem credor só para o operador que a atendeu ou
 * quem tem todos os credores. Comandos sobre a chamada: o próprio operador ou a supervisão.
 */
@Component
class CallAccess {

    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;

    CallAccess(AccessScope accessScope, CurrentActor currentActor, AccessLogger accessLogger) {
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
    }

    void requireVisible(Call call) {
        if (call.getCreditorId() != null) {
            accessScope.requireVisible("call", call.getId(), call.getCreditorId());
            return;
        }
        if (!accessScope.hasAllCreditors() && !isOperator(call)) {
            accessLogger.log(AccessEvent.of(AccessEventType.SCOPE_DENIED, currentActor.get(),
                    Map.of("resource", "call", "resourceId", call.getId().toString())));
            throw ResourceNotFoundException.of("call", call.getId());
        }
    }

    /** Encerrar/acompanhar: o operador da chamada ou quem supervisiona. */
    void requireOperatorOrSupervisor(Call call) {
        requireVisible(call);
        if (!isOperator(call) && !supervises()) {
            deny(call, "not_call_operator");
        }
        if (call.getCreditorId() != null) {
            accessScope.requireCreditor(call.getCreditorId());
        }
    }

    /** Tabular: somente o operador da chamada (a interação é dele). */
    void requireOperator(Call call) {
        requireVisible(call);
        if (!isOperator(call)) {
            deny(call, "not_call_operator");
        }
    }

    boolean supervises() {
        return currentActor.get().has(Permission.TELEPHONY_SUPERVISE);
    }

    UUID actorId() {
        return currentActor.actorId();
    }

    private boolean isOperator(Call call) {
        Actor actor = currentActor.get();
        return actor.isUser() && actor.id().equals(call.getOperatorId());
    }

    private void deny(Call call, String reason) {
        accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, currentActor.get(),
                Map.of("resource", "call", "resourceId", call.getId().toString(), "reason", reason)));
        throw new ScopeAccessDeniedException();
    }
}
