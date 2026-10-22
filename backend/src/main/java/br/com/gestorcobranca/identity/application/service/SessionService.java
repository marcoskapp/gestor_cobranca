package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.SessionUseCases;
import br.com.gestorcobranca.identity.domain.model.AuthSession;
import br.com.gestorcobranca.identity.domain.repository.SessionRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
class SessionService implements SessionUseCases {

    private final SessionRepository sessions;
    private final UserRepository users;
    private final SessionManager sessionManager;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;

    SessionService(SessionRepository sessions, UserRepository users, SessionManager sessionManager,
                   CurrentActor currentActor, AccessLogger accessLogger, AuditTrail auditTrail) {
        this.sessions = sessions;
        this.users = users;
        this.sessionManager = sessionManager;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionView> mySessions() {
        Actor actor = currentActor.get();
        return list(actor.id(), actor.sessionId());
    }

    @Override
    public void revokeMySession(UUID sessionId) {
        Actor actor = currentActor.get();
        AuthSession session = sessions.findById(sessionId)
                .filter(s -> s.getUserId().equals(actor.id()))
                .orElseThrow(() -> ResourceNotFoundException.of("session", sessionId));
        sessionManager.revoke(session, "revoked_by_user");
        accessLogger.log(AccessEvent.of(AccessEventType.SESSION_REVOKED, actor, Map.of("revokedSessionId", sessionId.toString())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionView> sessionsOf(UUID userId) {
        users.findById(userId).orElseThrow(() -> ResourceNotFoundException.of("user", userId));
        return list(userId, currentActor.get().sessionId());
    }

    @Override
    public int revokeAllOf(UUID userId, String reason) {
        users.findById(userId).orElseThrow(() -> ResourceNotFoundException.of("user", userId));
        int revoked = sessionManager.revokeAll(userId, "revoked_by_admin");
        auditTrail.record(AuditEntry.of("user.sessions_revoked", "user", userId).reason(reason).meta("count", revoked));
        accessLogger.log(AccessEvent.of(AccessEventType.SESSION_REVOKED, currentActor.get(),
                Map.of("targetUserId", userId.toString(), "count", String.valueOf(revoked))));
        return revoked;
    }

    private List<SessionView> list(UUID userId, UUID currentSessionId) {
        return sessions.findActiveByUser(userId).stream()
                .sorted(Comparator.comparing(AuthSession::getLastSeenAt).reversed())
                .map(s -> new SessionView(s.getId(), s.getStatus().name(), Objects.equals(s.getId(), currentSessionId),
                        s.isMfaVerified(), s.getStartedAt(), s.getLastSeenAt(), s.getAbsoluteExpiresAt(), s.getClientIp(),
                        s.getUserAgent()))
                .toList();
    }
}
