package br.com.gestorcobranca.identity.domain.repository;

import br.com.gestorcobranca.identity.domain.model.creditor.CreditorActionToken;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorPasswordHistoryEntry;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorPortalAccess;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorRefreshToken;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorSession;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorUser;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistência da identidade da pessoa do credor (ADR 0011): contas, liberação do portal, sessões e links. */
public interface CreditorAccountRepository {

    CreditorUser saveUser(CreditorUser user);

    Optional<CreditorUser> findUser(UUID id);

    /** Leitura com lock pessimista (login, nova verificação): serializa tentativas da mesma conta. */
    Optional<CreditorUser> findUserForUpdate(UUID id);

    Optional<CreditorUser> findUserByEmailForUpdate(String normalizedEmail);

    Optional<CreditorUser> findUserByEmail(String normalizedEmail);

    /** Trava a linha da conta já carregada e recarrega o estado gravado por outra transação desde a leitura. */
    CreditorUser lockAndRefresh(CreditorUser user);

    boolean existsUserWithEmail(String normalizedEmail);

    List<CreditorUser> findUsersByCreditor(UUID creditorId);

    CreditorPortalAccess saveAccess(CreditorPortalAccess access);

    Optional<CreditorPortalAccess> findAccess(UUID creditorId);

    CreditorSession saveSession(CreditorSession session);

    Optional<CreditorSession> findSession(UUID id);

    List<CreditorSession> findActiveSessionsByUser(UUID userId);

    List<CreditorSession> findActiveSessionsByCreditor(UUID creditorId);

    /** Registra atividade da sessão sem incrementar a versão (não conflita com requisições paralelas). */
    void touchSession(UUID sessionId, Instant now);

    /** Expira sessões inativas ou vencidas (job de limpeza). */
    int expireStaleSessions(Instant idleBefore, Instant now);

    CreditorRefreshToken saveRefreshToken(CreditorRefreshToken token);

    Optional<CreditorRefreshToken> findRefreshTokenByHashForUpdate(String tokenHash);

    List<CreditorRefreshToken> findActiveRefreshTokens(UUID sessionId);

    CreditorActionToken saveActionToken(CreditorActionToken token);

    Optional<CreditorActionToken> findActionTokenByHashForUpdate(String tokenHash);

    List<CreditorActionToken> findUsableActionTokens(UUID userId);

    CreditorPasswordHistoryEntry saveHistory(CreditorPasswordHistoryEntry entry);

    List<CreditorPasswordHistoryEntry> findRecentHistory(UUID userId, int limit);
}
