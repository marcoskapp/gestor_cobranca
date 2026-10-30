package br.com.gestorcobranca.publicapi.application.port.in;

import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssuedToken;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gestão dos tokens de integração (telas internas): consulta ({@code PUBLIC_API_TOKEN_VIEW}), alteração de
 * configurações, revogação e rotação ({@code PUBLIC_API_TOKEN_MANAGE}). Escopo por credor: leitura fora do escopo →
 * 404; comando → 403.
 */
public interface IntegrationTokenUseCases {

    TokenView get(UUID tokenId);

    /** @param status ACTIVE, REVOKED ou EXPIRED (opcional) */
    PageResponse<TokenView> search(UUID creditorId, String status, PageQuery page);

    TokenView updateSettings(UUID tokenId, UpdateTokenCommand command);

    /** Revogação imediata e definitiva, com motivo. */
    TokenView revoke(UUID tokenId, String reason);

    /**
     * Novo segredo (exibido uma vez). O anterior continua aceito durante a carência ({@code graceMinutes}, 0 a 1440)
     * para o credor trocar a configuração sem parada.
     */
    IssuedToken rotate(UUID tokenId, int graceMinutes);

    /** Catálogo de escopos (para a tela). */
    List<ScopeView> scopes();

    record UpdateTokenCommand(String name, String description, int rateLimitPerMinute, List<String> allowedIps) {
    }

    record ScopeView(String code, String description) {
    }

    /**
     * @param prefix            identificação pública ({@code gck_xxxxxxxxxxxx})
     * @param state             ACTIVE, REVOKED ou EXPIRED
     * @param previousValidUntil fim da carência do segredo anterior (rotação), se houver
     * @param lastUsedAt        último uso (atualizado em lote, até ~1 min de atraso)
     */
    record TokenView(UUID id, UUID creditorId, String name, String description, String prefix, Set<String> scopes,
                     int rateLimitPerMinute, List<String> allowedIps, Instant expiresAt, String state, UUID createdBy,
                     Instant createdAt, Instant lastUsedAt, String lastUsedIp, Instant revokedAt, UUID revokedBy,
                     String revokeReason, Instant rotatedAt, int rotationCount, Instant previousValidUntil) {
    }
}
