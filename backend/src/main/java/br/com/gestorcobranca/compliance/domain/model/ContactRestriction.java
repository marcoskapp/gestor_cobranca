package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Restrição de contato (RB02, RB07): opt-out, bloqueio jurídico ou operacional, pedido do
 * devedor ou do credor. O alcance é a combinação de:
 * <ul>
 *   <li><b>devedor</b> — obrigatório, exceto quando a restrição é de um contato específico
 *       recebido sem identificação do devedor (ex.: "SAIR" vindo de um número);</li>
 *   <li><b>credor</b> — nulo vale para todos os credores;</li>
 *   <li><b>canal</b> — nulo vale para todos os canais;</li>
 *   <li><b>valor de contato</b> — opcional: restringe apenas aquele telefone/e-mail, guardado
 *       como blind index (nunca em claro) e mascarado para exibição.</li>
 * </ul>
 * A vigência é {@code [startsAt, endsAt)}; sem {@code endsAt} é permanente. Restrições não são
 * apagadas: são revogadas com motivo, preservando o histórico como evidência.
 */
@Entity
@Table(name = "contact_restriction", schema = "compliance")
public class ContactRestriction extends TenantScopedEntity {

    private static final int REASON_MAX = 1000;

    @Column(name = "debtor_id", updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20, updatable = false)
    private ContactChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private RestrictionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_value_type", length = 10, updatable = false)
    private ContactValueType contactValueType;

    @Column(name = "contact_value_hash", length = 64, updatable = false)
    private String contactValueHash;

    @Column(name = "contact_value_masked", length = 254, updatable = false)
    private String contactValueMasked;

