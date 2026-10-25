package br.com.gestorcobranca.shared.logging.access;

import br.com.gestorcobranca.shared.security.Actor;

import java.util.Map;
import java.util.UUID;

/**
 * Evento de acesso/segurança. IP, user-agent e requestId são obtidos do contexto da requisição
 * pelo {@link AccessLogger}; aqui ficam apenas os dados do fato.
 *
 * @param type       tipo do evento
 * @param success    resultado
 * @param tenantId   tenant (pode ser nulo em falhas de login com tenant desconhecido)
 * @param actorType  USER, DEBTOR, SYSTEM, INTEGRATION ou ANONYMOUS
 * @param actorId    usuário/devedor (nulo se desconhecido)
 * @param sessionId  sessão autenticada
 * @param principal  identificador informado, já mascarado (ex.: e-mail mascarado)
 * @param details    detalhes sem dados pessoais (permissão exigida, recurso, motivo)
 */
public record AccessEvent(
        AccessEventType type,
        boolean success,
        UUID tenantId,
        String actorType,
        UUID actorId,
        UUID sessionId,
        String principal,
        Map<String, String> details) {

    public AccessEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** Mesmo evento com outros detalhes. */
    public AccessEvent withDetails(Map<String, String> newDetails) {
        return new AccessEvent(type, success, tenantId, actorType, actorId, sessionId, principal, newDetails);
    }

    public static AccessEvent of(AccessEventType type, Actor actor, Map<String, String> details) {
        return new AccessEvent(type, isSuccess(type), actor.tenantId(), actor.type().name(), actor.id(),
                actor.sessionId(), null, details);
    }

    /** Evento de um usuário identificado fora de requisição autenticada (ex.: durante o login). */
    public static AccessEvent forUser(AccessEventType type, UUID tenantId, UUID userId, UUID sessionId,
                                      String maskedPrincipal, Map<String, String> details) {
        return new AccessEvent(type, isSuccess(type), tenantId, "USER", userId, sessionId, maskedPrincipal, details);
    }

    /** Evento do devedor no portal fora de requisição autenticada (ex.: abertura da sessão). */
    public static AccessEvent forDebtor(AccessEventType type, UUID tenantId, UUID debtorId, UUID sessionId,
                                        Map<String, String> details) {
        return new AccessEvent(type, isSuccess(type), tenantId, "DEBTOR", debtorId, sessionId, null, details);
    }

    public static AccessEvent anonymous(AccessEventType type, UUID tenantId, String maskedPrincipal, Map<String, String> details) {
        return new AccessEvent(type, isSuccess(type), tenantId, "ANONYMOUS", null, null, maskedPrincipal, details);
    }

    private static boolean isSuccess(AccessEventType type) {
        return switch (type) {
            case LOGIN_FAILURE, LOGIN_BLOCKED, MFA_FAILURE, TOKEN_REUSE_DETECTED, ACCESS_DENIED, SCOPE_DENIED,
                 UNAUTHENTICATED, WEBHOOK_REJECTED, PORTAL_LOGIN_FAILURE, PORTAL_LOCKED, RATE_LIMITED -> false;
            default -> true;
        };
    }
}
