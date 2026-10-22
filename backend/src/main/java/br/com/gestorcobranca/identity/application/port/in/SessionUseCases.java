package br.com.gestorcobranca.identity.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Consulta e revogação de sessões (do próprio usuário ou, por administradores, de terceiros). */
public interface SessionUseCases {

    List<SessionView> mySessions();

    void revokeMySession(UUID sessionId);

    List<SessionView> sessionsOf(UUID userId);

    /** Revoga todas as sessões ativas de um usuário (ex.: suspeita de comprometimento). */
    int revokeAllOf(UUID userId, String reason);

    record SessionView(UUID id, String status, boolean current, boolean mfaVerified, Instant startedAt,
                       Instant lastSeenAt, Instant absoluteExpiresAt, String clientIp, String userAgent) {
    }
}
