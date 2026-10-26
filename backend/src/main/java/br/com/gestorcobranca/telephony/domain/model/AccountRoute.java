package br.com.gestorcobranca.telephony.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Regra de roteamento: qual conta de telefonia disca num escopo (campanha, carteira, credor ou
 * padrão da assessoria) e qual conta assume em falha transitória da principal (contingência).
 * Uma regra por escopo. {@code creditorId} é o credor do escopo: contas restritas a um credor só
 * servem a regras desse credor.
 */
@Entity
@Table(name = "account_route", schema = "telephony")
public class AccountRoute extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20, updatable = false)
    private RouteScope scopeType;

    @Column(name = "scope_id", updatable = false)
    private UUID scopeId;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "primary_account_id", nullable = false)
    private UUID primaryAccountId;

    @Column(name = "fallback_account_id")
    private UUID fallbackAccountId;

    protected AccountRoute() {
    }

    public AccountRoute(RouteScope scopeType, UUID scopeId, UUID creditorId, TelephonyAccount primary,
                        TelephonyAccount fallback) {
        Guard.require(scopeType != null, "telephony_route.scope_required", "Escopo obrigatório");
        Guard.require((scopeType == RouteScope.DEFAULT) == (scopeId == null), "telephony_route.scope_id_invalid",
                scopeType == RouteScope.DEFAULT ? "O padrão da assessoria não tem identificador"
                        : "Informe o identificador do escopo");
        Guard.require(scopeType != RouteScope.DEFAULT || creditorId == null, "telephony_route.scope_creditor_invalid",
                "O padrão da assessoria não pertence a um credor");
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.creditorId = creditorId;
        assign(primary, fallback);
    }

    /** Troca as contas; ambas precisam servir ao credor do escopo e ser diferentes. */
    public void assign(TelephonyAccount primary, TelephonyAccount fallback) {
        Guard.require(primary != null, "telephony_route.primary_required", "Conta principal obrigatória");
        Guard.require(fallback == null || !fallback.getId().equals(primary.getId()), "telephony_route.fallback_same",
                "A contingência precisa ser outra conta");
        Guard.require(primary.usableFor(creditorId) && (fallback == null || fallback.usableFor(creditorId)),
                "telephony_route.account_other_creditor", "Conta restrita a outro credor");
        this.primaryAccountId = primary.getId();
        this.fallbackAccountId = fallback == null ? null : fallback.getId();
    }

    public RouteScope getScopeType() {
        return scopeType;
    }

    public UUID getScopeId() {
        return scopeId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPrimaryAccountId() {
        return primaryAccountId;
    }

    public UUID getFallbackAccountId() {
        return fallbackAccountId;
    }
}
