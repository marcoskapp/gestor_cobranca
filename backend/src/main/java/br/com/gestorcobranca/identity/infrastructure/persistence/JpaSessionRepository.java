package br.com.gestorcobranca.identity.infrastructure.persistence;

import br.com.gestorcobranca.identity.domain.model.AuthSession;
import br.com.gestorcobranca.identity.domain.model.RefreshToken;
import br.com.gestorcobranca.identity.domain.repository.SessionRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaSessionRepository implements SessionRepository {

    private final AuthSessionJpaRepository sessions;
    private final RefreshTokenJpaRepository tokens;

    JpaSessionRepository(AuthSessionJpaRepository sessions, RefreshTokenJpaRepository tokens) {
        this.sessions = sessions;
        this.tokens = tokens;
    }

    @Override
    public AuthSession save(AuthSession session) {
        return sessions.save(session);
    }

    @Override
    public Optional<AuthSession> findById(UUID id) {
        return sessions.findById(id);
    }

    @Override
    public List<AuthSession> findActiveByUser(UUID userId) {
        return sessions.findByUserIdAndStatus(userId, AuthSession.Status.ACTIVE);
    }

    @Override
    public RefreshToken saveToken(RefreshToken token) {
        return tokens.save(token);
    }

    @Override
    public Optional<RefreshToken> findTokenByHashForUpdate(String tokenHash) {
        return tokens.findByTokenHashForUpdate(tokenHash);
    }

    @Override
    public List<RefreshToken> findActiveTokensBySession(UUID sessionId) {
        return tokens.findBySessionIdAndStatus(sessionId, RefreshToken.Status.ACTIVE);
    }

    @Override
    public void touch(UUID sessionId, Instant now) {
        sessions.touch(sessionId, now);
    }

    @Override
    public List<AuthSession> findOverlapping(java.util.Collection<UUID> userIds, Instant from, Instant to) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return sessions.findOverlapping(userIds, from, to);
    }

    @Override
    public int expireStale(Instant idleBefore, Instant now) {
        return sessions.expireStale(idleBefore, now);
    }
}
