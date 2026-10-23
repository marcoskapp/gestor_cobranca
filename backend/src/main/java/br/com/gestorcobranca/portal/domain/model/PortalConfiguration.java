package br.com.gestorcobranca.portal.domain.model;

import br.com.gestorcobranca.portal.domain.service.BrandingRules;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Portal de autonegociação de um credor: endereço público ({@code slug}), marca (nome, cores com
 * contraste WCAG AA, logotipo), carteiras habilitadas (explícitas — carteira fora da lista não aparece
 * no portal), canais do código de acesso, desafio de conhecimento, validade do link e aviso de
 * privacidade versionado (alterar o texto cria nova versão e exige novo reconhecimento).
 */
@Entity
@Table(name = "portal_configuration", schema = "portal")
public class PortalConfiguration extends TenantScopedEntity {

    public static final int MAX_NOTICE_LENGTH = 8_000;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "slug", nullable = false, length = 60)
    private String slug;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "primary_color", nullable = false, length = 7)
    private String primaryColor;

    @Column(name = "accent_color", nullable = false, length = 7)
    private String accentColor;

    @Column(name = "logo_file_key", length = 500)
    private String logoFileKey;

    @Column(name = "logo_content_type", length = 40)
    private String logoContentType;

    @Column(name = "logo_sha256", length = 64)
    private String logoSha256;

    @Column(name = "otp_channels", nullable = false, length = 60)
    private String otpChannels;

    @Enumerated(EnumType.STRING)
    @Column(name = "knowledge_challenge", nullable = false, length = 20)
    private KnowledgeChallenge knowledgeChallenge;

    @Column(name = "link_validity_hours", nullable = false)
    private int linkValidityHours;

    @Column(name = "privacy_notice_version", nullable = false)
    private int privacyNoticeVersion;

    @Column(name = "privacy_notice_text", nullable = false, columnDefinition = "text")
    private String privacyNoticeText;

    @Column(name = "privacy_notice_sha256", nullable = false, length = 64)
    private String privacyNoticeSha256;

    @Column(name = "support_text", length = 300)
    private String supportText;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "portal_configuration_portfolio", schema = "portal",
            joinColumns = @JoinColumn(name = "configuration_id"))
    @Column(name = "portfolio_id", nullable = false)
    private Set<UUID> portfolioIds = new HashSet<>();

    protected PortalConfiguration() {
    }

    /** Dados editáveis da configuração. */
    public record Settings(String slug, boolean enabled, String displayName, String primaryColor, String accentColor,
                           Set<UUID> portfolioIds, Set<PortalChannel> otpChannels, KnowledgeChallenge knowledgeChallenge,
                           int linkValidityHours, String privacyNoticeText, String supportText) {
    }

    public PortalConfiguration(UUID creditorId, Settings settings) {
        this.creditorId = Guard.requireNonNull(creditorId, "portal.creditor_required", "Credor obrigatório");
        this.privacyNoticeVersion = 0;
        apply(settings);
    }

    /** @return verdadeiro se o aviso de privacidade mudou (nova versão) */
    public boolean apply(Settings s) {
        Guard.require(s != null, "portal.settings_required", "Configuração obrigatória");
        this.slug = BrandingRules.slug(s.slug());
        this.enabled = s.enabled();
        String name = s.displayName() == null ? "" : s.displayName().strip();
        Guard.require(!name.isEmpty() && name.length() <= 80, "portal.display_name_invalid",
                "Nome de exibição obrigatório (até 80 caracteres)");
        this.displayName = name;
        this.primaryColor = BrandingRules.primaryColor(s.primaryColor());
        this.accentColor = BrandingRules.accentColor(s.accentColor());
        Guard.require(s.otpChannels() != null && !s.otpChannels().isEmpty(), "portal.otp_channels_required",
                "Informe ao menos um canal para o código de acesso");
        this.otpChannels = EnumSet.copyOf(s.otpChannels()).stream().map(Enum::name).collect(Collectors.joining(","));
        this.knowledgeChallenge = s.knowledgeChallenge() == null ? KnowledgeChallenge.NONE : s.knowledgeChallenge();
        Guard.require(s.linkValidityHours() >= 1 && s.linkValidityHours() <= 168, "portal.link_validity_invalid",
                "Validade do link entre 1 e 168 horas");
        this.linkValidityHours = s.linkValidityHours();
        String support = s.supportText() == null || s.supportText().isBlank() ? null : s.supportText().strip();
        Guard.require(support == null || support.length() <= 300, "portal.support_text_too_long",
                "Texto de atendimento até 300 caracteres");
        this.supportText = support;
        this.portfolioIds.clear();
        if (s.portfolioIds() != null) {
            this.portfolioIds.addAll(s.portfolioIds());
        }
        String notice = s.privacyNoticeText() == null ? "" : s.privacyNoticeText().strip();
        Guard.require(notice.length() >= 50 && notice.length() <= MAX_NOTICE_LENGTH, "portal.privacy_notice_invalid",
                "Aviso de privacidade obrigatório (50 a " + MAX_NOTICE_LENGTH + " caracteres)");
        boolean changed = !notice.equals(this.privacyNoticeText);
        if (changed) {
            this.privacyNoticeText = notice;
            this.privacyNoticeSha256 = sha256(notice);
            this.privacyNoticeVersion++;
        }
        return changed;
    }

    public void changeLogo(String fileKey, String contentType, String sha256) {
        Guard.require(BrandingRules.LOGO_TYPES.contains(contentType), "portal.logo_type_invalid",
                "Logotipo em PNG, JPEG ou WebP");
        this.logoFileKey = Guard.requireNonNull(fileKey, "portal.logo_required", "Arquivo obrigatório");
        this.logoContentType = contentType;
        this.logoSha256 = sha256;
    }

    /** @return chave do arquivo removido (para remoção física após o commit) ou nula */
    public String removeLogo() {
        String previous = this.logoFileKey;
        this.logoFileKey = null;
        this.logoContentType = null;
        this.logoSha256 = null;
        return previous;
    }

    public boolean coversPortfolio(UUID portfolioId) {
        return portfolioId != null && portfolioIds.contains(portfolioId);
    }

    public Set<PortalChannel> getOtpChannels() {
        return Arrays.stream(otpChannels.split(",")).filter(v -> !v.isBlank()).map(PortalChannel::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PortalChannel.class)));
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public UUID getCreditorId() { return creditorId; }
    public String getSlug() { return slug; }
    public boolean isEnabled() { return enabled; }
    public String getDisplayName() { return displayName; }
    public String getPrimaryColor() { return primaryColor; }
    public String getAccentColor() { return accentColor; }
    public String getLogoFileKey() { return logoFileKey; }
    public String getLogoContentType() { return logoContentType; }
    public String getLogoSha256() { return logoSha256; }
    public KnowledgeChallenge getKnowledgeChallenge() { return knowledgeChallenge; }
    public int getLinkValidityHours() { return linkValidityHours; }
    public int getPrivacyNoticeVersion() { return privacyNoticeVersion; }
    public String getPrivacyNoticeText() { return privacyNoticeText; }
    public String getPrivacyNoticeSha256() { return privacyNoticeSha256; }
    public String getSupportText() { return supportText; }
    public Set<UUID> getPortfolioIds() { return Set.copyOf(new LinkedHashSet<>(portfolioIds)); }
}
