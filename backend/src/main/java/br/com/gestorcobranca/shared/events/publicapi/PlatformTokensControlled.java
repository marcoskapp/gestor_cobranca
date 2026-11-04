package br.com.gestorcobranca.shared.events.publicapi;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A equipe da plataforma pausou, retomou ou revogou tokens de integração de uma assessoria (ou a retomada automática
 * agendada venceu). {@code tenantId} é o da assessoria dona dos tokens. O {@code identity} reage avisando por e-mail
 * quem administra os tokens na assessoria, sem que o módulo {@code publicapi} dependa dele.
 * <p>
 * Só identificadores: o motivo não viaja no evento (a assessoria o vê na tela do token).
 *
 * @param action        PAUSED, RESUMED, AUTO_RESUMED ou REVOKED
 * @param tokenIds      tokens afetados (um, ou vários na pausa/retomada de todos os tokens)
 * @param tokenPrefixes prefixos públicos ({@code gck_…}) dos tokens, na mesma ordem — identificação sem segredo
 * @param resumeAt      retomada automática agendada (só em PAUSED), se houver
 */
public record PlatformTokensControlled(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        String action,
        List<UUID> tokenIds,
        List<String> tokenPrefixes,
        Instant resumeAt) implements IntegrationEvent {

    public static final String PAUSED = "PAUSED";
    public static final String RESUMED = "RESUMED";
    public static final String AUTO_RESUMED = "AUTO_RESUMED";
    public static final String REVOKED = "REVOKED";

    public PlatformTokensControlled {
        tokenIds = List.copyOf(tokenIds);
        tokenPrefixes = List.copyOf(tokenPrefixes);
    }
}