    @Column(name = "reason", nullable = false, length = REASON_MAX, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30, updatable = false)
    private RestrictionSource source;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", updatable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RestrictionStatus status;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoked_reason", length = REASON_MAX)
    private String revokedReason;

    /** Solicitação de titular que originou a restrição (oposição), se houver. */
    @Column(name = "origin_request_id", updatable = false)
    private UUID originRequestId;

    @Column(name = "evidence_file_key", length = 512)
    private String evidenceFileKey;

    @Column(name = "evidence_file_name", length = 200)
    private String evidenceFileName;

    @Column(name = "evidence_content_type", length = 100)
    private String evidenceContentType;

    @Column(name = "evidence_sha256", length = 64)
    private String evidenceSha256;

    /** Quando o fim da vigência foi comunicado aos demais módulos (recálculo de elegibilidade). */
    @Column(name = "expiry_notified_at")
    private Instant expiryNotifiedAt;

    protected ContactRestriction() {
    }

    /**
     * @param contact valor de contato restrito (opcional)
     * @param endsAt  fim da vigência (opcional; restrição temporária)
     */
    public ContactRestriction(UUID debtorId, UUID creditorId, ContactChannel channel, RestrictionType type,
                              ContactValueRef contact, String reason, RestrictionSource source, Instant startsAt,
                              Instant endsAt, UUID originRequestId) {
        Guard.require(type != null, "restriction.type_required", "Tipo da restrição obrigatório");
        Guard.require(source != null, "restriction.source_required", "Origem da restrição obrigatória");
        Guard.require(debtorId != null || contact != null, "restriction.target_required",
                "Informe o devedor ou o contato a restringir");
        Guard.require(startsAt != null, "restriction.starts_at_required", "Início da vigência obrigatório");
        Guard.require(endsAt == null || endsAt.isAfter(startsAt), "restriction.period_invalid",
                "Fim da vigência deve ser posterior ao início");
        if (contact != null && channel != null) {
            Guard.require(channel.contactValueType().filter(contact.type()::equals).isPresent(),
                    "restriction.channel_contact_mismatch", "O contato informado não é usado pelo canal %s".formatted(channel));
        }
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.channel = channel;
        this.type = type;
        if (contact != null) {
            this.contactValueType = contact.type();
            this.contactValueHash = contact.hash();
            this.contactValueMasked = contact.masked();
        }
        this.reason = requireReason(reason, "restriction.reason_required");
        this.source = source;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.originRequestId = originRequestId;
        this.status = RestrictionStatus.ACTIVE;
    }

    /** Vigente no instante: ativa (não revogada) e dentro de {@code [startsAt, endsAt)}. */
    public boolean isEffectiveAt(Instant at) {
        return status == RestrictionStatus.ACTIVE && !at.isBefore(startsAt) && (endsAt == null || at.isBefore(endsAt));
    }

    /**
     * Indica se a restrição alcança um contato com o devedor, credor e canal informados.
     * <ul>
     *   <li>Restrição de um devedor alcança só aquele devedor, exceto o opt-out de um valor de
     *       contato: o descadastro é do dono do número/e-mail e vale para qualquer devedor que o
     *       use.</li>
     *   <li>Restrição sem devedor (valor de contato recebido sem identificação) alcança qualquer
     *       devedor que use o valor.</li>
     *   <li>Restrição de valor de contato só alcança contatos feitos por aquele valor, em canais
     *       que o usam.</li>
     * </ul>
     *
     * @param contactValueHash blind index do valor usado no contato; nulo se não informado
     */
    public boolean covers(UUID requestDebtorId, UUID requestCreditorId, ContactChannel requestChannel,
                          String contactValueHash) {
        boolean debtorMatches = debtorId == null || debtorId.equals(requestDebtorId)
                || type == RestrictionType.OPT_OUT && this.contactValueHash != null;
        if (!debtorMatches) {
            return false;
        }
        if (creditorId != null && !creditorId.equals(requestCreditorId)) {
            return false;
        }
        if (channel != null && channel != requestChannel) {
            return false;
        }
        if (this.contactValueHash == null) {
            // sem valor de contato a restrição sempre tem devedor, já conferido acima
            return true;
        }
        return requestChannel.contactValueType().filter(contactValueType::equals).isPresent()
                && this.contactValueHash.equals(contactValueHash);
    }

    /** Motivo de negação que esta restrição produz quando alcança o contato. */
    public DenialReason denialReason() {
        if (contactValueHash != null) {
            return DenialReason.RESTRICTED_CONTACT_VALUE;
        }
        return channel == null ? DenialReason.RESTRICTED_DEBTOR : DenialReason.RESTRICTED_CHANNEL;
    }

    /** Opt-out vigente do valor de contato no canal, para todos os credores (deduplicação). */
    public boolean isOptOutOf(String otherContactHash, ContactChannel otherChannel, Instant at) {
        return type == RestrictionType.OPT_OUT && creditorId == null && channel == otherChannel
                && Objects.equals(contactValueHash, otherContactHash) && isEffectiveAt(at);
    }

    public boolean isTemporary() {
        return endsAt != null;
    }

    public void revoke(String reason, UUID actorId, Instant now) {
        Guard.requireState(status == RestrictionStatus.ACTIVE, "restriction.already_revoked", "Restrição já revogada");
        this.revokedReason = requireReason(reason, "restriction.revoke_reason_required");
        this.revokedBy = actorId;
        this.revokedAt = now;
        this.status = RestrictionStatus.REVOKED;
    }

    /** Anexa o comprovante (pedido por escrito, decisão judicial, gravação) à restrição. */
    public void attachEvidence(String fileKey, String fileName, String contentType, String sha256) {
        Guard.requireState(status == RestrictionStatus.ACTIVE, "restriction.revoked",
                "Restrição revogada não aceita evidência");
        Guard.requireState(evidenceFileKey == null, "restriction.evidence_present", "Restrição já possui evidência anexada");
        Guard.require(fileKey != null && sha256 != null, "restriction.evidence_invalid", "Arquivo de evidência inválido");
        this.evidenceFileKey = fileKey;
        this.evidenceFileName = fileName;
        this.evidenceContentType = contentType;
        this.evidenceSha256 = sha256;
    }

    /** Fim da vigência alcançado e ainda não comunicado. */
    public boolean expiredUnnotified(Instant now) {
        return status == RestrictionStatus.ACTIVE && endsAt != null && !now.isBefore(endsAt) && expiryNotifiedAt == null;
    }

    public void markExpiryNotified(Instant now) {
        Guard.requireState(expiredUnnotified(now), "restriction.not_expired", "Restrição não expirou");
        this.expiryNotifiedAt = now;
    }

    private static String requireReason(String value, String code) {
        Guard.require(value != null && !value.isBlank(), code, "Motivo obrigatório");
        String trimmed = value.trim();
        Guard.require(trimmed.length() <= REASON_MAX, "restriction.reason_too_long",
                "Motivo deve ter até %d caracteres".formatted(REASON_MAX));
        return trimmed;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public ContactChannel getChannel() {
        return channel;
    }

    public RestrictionType getType() {
        return type;
    }

    public ContactValueType getContactValueType() {
        return contactValueType;
    }

    public String getContactValueHash() {
        return contactValueHash;
    }

    public String getContactValueMasked() {
        return contactValueMasked;
    }

    public String getReason() {
        return reason;
    }

    public RestrictionSource getSource() {
        return source;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public RestrictionStatus getStatus() {
        return status;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getRevokedBy() {
        return revokedBy;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public UUID getOriginRequestId() {
        return originRequestId;
    }

    public String getEvidenceFileKey() {
        return evidenceFileKey;
    }

    public String getEvidenceFileName() {
        return evidenceFileName;
    }

    public String getEvidenceContentType() {
        return evidenceContentType;
    }

    public String getEvidenceSha256() {
        return evidenceSha256;
    }

    public Instant getExpiryNotifiedAt() {
        return expiryNotifiedAt;
    }
}
