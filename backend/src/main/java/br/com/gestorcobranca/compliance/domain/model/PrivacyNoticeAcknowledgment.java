package br.com.gestorcobranca.compliance.domain.model;

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
 * Reconhecimento do aviso de privacidade pelo titular (LGPD art. 9º — direito à informação),
 * com a base legal do tratamento vigente no instante para o credor (o registro do compliance, não
 * um consentimento: a cobrança se apoia na base registrada para o credor). Imutável.
 */
@Entity
@Table(name = "privacy_notice_acknowledgment", schema = "compliance")
public class PrivacyNoticeAcknowledgment extends TenantScopedEntity {

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private String channel;

    @Column(name = "notice_version", nullable = false, updatable = false)
    private int noticeVersion;

    @Column(name = "notice_sha256", nullable = false, length = 64, updatable = false)
    private String noticeSha256;

    /** Sessão do portal em que houve o reconhecimento. */
    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @Column(name = "acknowledged_at", nullable = false, updatable = false)
    private Instant acknowledgedAt;

    @Column(name = "legal_basis_record_id", updatable = false)
    private UUID legalBasisRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "legal_basis", length = 40, updatable = false)
    private LegalBasis legalBasis;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    protected PrivacyNoticeAcknowledgment() {
    }

    /**
     * @param basis base legal vigente do credor/carteira (nula = sem registro vigente)
     */
    public PrivacyNoticeAcknowledgment(UUID debtorId, UUID creditorId, String channel, int noticeVersion,
                                       String noticeSha256, UUID referenceId, Instant acknowledgedAt,
                                       LegalBasisRecord basis, UUID sourceEventId) {
        Guard.require(debtorId != null && creditorId != null, "privacy_ack.subject_required", "Titular e credor obrigatórios");
        Guard.require("PORTAL".equals(channel), "privacy_ack.channel_invalid", "Canal do reconhecimento inválido");
        Guard.require(noticeVersion >= 1, "privacy_ack.version_invalid", "Versão do aviso inválida");
        Guard.require(noticeSha256 != null && noticeSha256.matches("[0-9a-f]{64}"), "privacy_ack.hash_invalid",
                "Hash do aviso inválido");
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.channel = channel;
        this.noticeVersion = noticeVersion;
        this.noticeSha256 = noticeSha256;
        this.referenceId = referenceId;
        this.acknowledgedAt = Guard.requireNonNull(acknowledgedAt, "privacy_ack.instant_required", "Instante obrigatório");
        this.legalBasisRecordId = basis == null ? null : basis.getId();
        this.legalBasis = basis == null ? null : basis.getLegalBasis();
        this.sourceEventId = Guard.requireNonNull(sourceEventId, "privacy_ack.event_required", "Evento obrigatório");
    }

    public UUID getDebtorId() { return debtorId; }
    public UUID getCreditorId() { return creditorId; }
    public String getChannel() { return channel; }
    public int getNoticeVersion() { return noticeVersion; }
    public String getNoticeSha256() { return noticeSha256; }
    public UUID getReferenceId() { return referenceId; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public UUID getLegalBasisRecordId() { return legalBasisRecordId; }
    public LegalBasis getLegalBasis() { return legalBasis; }
    public UUID getSourceEventId() { return sourceEventId; }
}
