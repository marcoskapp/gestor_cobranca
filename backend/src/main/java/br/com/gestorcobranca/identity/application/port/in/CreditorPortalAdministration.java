package br.com.gestorcobranca.identity.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Administração do portal do credor pela assessoria (ADR 0011): liberar o portal para um credor e convidar, mudar o
 * perfil, refazer o acesso e revogar as pessoas do credor. Exige {@code CREDITOR_PORTAL_MANAGE} e o credor no escopo de
 * quem administra. O convite vai só por e-mail — o link nunca aparece para a assessoria (decisão 1395).
 */
public interface CreditorPortalAdministration {

    CreditorPortalView status(UUID creditorId);

    CreditorPortalView enable(UUID creditorId);

    /** Desativa o portal do credor: revoga todas as sessões e invalida os links pendentes. */
    CreditorPortalView disable(UUID creditorId);

    /**
     * Convida uma pessoa do credor. O e-mail não pode ser de usuário da assessoria nem de outra pessoa de credor.
     */
    CreditorUserView invite(UUID creditorId, InviteCommand command);

    /** Novo convite para quem ainda não ativou (invalida o anterior). */
    CreditorUserView resendInvitation(UUID creditorId, UUID userId);

    /** Refaz o acesso (perdeu o autenticador): volta ao convite, derruba as sessões e envia um link novo. */
    CreditorUserView resetAccess(UUID creditorId, UUID userId);

    CreditorUserView changeRole(UUID creditorId, UUID userId, String role);

    CreditorUserView revoke(UUID creditorId, UUID userId, String reason);

    record InviteCommand(String name, String email, String role) {
    }

    record CreditorPortalView(UUID creditorId, boolean enabled, Instant enabledAt, List<CreditorUserView> users,
                              List<RoleOption> roles) {
    }

    record CreditorUserView(UUID id, String name, String email, String role, String roleLabel, String status,
                            Instant invitedAt, Instant lastLoginAt, Instant revokedAt, boolean locked) {
    }

    record RoleOption(String code, String label) {
    }
}
