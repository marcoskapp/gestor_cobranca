package br.com.gestorcobranca.communication.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Conta de canal de uma assessoria: provedor, remetente e credenciais. Uma conta ativa por canal
 * e credor; {@code creditorId} nulo é a conta padrão da assessoria, usada quando o credor não tem
 * conta própria (remetente por credor).
 * <p>
 * As credenciais do provedor (JSON) ficam criptografadas no banco e nunca são devolvidas pela API;
 * a rotação substitui o conjunto inteiro. O {@code routingKey} (aleatório, 256 bits) identifica a
 * conta nos webhooks do provedor.
 */
@Entity
@Table(name = "channel_account", schema = "communication")
public class ChannelAccount extends TenantScopedEntity {

    public static final int CREDENTIALS_MAX = 4000;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private ChannelType channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30, updatable = false)
    private ProviderType provider;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** E-mail do remetente ou id do número do WhatsApp no provedor. */
    @Column(name = "sender_address", nullable = false, length = 320)
    private String senderAddress;

    @Column(name = "sender_name", length = 120)
    private String senderName;

    @Column(name = "reply_to", length = 320)
    private String replyTo;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "credentials_encrypted", length = 8000)
    private String credentials;

    @Column(name = "credentials_updated_at")
    private Instant credentialsUpdatedAt;

    @Column(name = "routing_key", nullable = false, length = 64, updatable = false)
    private String routingKey;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected ChannelAccount() {
    }

    public ChannelAccount(ChannelType channel, ProviderType provider, UUID creditorId, String name, String senderAddress,
                          String senderName, String replyTo, String credentials, String routingKey, Instant now) {
        Guard.require(channel != null, "channel_account.channel_required", "Canal obrigatório");
        Guard.require(provider != null, "channel_account.provider_required", "Provedor obrigatório");
        Guard.require(provider.supports(channel), "channel_account.provider_channel_mismatch",
                "O provedor %s não atende o canal %s".formatted(provider, channel));
        Guard.require(routingKey != null && routingKey.length() >= 32, "channel_account.routing_key_invalid",
                "Chave de roteamento inválida");
        this.channel = channel;
        this.provider = provider;
        this.creditorId = creditorId;
        this.routingKey = routingKey;
        this.active = true;
        describe(name, senderAddress, senderName, replyTo);
        replaceCredentials(credentials, now);
    }

    public void describe(String name, String senderAddress, String senderName, String replyTo) {
        Guard.require(name != null && !name.isBlank() && name.length() <= 120, "channel_account.name_invalid",
                "Nome obrigatório (até 120 caracteres)");
        Guard.require(senderAddress != null && !senderAddress.isBlank() && senderAddress.length() <= 320,
                "channel_account.sender_required", "Remetente obrigatório");
        Guard.require(senderName == null || senderName.length() <= 120, "channel_account.sender_name_invalid",
                "Nome do remetente até 120 caracteres");
        Guard.require(replyTo == null || replyTo.length() <= 320, "channel_account.reply_to_invalid",
                "Endereço de resposta até 320 caracteres");
        this.name = name.trim();
        this.senderAddress = senderAddress.trim();
        this.senderName = senderName == null || senderName.isBlank() ? null : senderName.trim();
        this.replyTo = replyTo == null || replyTo.isBlank() ? null : replyTo.trim();
    }

    /** Substitui as credenciais (já validadas pelo serviço conforme o provedor). */
    public void replaceCredentials(String credentials, Instant now) {
        Guard.require(credentials == null || credentials.length() <= CREDENTIALS_MAX, "channel_account.credentials_too_long",
                "Credenciais muito longas");
        this.credentials = credentials;
        this.credentialsUpdatedAt = credentials == null ? null : now;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public ProviderType getProvider() {
        return provider;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getName() {
        return name;
    }

    public String getSenderAddress() {
        return senderAddress;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getReplyTo() {
        return replyTo;
    }

    /** Credenciais em claro: uso exclusivo dos adaptadores de provedor; nunca registrar em log. */
    public String getCredentials() {
        return credentials;
    }

    public Instant getCredentialsUpdatedAt() {
        return credentialsUpdatedAt;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public boolean isActive() {
        return active;
    }
}
