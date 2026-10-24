package br.com.gestorcobranca.portal.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Link de acesso de uso único ao portal, enviado ao contato do devedor pelo communication. Só o
 * SHA-256 do token é guardado; o resgate o consome ({@code used_at}) e um novo link revoga os
 * anteriores ainda abertos.
 */
@Entity
@Table(name = "access_link", schema = "portal")
public class AccessLink extends TenantScopedEntity {

    @Column(name = "configuration_id", nullable = false, updatable = false)
    private UUID configurationId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private PortalChannel channel;

    @Column(name = "issued_by_type", nullable = false, length = 20, updatable = false)
    private String issuedByType;

    @Column(name = "issued_by_id", nullable = false, updatable = false)
    private UUID issuedById;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AccessLink() {
    }

    public AccessLink(UUID configurationId, UUID creditorId, UUID debtorId, String tokenHash, PortalChannel channel,
                      String issuedByType, UUID issuedById, Instant expiresAt) {
        this.configurationId = Guard.requireNonNull(configurationId, "portal.configuration_required", "Portal obrigatório");
        this.creditorId = Guard.requireNonNull(creditorId, "portal.creditor_required", "Credor obrigatório");
        this.debtorId = Guard.requireNonNull(debtorId, "portal.debtor_required", "Devedor obrigatório");
        Guard.require(tokenHash != null && tokenHash.length() == 64, "portal.link_invalid", "Token inválido");
        this.tokenHash = tokenHash;
        this.channel = Guard.requireNonNull(channel, "portal.channel_required", "Canal obrigatório");
        this.issuedByType = issuedByType;
        this.issuedById = issuedById;
        this.expiresAt = expiresAt;
    }

    public boolean usableAt(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    public void use(Instant now) {
        Guard.requireState(usableAt(now), "portal.link_unusable", "Link já usado, revogado ou expirado");
        this.usedAt = now;
    }

    public void revoke(Instant now) {
        if (usedAt == null && revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public void sentAs(UUID messageId) {
        this.messageId = messageId;
    }

    public UUID getConfigurationId() { return configurationId; }
    public UUID getCreditorId() { return creditorId; }
    public UUID getDebtorId() { return debtorId; }
    public String getTokenHash() { return tokenHash; }
    public PortalChannel getChannel() { return channel; }
    public String getIssuedByType() { return issuedByType; }
    public UUID getIssuedById() { return issuedById; }
    public UUID getMessageId() { return messageId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
