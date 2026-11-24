package br.com.gestorcobranca.identity.infrastructure.persistence.creditor;

import br.com.gestorcobranca.identity.domain.model.creditor.CreditorActionToken;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorPasswordHistoryEntry;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorPortalAccess;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorRefreshToken;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorSession;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorUser;
import br.com.gestorcobranca.identity.domain.repository.CreditorAccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaCreditorAccountRepository implements CreditorAccountRepository {

    private final CreditorUserJpaRepository users;
    private final CreditorPortalAccessJpaRepository access;
    private final CreditorSessionJpaRepository sessions;
    private final CreditorRefreshTokenJpaRepository refreshTokens;
    private final CreditorActionTokenJpaRepository actionTokens;
    private final CreditorPasswordHistoryJpaRepository history;

    @PersistenceContext
    private EntityManager entityManager;

    JpaCreditorAccountRepository(CreditorUserJpaRepository users, CreditorPortalAccessJpaRepository access,
                                 CreditorSessionJpaRepository sessions, CreditorRefreshTokenJpaRepository refreshTokens,
                                 CreditorActionTokenJpaRepository actionTokens, CreditorPasswordHistoryJpaRepository history) {
        this.users = users;
        this.access = access;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.actionTokens = actionTokens;
        this.history = history;
    }

    @Override
    public CreditorUser saveUser(CreditorUser user) {
        return users.save(user);
    }

    @Override
    public Optional<CreditorUser> findUser(UUID id) {
        return users.findById(id);
    }

    @Override
    public Optional<CreditorUser> findUserForUpdate(UUID id) {
        return users.findByIdForUpdate(id);
    }

    @Override
    public Optional<CreditorUser> findUserByEmailForUpdate(String normalizedEmail) {
        return users.findByEmailForUpdate(normalizedEmail);
    }

    @Override
    public Optional<CreditorUser> findUserByEmail(String normalizedEmail) {
        return users.findByEmail(normalizedEmail);
    }

    @Override
    public CreditorUser lockAndRefresh(CreditorUser user) {
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        return user;
    }

    @Override
    public boolean existsUserWithEmail(String normalizedEmail) {
        return users.existsByEmail(normalizedEmail);
    }

    @Override
    public List<CreditorUser> findUsersByCreditor(UUID creditorId) {
        return users.findByCreditorIdOrderByNameAsc(creditorId);
    }

    @Override
    public CreditorPortalAccess saveAccess(CreditorPortalAccess portalAccess) {
        return access.save(portalAccess);
    }

    @Override
    public Optional<CreditorPortalAccess> findAccess(UUID creditorId) {
        return access.findByCreditorId(creditorId);
    }

    @Override
    public CreditorSession saveSession(CreditorSession session) {
        return sessions.save(session);
    }

    @Override
    public Optional<CreditorSession> findSession(UUID id) {
        return sessions.findById(id);
    }

    @Override
    public List<CreditorSession> findActiveSessionsByUser(UUID userId) {
        return sessions.findByUserIdAndStatus(userId, CreditorSession.Status.ACTIVE);
    }

    @Override
    public List<CreditorSession> findActiveSessionsByCreditor(UUID creditorId) {
        return sessions.findByCreditorIdAndStatus(creditorId, CreditorSession.Status.ACTIVE);
    }

    @Override
    public void touchSession(UUID sessionId, Instant now) {
        sessions.touch(sessionId, now);
    }

    @Override
    public int expireStaleSessions(Instant idleBefore, Instant now) {
        return sessions.expireStale(idleBefore, now);
    }

    @Override
    public CreditorRefreshToken saveRefreshToken(CreditorRefreshToken token) {
        return refreshTokens.save(token);
    }

    @Override
    public Optional<CreditorRefreshToken> findRefreshTokenByHashForUpdate(String tokenHash) {
        return refreshTokens.findByTokenHashForUpdate(tokenHash);
    }

    @Override
    public List<CreditorRefreshToken> findActiveRefreshTokens(UUID sessionId) {
        return refreshTokens.findBySessionIdAndStatus(sessionId, CreditorRefreshToken.Status.ACTIVE);
    }

    @Override
    public CreditorActionToken saveActionToken(CreditorActionToken token) {
        return actionTokens.save(token);
    }

    @Override
    public Optional<CreditorActionToken> findActionTokenByHashForUpdate(String tokenHash) {
        return actionTokens.findByTokenHashForUpdate(tokenHash);
    }

    @Override
    public List<CreditorActionToken> findUsableActionTokens(UUID userId) {
        return actionTokens.findUsable(userId);
    }

    @Override
    public CreditorPasswordHistoryEntry saveHistory(CreditorPasswordHistoryEntry entry) {
        return history.save(entry);
    }

    @Override
    public List<CreditorPasswordHistoryEntry> findRecentHistory(UUID userId, int limit) {
        return limit <= 0 ? List.of() : history.findRecent(userId, PageRequest.of(0, limit));
    }
}
