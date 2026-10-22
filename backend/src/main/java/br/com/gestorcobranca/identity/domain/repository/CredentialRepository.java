package br.com.gestorcobranca.identity.domain.repository;

import br.com.gestorcobranca.identity.domain.model.ActionToken;
import br.com.gestorcobranca.identity.domain.model.PasswordHistoryEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tokens de ação (ativação/redefinição) e histórico de senhas. */
public interface CredentialRepository {

    ActionToken saveActionToken(ActionToken token);

    Optional<ActionToken> findActionTokenByHashForUpdate(String tokenHash);

    List<ActionToken> findUsableActionTokens(UUID userId, ActionToken.Purpose purpose);

    PasswordHistoryEntry saveHistory(PasswordHistoryEntry entry);

    /** Últimas {@code limit} senhas do usuário, mais recentes primeiro. */
    List<PasswordHistoryEntry> findRecentHistory(UUID userId, int limit);
}
