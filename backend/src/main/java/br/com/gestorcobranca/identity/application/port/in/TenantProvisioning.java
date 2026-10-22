package br.com.gestorcobranca.identity.application.port.in;

import br.com.gestorcobranca.organization.application.port.in.TenantView;

import java.util.UUID;

/**
 * Provisionamento de nova assessoria pela plataforma: cria o tenant, os perfis padrão e o
 * primeiro administrador (que recebe link de ativação), atomicamente.
 */
public interface TenantProvisioning {

    ProvisionedTenant provision(ProvisionTenantCommand command);

    record ProvisionTenantCommand(String slug, String legalName, String tradeName, String cnpj, String adminName,
                                  String adminEmail) {
    }

    record ProvisionedTenant(TenantView tenant, UUID adminUserId) {
    }
}
