package br.com.gestorcobranca.identity.infrastructure.persistence;

import br.com.gestorcobranca.identity.domain.model.ActionToken;
import br.com.gestorcobranca.identity.domain.model.PasswordHistoryEntry;
import br.com.gestorcobranca.identity.domain.repository.CredentialRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaCredentialRepository implements CredentialRepository {

    private final ActionTokenJpaRepository actionTokens;
    private final PasswordHistoryJpaRepository history;
    private final Clock clock;

    JpaCredentialRepository(ActionTokenJpaRepository actionTokens, PasswordHistoryJpaRepository history, Clock clock) {
        this.actionTokens = actionTokens;
        this.history = history;
        this.clock = clock;
    }

    @Override
    public ActionToken saveActionToken(ActionToken token) {
        return actionTokens.save(token);
    }

    @Override
    public Optional<ActionToken> findActionTokenByHashForUpdate(String tokenHash) {
        return actionTokens.findByTokenHashForUpdate(tokenHash);
    }

    @Override
    public List<ActionToken> findUsableActionTokens(UUID userId, ActionToken.Purpose purpose) {
        return actionTokens.findUsable(userId, purpose, Instant.now(clock));
    }

    @Override
    public PasswordHistoryEntry saveHistory(PasswordHistoryEntry entry) {
        return history.save(entry);
    }

    @Override
    public List<PasswordHistoryEntry> findRecentHistory(UUID userId, int limit) {
        return history.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(limit, 1)));
    }
}
