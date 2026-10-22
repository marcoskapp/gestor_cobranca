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
}
