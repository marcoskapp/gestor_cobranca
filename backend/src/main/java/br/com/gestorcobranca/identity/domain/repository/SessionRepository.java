package br.com.gestorcobranca.identity.domain.repository;

import br.com.gestorcobranca.identity.domain.model.AuthSession;
import br.com.gestorcobranca.identity.domain.model.RefreshToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistência de sessões e refresh tokens (mesmo agregado lógico). */
public interface SessionRepository {

    AuthSession save(AuthSession session);

    Optional<AuthSession> findById(UUID id);

    List<AuthSession> findActiveByUser(UUID userId);

    RefreshToken saveToken(RefreshToken token);

    /** Leitura com lock pessimista: impede uso concorrente do mesmo refresh token. */
    Optional<RefreshToken> findTokenByHashForUpdate(String tokenHash);

    List<RefreshToken> findActiveTokensBySession(UUID sessionId);

    /** Registra atividade da sessão sem incrementar a versão (não conflita com requisições paralelas). */
    void touch(UUID sessionId, Instant now);

    /** Sessões dos usuários com uso entre os instantes (início antes de {@code to}, atividade após {@code from}). */
    List<AuthSession> findOverlapping(java.util.Collection<UUID> userIds, Instant from, Instant to);

    /** Expira sessões inativas ou vencidas (job de limpeza). */
    int expireStale(Instant idleBefore, Instant now);
}
