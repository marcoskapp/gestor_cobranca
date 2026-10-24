package br.com.gestorcobranca.portal.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gestão do portal por credor ({@code PORTAL_MANAGE}, credor no escopo): marca, carteiras
 * habilitadas, canais do código, desafio de conhecimento, validade do link, aviso de privacidade,
 * logotipo, funil e sessões (revogação). Alterações auditadas.
 */
public interface PortalConfigurationUseCases {

    List<ConfigurationView> list();

    ConfigurationView get(UUID configurationId);

    ConfigurationView create(ConfigurationCommand command);

    ConfigurationView update(UUID configurationId, ConfigurationCommand command);

    ConfigurationView uploadLogo(UUID configurationId, LogoUpload upload);

    ConfigurationView removeLogo(UUID configurationId);

    /** Logotipo atual (pré-visualização na gestão, mesmo com o portal desabilitado). O chamador fecha o stream. */
    PortalAccessUseCases.LogoContent logo(UUID configurationId);

    /** Funil do período (datas no fuso de operação, inclusive). */
    FunnelView funnel(UUID configurationId, LocalDate from, LocalDate to);

    PageResponse<SessionSummary> sessions(UUID configurationId, UUID debtorId, PageQuery page);

    SessionSummary revokeSession(UUID sessionId, String reason);

    /**
     * @param otpChannels        WHATSAPP, SMS, EMAIL
     * @param knowledgeChallenge NONE, BIRTH_DATE ou CONTRACT_SUFFIX
     * @param portfolioIds       carteiras do credor habilitadas no portal (vazio = nenhuma)
     */
    record ConfigurationCommand(UUID creditorId, String slug, boolean enabled, String displayName, String primaryColor,
                                String accentColor, Set<UUID> portfolioIds, Set<String> otpChannels,
                                String knowledgeChallenge, int linkValidityHours, String privacyNoticeText,
                                String supportText) {
    }

    record LogoUpload(String fileName, String contentType, long size, InputStream content) {
    }

    record ConfigurationView(UUID id, UUID creditorId, String slug, boolean enabled, String displayName,
                             String primaryColor, String accentColor, boolean hasLogo, String logoSha256,
                             Set<UUID> portfolioIds, List<String> otpChannels, String knowledgeChallenge,
                             int linkValidityHours, int privacyNoticeVersion, String privacyNoticeText,
                             String supportText, String publicUrl, Instant updatedAt, long version) {
    }

    /**
     * Etapas do funil por sessão: acesso → simulação → aceite (acordo formalizado) → Pix emitido →
     * pagamento confirmado.
     */
    record FunnelView(UUID configurationId, LocalDate from, LocalDate to, long accesses, long simulations,
                      long acceptances, long pixIssued, long paid, long serviceRequests) {
    }

    /** @param status ACTIVE, ENDED, REVOKED ou EXPIRED */
    record SessionSummary(UUID id, UUID debtorId, String method, String status, Instant createdAt, Instant lastSeenAt,
                          Instant expiresAt, Instant endedAt, String endReason) {
    }
}
