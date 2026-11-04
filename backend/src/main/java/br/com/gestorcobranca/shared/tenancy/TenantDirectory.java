package br.com.gestorcobranca.shared.tenancy;

import java.util.List;
import java.util.UUID;

/**
 * Lista os tenants ativos para jobs que precisam percorrer todas as assessorias (selagem de
 * auditoria, réguas, vencimentos). Implementado pelo módulo {@code organization}.
 * Jobs devem iterar tenants explicitamente e executar cada um via {@link TenantExecution}.
 */
public interface TenantDirectory {

    List<UUID> activeTenantIds();

    /**
     * Todas as assessorias, inclusive suspensas (e o tenant da plataforma). Para visões da plataforma que precisam
     * mostrar também as suspensas (ex.: tráfego da API no faturamento da plataforma) — jobs de negócio usam
     * {@link #activeTenantIds()}.
     */
    List<UUID> allTenantIds();
}
