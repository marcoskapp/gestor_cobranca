package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.telephony.domain.model.AccountRoute;
import br.com.gestorcobranca.telephony.domain.model.Agent;
import br.com.gestorcobranca.telephony.domain.model.RouteScope;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.AccountRouteRepository;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Qual conta disca (decisão 561): a regra mais específica com conta utilizável — campanha →
 * carteira → credor → padrão da assessoria. Numa regra, a principal; se ela está inativa ou o
 * provedor não disca neste ambiente, a contingência assume como principal; sem nenhuma, a regra
 * seguinte. No clique para ligar o ramal do operador também decide (o ramal pertence a uma
 * plataforma): vale a primeira conta, entre a principal, a contingência e as contas em que o
 * operador tem ramal específico, pela qual ele é alcançável.
 */
@Component
class AccountRouter {

    private final AccountRouteRepository routes;
    private final TelephonyAccountRepository accounts;
    private final ProviderResolver resolver;
    private final AgentEndpoints endpoints;

    AccountRouter(AccountRouteRepository routes, TelephonyAccountRepository accounts, ProviderResolver resolver,
                  AgentEndpoints endpoints) {
        this.routes = routes;
        this.accounts = accounts;
        this.resolver = resolver;
        this.endpoints = endpoints;
    }

    /**
     * @param primary  conta que disca
     * @param fallback contingência para falha transitória que não criou a chamada (pode faltar)
     * @param scope    escopo da regra aplicada
     */
    record Route(TelephonyAccount primary, TelephonyAccount fallback, RouteScope scope) {

        Optional<TelephonyAccount> fallbackAccount() {
            return Optional.ofNullable(fallback);
        }
    }

    /** Discador: campanha, carteira, credor e padrão. */
    Optional<Route> forCampaign(UUID campaignId, UUID creditorId, UUID portfolioId) {
        return resolve(scopes(campaignId, portfolioId, creditorId));
    }

    /** Caso sem campanha (contingência do clique para ligar, chamadas avulsas). */
    Optional<Route> forCase(UUID creditorId, UUID portfolioId) {
        return resolve(scopes(null, portfolioId, creditorId));
    }

    /** Conta da campanha pela regra da própria campanha (validação do operador ao entrar nela). */
    Optional<TelephonyAccount> campaignAccount(UUID campaignId) {
        return routes.findByScope(RouteScope.CAMPAIGN, campaignId).flatMap(this::fromRule).map(Route::primary);
    }

    /**
     * Clique para ligar: conta pela qual o operador é alcançável, preferindo a regra do caso.
     *
     * @throws ConflictException {@code telephony.not_configured} sem conta utilizável;
     *                           {@code telephony.agent_not_on_account} se o operador não é alcançável por nenhuma
     */
    Route forClickToCall(Agent agent, UUID creditorId, UUID portfolioId) {
        Optional<Route> route = forCase(creditorId, portfolioId);
        List<TelephonyAccount> candidates = new ArrayList<>();
        route.ifPresent(r -> {
            candidates.add(r.primary());
            r.fallbackAccount().ifPresent(candidates::add);
        });
        Map<UUID, TelephonyAccount> byId = new LinkedHashMap<>();
        candidates.forEach(a -> byId.putIfAbsent(a.getId(), a));
        List<UUID> own = endpoints.specificAccounts(agent).stream().filter(id -> !byId.containsKey(id)).toList();
        accounts.findAllByIdIn(own).stream()
                .filter(a -> resolver.usable(a) && a.usableFor(creditorId))
                .sorted(java.util.Comparator.comparingInt(a -> own.indexOf(a.getId())))
                .forEach(a -> byId.putIfAbsent(a.getId(), a));
        if (byId.isEmpty()) {
            throw notConfigured();
        }
        List<TelephonyAccount> reachable = byId.values().stream()
                .filter(a -> endpoints.extensionFor(agent, a.getId()).isPresent()).toList();
        if (reachable.isEmpty()) {
            throw new ConflictException("telephony.agent_not_on_account",
                    "O operador não tem ramal em nenhuma conta de telefonia que atende este caso");
        }
        TelephonyAccount chosen = reachable.getFirst();
        TelephonyAccount fallback = reachable.size() > 1 ? reachable.get(1) : null;
        return new Route(chosen, fallback, route.map(Route::scope).orElse(RouteScope.DEFAULT));
    }

    static ConflictException notConfigured() {
        return new ConflictException("telephony.not_configured", "Nenhuma conta de telefonia ativa para o credor");
    }

    private Optional<Route> resolve(List<Scope> scopes) {
        for (Scope scope : scopes) {
            Optional<Route> route = routes.findByScope(scope.type(), scope.id()).flatMap(this::fromRule);
            if (route.isPresent()) {
                return route;
            }
        }
        return Optional.empty();
    }

    private Optional<Route> fromRule(AccountRoute rule) {
        Optional<TelephonyAccount> primary = accounts.findById(rule.getPrimaryAccountId()).filter(resolver::usable);
        Optional<TelephonyAccount> fallback = rule.getFallbackAccountId() == null ? Optional.empty()
                : accounts.findById(rule.getFallbackAccountId()).filter(resolver::usable);
        if (primary.isPresent()) {
            return Optional.of(new Route(primary.get(), fallback.orElse(null), rule.getScopeType()));
        }
        return fallback.map(f -> new Route(f, null, rule.getScopeType()));
    }

    private static List<Scope> scopes(UUID campaignId, UUID portfolioId, UUID creditorId) {
        List<Scope> scopes = new ArrayList<>(4);
        if (campaignId != null) {
            scopes.add(new Scope(RouteScope.CAMPAIGN, campaignId));
        }
        if (portfolioId != null) {
            scopes.add(new Scope(RouteScope.PORTFOLIO, portfolioId));
        }
        if (creditorId != null) {
            scopes.add(new Scope(RouteScope.CREDITOR, creditorId));
        }
        scopes.add(new Scope(RouteScope.DEFAULT, null));
        return scopes;
    }

    private record Scope(RouteScope type, UUID id) {
    }
}
