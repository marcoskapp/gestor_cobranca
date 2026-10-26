package br.com.gestorcobranca.telephony.domain.repository;

import br.com.gestorcobranca.telephony.domain.model.AccountRoute;
import br.com.gestorcobranca.telephony.domain.model.RouteScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRouteRepository {

    AccountRoute save(AccountRoute route);

    AccountRoute saveAndFlush(AccountRoute route);

    Optional<AccountRoute> findById(UUID id);

    void delete(AccountRoute route);

    Optional<AccountRoute> findByScopeTypeAndScopeId(RouteScope scopeType, UUID scopeId);

    Optional<AccountRoute> findFirstByScopeType(RouteScope scopeType);

    /** Regra do escopo; {@code scopeId} nulo somente para {@code DEFAULT}. */
    default Optional<AccountRoute> findByScope(RouteScope scopeType, UUID scopeId) {
        if (scopeType == RouteScope.DEFAULT) {
            return findFirstByScopeType(RouteScope.DEFAULT);
        }
        return scopeId == null ? Optional.empty() : findByScopeTypeAndScopeId(scopeType, scopeId);
    }

    List<AccountRoute> findAllByOrderByScopeTypeAsc();

    /** Regras que usam a conta como principal ou contingência. */
    List<AccountRoute> findUsingAccount(UUID accountId);
}
