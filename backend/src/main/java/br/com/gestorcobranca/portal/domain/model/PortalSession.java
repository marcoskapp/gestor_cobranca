package br.com.gestorcobranca.portal.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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

    /** Confirmação de quem negocia pela empresa (devedor PJ, B4): nulo = ainda não informado. */
    @Enumerated(EnumType.STRING)
    @Column(name = "representative_status", length = 12)
    private RepresentativeStatus representativeStatus;

    @Column(name = "representative_id")
    private UUID representativeId;

    @Column(name = "representative_checked_at")
    private Instant representativeCheckedAt;

    /** Nome declarado por quem negocia (para o atendente); criptografado e apagado com os dados do navegador. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "representative_name_encrypted", length = 1024)
    private String representativeName;

    /** CPF declarado, só mascarado (o CPF em claro nunca é gravado). */
    @Column(name = "representative_document_masked", length = 14)
    private String representativeDocumentMasked;

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

    /**
     * Quem está negociando pela empresa (B4). {@code representativeId} nulo = não confirmado (CPF que não confere,
     * representante sem poder ou fora da validade, confirmação bloqueada): a sessão segue para consulta e o aceite vira
     * pedido de atendimento. Pode ser informado de novo na mesma sessão (o limite de tentativas é por devedor e IP).
     *
     * @param maskedDocument CPF declarado, mascarado
     */
    public void declareRepresentative(UUID representativeId, String name, String maskedDocument, Instant now) {
        Guard.require(name != null && !name.isBlank(), "portal.representative_name_required",
                "Informe o nome de quem está negociando");
        this.representativeStatus = representativeId == null ? RepresentativeStatus.UNCONFIRMED
                : RepresentativeStatus.CONFIRMED;
        this.representativeId = representativeId;
        this.representativeCheckedAt = now;
        this.representativeName = truncate(name.trim().replaceAll("\\s+", " "), 200);
        this.representativeDocumentMasked = truncate(maskedDocument, 14);
    }

    public boolean representativeDeclared() {
        return representativeStatus != null;
    }

    /** LGPD: anonimização do devedor remove IP, agente de usuário e o representante declarado. */
    public void anonymize() {
        this.clientIp = null;
        this.userAgent = null;
        this.representativeName = null;
        this.representativeDocumentMasked = null;
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
    public RepresentativeStatus getRepresentativeStatus() { return representativeStatus; }
    public UUID getRepresentativeId() { return representativeId; }
    public Instant getRepresentativeCheckedAt() { return representativeCheckedAt; }
    /** Nome declarado (descriptografado): só para o pedido de atendimento. */
    public String getRepresentativeName() { return representativeName; }
    public String getRepresentativeDocumentMasked() { return representativeDocumentMasked; }

    /** Situação da confirmação do representante. */
    public enum RepresentativeStatus { CONFIRMED, UNCONFIRMED }
}
