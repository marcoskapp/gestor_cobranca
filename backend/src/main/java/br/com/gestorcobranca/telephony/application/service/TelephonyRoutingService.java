package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyRoutingUseCases;
import br.com.gestorcobranca.telephony.domain.model.AccountRoute;
import br.com.gestorcobranca.telephony.domain.model.RouteScope;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.AccountRouteRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerCampaignRepository;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Regras de roteamento entre contas. O credor do escopo vem do próprio escopo (credor, credor da
 * carteira, credor da campanha); a regra exige o credor no escopo de quem altera (padrão e campanha
 * sem credor exigem todos os credores) e contas que sirvam a esse credor.
 */
@Service
@Transactional
class TelephonyRoutingService implements TelephonyRoutingUseCases {

    private final AccountRouteRepository routes;
    private final TelephonyAccountRepository accounts;
    private final DialerCampaignRepository campaigns;
    private final CreditorUseCases creditors;
    private final PortfolioUseCases portfolios;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;

    TelephonyRoutingService(AccountRouteRepository routes, TelephonyAccountRepository accounts,
                            DialerCampaignRepository campaigns, CreditorUseCases creditors, PortfolioUseCases portfolios,
                            AccessScope accessScope, AuditTrail auditTrail) {
        this.routes = routes;
        this.accounts = accounts;
        this.campaigns = campaigns;
        this.creditors = creditors;
        this.portfolios = portfolios;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RouteView> list() {
        return routes.findAllByOrderByScopeTypeAsc().stream()
                .filter(r -> r.getCreditorId() == null ? accessScope.hasAllCreditors()
                        : accessScope.canAccessCreditor(r.getCreditorId()))
                .map(TelephonyRoutingService::view).toList();
    }

    @Override
    public RouteView save(SaveRouteCommand command) {
        Guard.require(command != null, "telephony_route.required", "Dados da regra obrigatórios");
        RouteScope scope = TelephonyEnums.parse(RouteScope.class, command.scopeType(), "telephony_route.scope_invalid",
                "Escopo");
        UUID scopeId = scope == RouteScope.DEFAULT ? null : command.scopeId();
        Guard.require(scope == RouteScope.DEFAULT || scopeId != null, "telephony_route.scope_id_invalid",
                "Informe o identificador do escopo");
        UUID creditorId = creditorOf(scope, scopeId);
        if (creditorId == null) {
            accessScope.requireAllCreditors("telephony_route");
        } else {
            accessScope.requireCreditor(creditorId);
        }
        Guard.require(command.primaryAccountId() != null, "telephony_route.primary_required", "Conta principal obrigatória");
        TelephonyAccount primary = account(command.primaryAccountId());
        TelephonyAccount fallback = command.fallbackAccountId() == null ? null : account(command.fallbackAccountId());
        AccountRoute existing = routes.findByScope(scope, scopeId).orElse(null);
        if (existing == null) {
            AccountRoute route = routes.saveAndFlush(new AccountRoute(scope, scopeId, creditorId, primary, fallback));
            auditTrail.record(AuditEntry.of("telephony_route.created", "telephony_route", route.getId())
                    .creditor(creditorId)
                    .created("scopeType", scope)
                    .created("scopeId", scopeId)
                    .created("primaryAccountId", primary.getId())
                    .created("fallbackAccountId", fallback == null ? null : fallback.getId()));
            return view(route);
        }
        UUID beforePrimary = existing.getPrimaryAccountId();
        UUID beforeFallback = existing.getFallbackAccountId();
        existing.assign(primary, fallback);
        routes.save(existing);
        if (!Objects.equals(beforePrimary, existing.getPrimaryAccountId())
                || !Objects.equals(beforeFallback, existing.getFallbackAccountId())) {
            auditTrail.record(AuditEntry.of("telephony_route.updated", "telephony_route", existing.getId())
                    .creditor(creditorId)
                    .change("primaryAccountId", beforePrimary, existing.getPrimaryAccountId())
                    .change("fallbackAccountId", beforeFallback, existing.getFallbackAccountId())
                    .meta("scopeType", scope).meta("scopeId", scopeId));
        }
        return view(existing);
    }

    @Override
    public void delete(UUID routeId) {
        AccountRoute route = routes.findById(routeId)
                .orElseThrow(() -> ResourceNotFoundException.of("telephony_route", routeId));
        if (route.getCreditorId() == null) {
            accessScope.requireAllCreditors("telephony_route");
        } else {
            accessScope.requireVisible("telephony_route", route.getId(), route.getCreditorId());
            accessScope.requireCreditor(route.getCreditorId());
        }
        routes.delete(route);
        auditTrail.record(AuditEntry.of("telephony_route.deleted", "telephony_route", route.getId())
                .creditor(route.getCreditorId())
                .change("primaryAccountId", route.getPrimaryAccountId(), null)
                .change("fallbackAccountId", route.getFallbackAccountId(), null)
                .meta("scopeType", route.getScopeType()).meta("scopeId", route.getScopeId()));
    }

    private UUID creditorOf(RouteScope scope, UUID scopeId) {
        return switch (scope) {
            case DEFAULT -> null;
            case CREDITOR -> creditors.get(scopeId).id();
            case PORTFOLIO -> portfolios.get(scopeId).creditorId();
            case CAMPAIGN -> campaigns.findById(scopeId)
                    .orElseThrow(() -> ResourceNotFoundException.of("dialer_campaign", scopeId)).getCreditorId();
        };
    }

    private TelephonyAccount account(UUID id) {
        TelephonyAccount account = accounts.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("telephony_account", id));
        if (account.getCreditorId() != null) {
            accessScope.requireVisible("telephony_account", account.getId(), account.getCreditorId());
        }
        return account;
    }

    private static RouteView view(AccountRoute r) {
        return new RouteView(r.getId(), r.getScopeType().name(), r.getScopeId(), r.getCreditorId(),
                r.getPrimaryAccountId(), r.getFallbackAccountId(), r.getVersion() == null ? 0 : r.getVersion());
    }
}
