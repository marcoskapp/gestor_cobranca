package br.com.gestorcobranca.identity.application.port.in;

import br.com.gestorcobranca.organization.application.port.in.TenantView;

import java.time.Instant;
import java.util.UUID;

/**
 * Provisionamento de nova assessoria pela plataforma: cria o tenant, os perfis padrão e o
 * primeiro administrador (que recebe link de ativação), atomicamente.
 */
public interface TenantProvisioning {

    ProvisionedTenant provision(ProvisionTenantCommand command);

    /**
     * Reenvia o convite (link de ativação, 72 h, uso único) do administrador inicial de uma assessoria
     * que ainda não tem administrador ativo. Depois que a assessoria tem um administrador ativo, ela
     * administra os próprios usuários e a plataforma não intervém (segregação).
     */
    AdminInvitation resendAdminInvitation(UUID tenantId, String reason);

    record ProvisionTenantCommand(String slug, String legalName, String tradeName, String cnpj, String adminName,
                                  String adminEmail) {
    }

    record ProvisionedTenant(TenantView tenant, UUID adminUserId) {
    }

    /** @param maskedEmail destino do convite, mascarado (o e-mail completo não é devolvido) */
    record AdminInvitation(UUID tenantId, UUID adminUserId, String maskedEmail, Instant expiresAt) {
    }
}
