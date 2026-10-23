package br.com.gestorcobranca.portal.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Sessão do devedor no portal de um credor: curta (inatividade e prazo absoluto), validada no
 * servidor a cada requisição e revogável; separada das sessões de usuários internos. Registra o
 * aviso de privacidade reconhecido e as etapas do funil atingidas (simulação, aceite, Pix).
 */
@Entity
@Table(name = "portal_session", schema = "portal")
public class PortalSession extends TenantScopedEntity {

    @Column(name = "configuration_id", nullable = false, updatable = false)
    private UUID configurationId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 10, updatable = false)
    private AccessMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private SessionStatus status;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "end_reason", length = 30)
    private String endReason;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "privacy_notice_version")
    private Integer privacyNoticeVersion;

    @Column(name = "privacy_acknowledged_at")
    private Instant privacyAcknowledgedAt;

    @Column(name = "simulated_at")
    private Instant simulatedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "pix_issued_at")
    private Instant pixIssuedAt;

    protected PortalSession() {
    }

    public PortalSession(UUID configurationId, UUID creditorId, UUID debtorId, AccessMethod method, Instant now,
                         Duration absoluteTimeout, String clientIp, String userAgent) {
        this.configurationId = Guard.requireNonNull(configurationId, "portal.configuration_required", "Portal obrigatório");
        this.creditorId = Guard.requireNonNull(creditorId, "portal.creditor_required", "Credor obrigatório");
        this.debtorId = Guard.requireNonNull(debtorId, "portal.debtor_required", "Devedor obrigatório");
        this.method = Guard.requireNonNull(method, "portal.method_required", "Método obrigatório");
        this.status = SessionStatus.ACTIVE;
        this.lastSeenAt = now;
        this.expiresAt = now.plus(absoluteTimeout);
        this.clientIp = truncate(clientIp, 64);
        this.userAgent = truncate(userAgent, 300);
    }

    /** Ativa, dentro do prazo absoluto e sem inatividade além do limite. */
    public boolean usableAt(Instant now, Duration idleTimeout) {
        return status == SessionStatus.ACTIVE && expiresAt.isAfter(now) && lastSeenAt.plus(idleTimeout).isAfter(now);
    }

    public void touch(Instant now) {
        if (status == SessionStatus.ACTIVE && now.isAfter(lastSeenAt)) {
            this.lastSeenAt = now;
        }
    }

    /** Encerra (idempotente): {@code ENDED} pelo devedor, {@code REVOKED} pela gestão/segurança, {@code EXPIRED}. */
    public boolean end(SessionStatus finalStatus, String reason, Instant now) {
        Guard.require(finalStatus != SessionStatus.ACTIVE, "portal.session_status_invalid", "Situação final inválida");
        if (status != SessionStatus.ACTIVE) {
            return false;
        }
        this.status = finalStatus;
        this.endedAt = now;
        this.endReason = truncate(reason, 30);
        return true;
    }

    public boolean privacyAcknowledged(int currentVersion) {
        return privacyNoticeVersion != null && privacyNoticeVersion == currentVersion;
    }

    public void acknowledgePrivacy(int version, Instant now) {
        this.privacyNoticeVersion = version;
        this.privacyAcknowledgedAt = now;
    }

    /** @return verdadeiro na primeira simulação da sessão (etapa do funil) */
    public boolean markSimulated(Instant now) {
        if (simulatedAt != null) {
            return false;
        }
        simulatedAt = now;
        return true;
    }

    public boolean markAccepted(Instant now) {
        if (acceptedAt != null) {
            return false;
        }
        acceptedAt = now;
        return true;
    }

    public boolean markPixIssued(Instant now) {
        if (pixIssuedAt != null) {
            return false;
        }
        pixIssuedAt = now;
        return true;
    }

    /** LGPD: anonimização do devedor remove IP e agente de usuário. */
    public void anonymize() {
        this.clientIp = null;
        this.userAgent = null;
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.length() > max ? value.substring(0, max) : value;
    }

    public UUID getConfigurationId() { return configurationId; }
    public UUID getCreditorId() { return creditorId; }
    public UUID getDebtorId() { return debtorId; }
    public AccessMethod getMethod() { return method; }
    public SessionStatus getStatus() { return status; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getEndedAt() { return endedAt; }
    public String getEndReason() { return endReason; }
    public Integer getPrivacyNoticeVersion() { return privacyNoticeVersion; }
    public Instant getPrivacyAcknowledgedAt() { return privacyAcknowledgedAt; }
    public Instant getSimulatedAt() { return simulatedAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getPixIssuedAt() { return pixIssuedAt; }
}
