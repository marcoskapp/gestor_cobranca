package br.com.gestorcobranca.telephony.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Conta do provedor de telefonia de uma assessoria: provedor, número de origem (bina) e
 * credenciais. A assessoria pode manter várias contas ativas, de provedores diferentes; qual conta
 * disca é decidido pelo roteamento ({@link AccountRoute}). {@code creditorId} preenchido restringe a
 * conta ao credor (bina do credor): só regras desse credor podem usá-la; nulo = conta da assessoria.
 * <p>
 * Credenciais (JSON) criptografadas no banco e nunca devolvidas pela API. {@code routingKey}
 * aleatório (256 bits) identifica a conta nos webhooks. Gravação e aviso de gravação são
 * configurados por conta: com gravação habilitada, o aviso ao devedor ("esta ligação pode ser
 * gravada") é obrigatório por padrão (LGPD art. 9º — transparência).
 */
@Entity
@Table(name = "provider_account", schema = "telephony")
public class TelephonyAccount extends TenantScopedEntity {

    public static final int CREDENTIALS_MAX = 4000;
    /** Número de origem em E.164 (com ou sem +). */
    private static final Pattern CALLER_ID = Pattern.compile("\\+?[0-9]{8,15}");

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30, updatable = false)
    private ProviderType provider;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "caller_id", nullable = false, length = 20)
    private String callerId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "credentials_encrypted", length = 8000)
    private String credentials;

    @Column(name = "credentials_updated_at")
    private Instant credentialsUpdatedAt;

    @Column(name = "routing_key", nullable = false, length = 64, updatable = false)
    private String routingKey;

    @Column(name = "recording_enabled", nullable = false)
    private boolean recordingEnabled;

    @Column(name = "recording_notice", nullable = false)
    private boolean recordingNotice;

    @Column(name = "active", nullable = false)
    private boolean active;

    /** Tarifa por minuto informada pela assessoria (somente para comparar custo estimado). */
    @Column(name = "rate_per_minute", precision = 10, scale = 4)
    private BigDecimal ratePerMinute;

    protected TelephonyAccount() {
    }

    public TelephonyAccount(ProviderType provider, UUID creditorId, String name, String callerId, boolean recordingEnabled,
                            boolean recordingNotice, String credentials, String routingKey, boolean active, Instant now) {
        Guard.require(provider != null, "telephony_account.provider_required", "Provedor obrigatório");
        Guard.require(routingKey != null && routingKey.length() >= 32, "telephony_account.routing_key_invalid",
                "Chave de roteamento inválida");
        this.provider = provider;
        this.creditorId = creditorId;
        this.routingKey = routingKey;
        this.active = active;
        describe(name, callerId, recordingEnabled, recordingNotice);
        replaceCredentials(credentials, now);
    }

    public void describe(String name, String callerId, boolean recordingEnabled, boolean recordingNotice) {
        Guard.require(name != null && !name.isBlank() && name.length() <= 120, "telephony_account.name_invalid",
                "Nome obrigatório (até 120 caracteres)");
        Guard.require(callerId != null && CALLER_ID.matcher(callerId.trim()).matches(),
                "telephony_account.caller_id_invalid", "Número de origem inválido (E.164, 8 a 15 dígitos)");
        Guard.require(!recordingEnabled || recordingNotice, "telephony_account.recording_notice_required",
                "Gravação exige o aviso de gravação ao devedor");
        this.name = name.trim();
        this.callerId = callerId.trim();
        this.recordingEnabled = recordingEnabled;
        this.recordingNotice = recordingNotice;
    }

    public void changeRate(BigDecimal ratePerMinute) {
        Guard.require(ratePerMinute == null || (ratePerMinute.signum() >= 0
                        && ratePerMinute.compareTo(BigDecimal.valueOf(100)) <= 0 && ratePerMinute.scale() <= 4),
                "telephony_account.rate_invalid", "Tarifa por minuto inválida (0 a 100, até 4 casas)");
        this.ratePerMinute = ratePerMinute;
    }

    /** A conta pode atender um escopo do credor informado (nulo = escopo da assessoria). */
    public boolean usableFor(UUID scopeCreditorId) {
        return creditorId == null || creditorId.equals(scopeCreditorId);
    }

    public void replaceCredentials(String credentials, Instant now) {
        Guard.require(credentials == null || credentials.length() <= CREDENTIALS_MAX,
                "telephony_account.credentials_too_long", "Credenciais muito longas");
        this.credentials = credentials;
        this.credentialsUpdatedAt = credentials == null ? null : now;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
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

    public String getCallerId() {
        return callerId;
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

    public boolean isRecordingEnabled() {
        return recordingEnabled;
    }

    public boolean isRecordingNotice() {
        return recordingNotice;
    }

    public BigDecimal getRatePerMinute() {
        return ratePerMinute;
    }

    public boolean isActive() {
        return active;
    }
}
