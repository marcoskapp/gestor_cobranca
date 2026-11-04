package br.com.gestorcobranca.publicapi.application.port.in;

import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.TrafficTotals;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Controle dos tokens de integração de uma assessoria pela equipe da plataforma, para bloquear consumo indevido
 * (docs/negocio/api-publica.md §9.3). Consulta com {@code PLATFORM_BILLING_VIEW}; pausa, retomada e revogação com
 * {@code PLATFORM_API_TOKENS_MANAGE} — ambas exclusivas da plataforma e conferidas também pelo tenant de quem chama.
 * Motivo obrigatório em toda ação.
 * <p>
 * A <b>pausa</b> é reversível e tem precedência sobre a assessoria: o token é recusado na autenticação (403
 * {@code auth.token_paused}, medido como tráfego recusado, nunca cobrado), a assessoria o vê como "Pausado pela
 * plataforma" e não pode retomá-lo (pode alterar, rotacionar e revogar). A retomada automática é opcional
 * ({@code resumeAt}, até 90 dias): vale na autenticação a partir do instante agendado e é registrada em seguida. A
 * <b>revogação</b> é a mesma da assessoria: definitiva e imediata. O efeito vale na requisição seguinte em todas as
 * instâncias (a autenticação lê o token no banco a cada requisição). Cada ação vai às duas trilhas de auditoria, ao
 * log de acesso da plataforma, ao log de negócio da assessoria e gera aviso por e-mail a quem administra os tokens
 * na assessoria.
 */
public interface PlatformTokenControlUseCases {

    /** Todos os tokens da assessoria (ativa ou suspensa), com o tráfego do ciclo em andamento da plataforma. */
    List<PlatformTokenView> tokens(UUID tenantId);

    /** @param resumeAt retomada automática (opcional; no futuro, até 90 dias) */
    PlatformTokenView pause(UUID tenantId, UUID tokenId, String reason, Instant resumeAt);

    PlatformTokenView resume(UUID tenantId, UUID tokenId, String reason);

    /** Revogação definitiva e imediata, igual à da assessoria, marcada como feita pela plataforma. */
    PlatformTokenView revoke(UUID tenantId, UUID tokenId, String reason);

    /** Pausa, com o mesmo motivo e a mesma retomada, todos os tokens ativos da assessoria. */
    BulkResult pauseAll(UUID tenantId, String reason, Instant resumeAt);

    /** Retoma todas as pausas da assessoria (a revogação não é desfeita). */
    BulkResult resumeAll(UUID tenantId, String reason);

    /**
     * @param state         ACTIVE, PAUSED, REVOKED ou EXPIRED
     * @param pausedBy      usuário da plataforma que pausou
     * @param cycle         tráfego do token no ciclo em andamento da cobrança da plataforma (autenticado, cobrável e
     *                      recusado)
     */
    record PlatformTokenView(UUID id, UUID creditorId, String creditorName, String name, String prefix,
                             Set<String> scopes, String state, Instant createdAt, Instant expiresAt,
                             Instant lastUsedAt, Instant pausedAt, UUID pausedBy, String pauseReason,
                             Instant resumeAt, Instant revokedAt, boolean revokedByPlatform, String revokeReason,
                             TrafficTotals cycle) {
    }

    /**
     * @param affected tokens pausados ou retomados
     * @param skipped  tokens da assessoria que não estavam na situação exigida (revogados, expirados, já pausados…)
     */
    record BulkResult(int affected, int skipped, List<PlatformTokenView> tokens) {
    }
}
