package br.com.gestorcobranca.organization.application.port.in;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Gestão de assessorias (tenants) e de suas configurações gerais. */
public interface TenantAdministration {

    /**
     * Cria uma assessoria. Chamado pelo provisionamento orquestrado no módulo identity, que na
     * mesma transação cria perfis padrão e o administrador. Requer TENANT_MANAGE.
     */
    TenantView createTenant(CreateTenantCommand command);

    /**
     * Cria o tenant da plataforma se ainda não existir (bootstrap da primeira inicialização).
     * Uso interno: não é exposto via API.
     *
     * @return tenant da plataforma e indicação se foi criado agora
     */
    PlatformTenant ensurePlatformTenant(String cnpj);

    record PlatformTenant(TenantView tenant, boolean created) {
    }

    /** Resolução do tenant no login (antes da autenticação). Não exige ator. */
    Optional<TenantView> findBySlug(String slug);

    Optional<TenantView> findById(UUID tenantId);

    /** Assessoria do usuário autenticado. */
    TenantView currentTenant();

    TenantView updateCurrentTenant(UpdateTenantSettingsCommand command);

    /** Plataforma: lista todas as assessorias. Requer TENANT_MANAGE. */
    List<TenantView> listTenants();

    /** Plataforma: suspende ou reativa uma assessoria. Requer TENANT_MANAGE. */
    TenantView changeTenantStatus(UUID tenantId, boolean active, String reason);

    record CreateTenantCommand(String slug, String legalName, String tradeName, String cnpj) {
    }

    record UpdateTenantSettingsCommand(String legalName, String tradeName, LocalTime contactWindowStart,
                                       LocalTime contactWindowEnd, boolean contactOnSaturday, boolean contactOnSunday) {
    }
}
