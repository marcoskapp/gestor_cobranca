package br.com.gestorcobranca.identity.application.port.in;

import br.com.gestorcobranca.shared.web.PageQuery;
import br.com.gestorcobranca.shared.web.PageResponse;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Administração de usuários da assessoria (F01). */
public interface UserManagementUseCases {

    /** Cria o usuário e envia o link de ativação por e-mail. */
    UserView create(CreateUserCommand command);

    UserView update(UUID userId, UpdateUserCommand command);

    UserView assignRoles(UUID userId, Set<UUID> roleIds);

    UserView defineCreditorScope(UUID userId, boolean allCreditors, Set<UUID> creditorIds);

    UserView disable(UUID userId, String reason);

    UserView enable(UUID userId);

    UserView unlock(UUID userId);

    UserView resetMfa(UUID userId, String reason);

    /** Reenvia o link de ativação (usuário pendente) ou envia link de redefinição. */
    void sendCredentialLink(UUID userId);

    UserView get(UUID userId);

    PageResponse<UserView> search(String text, String status, UUID roleId, UUID businessUnitId, PageQuery page);

    record CreateUserCommand(String name, String email, UUID businessUnitId, Set<UUID> roleIds, boolean allCreditors,
                             Set<UUID> creditorIds) {
    }

    record UpdateUserCommand(String name, UUID businessUnitId) {
    }

    record UserView(UUID id, String name, String email, String status, boolean locked, Instant lockedUntil,
                    boolean mfaEnabled, Instant lastLoginAt, Instant passwordChangedAt, UUID businessUnitId,
                    Set<UUID> roleIds, Set<String> roleCodes, boolean allCreditors, Set<UUID> creditorIds,
                    Instant createdAt) {
    }
}
