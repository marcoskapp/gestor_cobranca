package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
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

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Medida extrajudicial (etapa 4): notificação/constituição em mora, protesto, negativação com
 * comunicação prévia (CDC art. 43 §2º) e execução extrajudicial de garantia — tipo, títulos,
 * garantia (execução), cartório/birô, protocolo, datas, prazo e situação ({@link MeasureStatus}).
 * O envio por provedor é opcional ({@code provider}/{@code providerReference}); com o adaptador
 * manual, o protocolo e as datas são lançados pelo usuário.
 */
@Entity
@Table(name = "extrajudicial_measure", schema = "legal")
public class ExtrajudicialMeasure extends TenantScopedEntity {

    @Column(name = "matter_id", nullable = false, updatable = false)
    private UUID matterId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30, updatable = false)
    private MeasureType type;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "extrajudicial_measure_title", schema = "legal",
            joinColumns = @JoinColumn(name = "measure_id"))
    @Column(name = "title_id", nullable = false)
    private Set<UUID> titleIds = new HashSet<>();

    @Column(name = "collateral_id", updatable = false)
    private UUID collateralId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money amount;

    @Column(name = "authority", length = 150)
    private String authority;

    @Column(name = "protocol", length = 80)
    private String protocol;

    @Column(name = "prior_notice_on")
    private LocalDate priorNoticeOn;

    @Column(name = "submitted_on")
    private LocalDate submittedOn;

    @Column(name = "effective_on")
    private LocalDate effectiveOn;

    @Column(name = "resolved_on")
    private LocalDate resolvedOn;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MeasureStatus status;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "provider_reference", length = 120)
    private String providerReference;

    protected ExtrajudicialMeasure() {
    }

    public ExtrajudicialMeasure(UUID matterId, UUID creditorId, MeasureType type, Collection<UUID> titleIds,
                                UUID collateralId, Money amount, String authority, LocalDate priorNoticeOn,
                                LocalDate dueDate, String provider) {
        Guard.require(type != null, "legal_measure.type_required", "Tipo da medida obrigatório");
        Guard.require(titleIds != null && !titleIds.isEmpty(), "legal_measure.titles_required",
                "Informe os títulos da medida");
        Guard.require(type != MeasureType.COLLATERAL_ENFORCEMENT || collateralId != null,
                "legal_measure.collateral_required", "Execução extrajudicial exige a garantia");
        Guard.require(type == MeasureType.COLLATERAL_ENFORCEMENT || collateralId == null,
                "legal_measure.collateral_not_allowed", "Garantia só se aplica à execução extrajudicial");
        Guard.require(amount != null && amount.isPositive(), "legal_measure.amount_invalid",
                "Valor da medida deve ser positivo");
        this.matterId = matterId;
        this.creditorId = creditorId;
        this.type = type;
        this.titleIds = new HashSet<>(titleIds);
        this.collateralId = collateralId;
        this.amount = amount;
        this.authority = text(authority, 150, "legal_measure.authority_too_long");
        this.priorNoticeOn = priorNoticeOn;
        this.dueDate = dueDate;
        this.status = MeasureStatus.PLANNED;
        this.provider = provider;
    }

    /**
     * Protocolo/envio. A negativação exige a comunicação prévia ao devedor com antecedência
     * mínima ({@code noticeDays}, parâmetro da assessoria).
     */
    public MeasureStatus submit(LocalDate on, String protocolNumber, String providerRef, LocalDate today,
                                int noticeDays) {
        checkSubmission(on, today, noticeDays);
        MeasureStatus previous = move(MeasureStatus.SUBMITTED, null);
        this.submittedOn = on;
        this.protocol = text(protocolNumber, 80, "legal_measure.protocol_too_long");
        if (providerRef != null) {
            this.providerReference = text(providerRef, 120, "legal_measure.reference_too_long");
        }
        return previous;
    }

    /** Pré-condições do envio (também verificadas antes de acionar um provedor). */
    public void checkSubmission(LocalDate on, LocalDate today, int noticeDays) {
        Guard.require(on != null && !on.isAfter(today), "legal_measure.date_invalid", "Data do envio obrigatória e não futura");
        Guard.requireState(status == MeasureStatus.PLANNED, "legal_measure.transition_invalid",
                "Somente medida planejada é enviada");
        if (type == MeasureType.CREDIT_BUREAU_LISTING) {
            Guard.require(priorNoticeOn != null, "legal_measure.prior_notice_required",
                    "Negativação exige a comunicação prévia ao devedor (CDC art. 43 §2º)");
            Guard.require(!priorNoticeOn.plusDays(noticeDays).isAfter(on), "legal_measure.prior_notice_too_recent",
                    "A comunicação prévia deve anteceder a negativação em ao menos %d dias".formatted(noticeDays));
        }
    }

    public MeasureStatus markEffective(LocalDate on, LocalDate today, String reason) {
        Guard.require(on != null && !on.isAfter(today) && !on.isBefore(submittedOn), "legal_measure.date_invalid",
                "Data de efetivação obrigatória, não futura e posterior ao envio");
        MeasureStatus previous = move(MeasureStatus.EFFECTIVE, reason);
        this.effectiveOn = on;
        return previous;
    }

    /** FAILED, CANCELLED ou REMOVED (baixa) — exige motivo. */
    public MeasureStatus resolve(MeasureStatus target, LocalDate on, LocalDate today, String reason) {
        Guard.require(target == MeasureStatus.FAILED || target == MeasureStatus.CANCELLED
                || target == MeasureStatus.REMOVED, "legal_measure.status_invalid", "Situação inválida");
        Guard.require(reason != null && !reason.isBlank(), "legal_measure.reason_required", "Motivo obrigatório");
        Guard.require(on == null || !on.isAfter(today), "legal_measure.date_invalid", "Data não pode ser futura");
        MeasureStatus previous = move(target, reason);
        this.resolvedOn = on == null ? today : on;
        return previous;
    }

    private MeasureStatus move(MeasureStatus target, String reason) {
        if (!status.canMoveTo(target)) {
            throw new ConflictException("legal_measure.transition_invalid",
                    "Medida em %s não pode passar a %s".formatted(status, target));
        }
        MeasureStatus previous = status;
        this.status = target;
        this.statusReason = text(reason, 500, "legal_measure.reason_too_long");
        return previous;
    }

    public void registerProviderReference(String providerCode, String reference) {
        this.provider = providerCode;
        this.providerReference = text(reference, 120, "legal_measure.reference_too_long");
    }

    private static String text(String value, int max, String code) {
        String v = value == null || value.isBlank() ? null : value.trim();
        Guard.require(v == null || v.length() <= max, code, "Texto deve ter até %d caracteres".formatted(max));
        return v;
    }

    public UUID getMatterId() {
        return matterId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public MeasureType getType() {
        return type;
    }

    public Set<UUID> getTitleIds() {
        return Set.copyOf(titleIds);
    }

    public UUID getCollateralId() {
        return collateralId;
    }

    public Money getAmount() {
        return amount;
    }

    public String getAuthority() {
        return authority;
    }

    public String getProtocol() {
        return protocol;
    }

    public LocalDate getPriorNoticeOn() {
        return priorNoticeOn;
    }

    public LocalDate getSubmittedOn() {
        return submittedOn;
    }

    public LocalDate getEffectiveOn() {
        return effectiveOn;
    }

    public LocalDate getResolvedOn() {
        return resolvedOn;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public MeasureStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderReference() {
        return providerReference;
    }
}
