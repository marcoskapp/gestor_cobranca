package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/** Verificações de permissão do módulo (além do {@code @PreAuthorize} do adaptador web) e leitura de enums. */
@Component
class SettlementAccess {

    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;

    SettlementAccess(CurrentActor currentActor, AccessLogger accessLogger) {
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
    }

    void requireAny(Permission... permissions) {
        Actor actor = currentActor.get();
        if (Arrays.stream(permissions).noneMatch(actor::has)) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor,
                    Map.of("permission", Arrays.toString(permissions))));
            throw new ScopeAccessDeniedException();
        }
    }

    Actor actor() {
        return currentActor.get();
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String code) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(code, "Valor inválido: " + value);
        }
    }
}
