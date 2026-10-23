package br.com.gestorcobranca.incentive.domain.model.attribution;

import br.com.gestorcobranca.incentive.domain.model.DelayBucket;
import br.com.gestorcobranca.incentive.domain.model.Segment;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Atribuição de crédito de um acordo (§3): participantes registrados na formalização e créditos
 * resolvidos pelo plano vigente naquela data. Imutável depois de registrada — mudanças de
 * carteira, renegociações e estornos são tratados por lançamentos, nunca reescrevendo a
 * atribuição.
 */
@Entity
@Table(name = "agreement_attribution", schema = "incentive")
public class AgreementAttribution extends TenantScopedEntity {

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;

    @Column(name = "case_id", updatable = false)
    private UUID caseId;

    @Column(name = "debtor_id", updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "team_id", updatable = false)
    private UUID teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false, length = 40)
    private AgreementOrigin origin;

    @Column(name = "channel", nullable = false, updatable = false, length = 20)
    private String channel;

    @Column(name = "concession_level", updatable = false)
    private Integer concessionLevel;

    @Column(name = "envelope_usage", updatable = false, precision = 9, scale = 4)
    private Percentage envelopeUsage;

    @Column(name = "days_past_due", updatable = false)
    private Integer daysPastDue;

    @Enumerated(EnumType.STRING)
    @Column(name = "delay_bucket", nullable = false, updatable = false, length = 20)
    private DelayBucket delayBucket;

    @Column(name = "agreement_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money agreementAmount;

    @Column(name = "updated_balance", updatable = false, precision = 19, scale = 2)
    private Money updatedBalance;

    @Column(name = "discount_amount", updatable = false, precision = 19, scale = 2)
    private Money discountAmount;

    @Column(name = "down_payment_amount", updatable = false, precision = 19, scale = 2)
    private Money downPaymentAmount;

    @Column(name = "replaced_agreement_id", updatable = false)
    private UUID replacedAgreementId;

    /** Valor negociado incremental (§3.4): na renegociação, só o que excede o saldo em aberto do acordo substituído. */
    @Column(name = "incremental_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money incrementalAmount;

    @Column(name = "formalized_at", nullable = false, updatable = false)
    private Instant formalizedAt;

    @Column(name = "plan_id", updatable = false)
    private UUID planId;

    /** Comissão prevista (informativa, §5.2) para o closer/influenciador; nunca vai para logs. */
    @Column(name = "forecast_commission", updatable = false, precision = 19, scale = 2)
    private Money forecastCommission;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "attribution_participant", schema = "incentive", joinColumns = @JoinColumn(name = "attribution_id"))
    @OrderColumn(name = "position")
    private List<AttributionParticipant> participants = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "attribution_credit", schema = "incentive", joinColumns = @JoinColumn(name = "attribution_id"))
    @OrderColumn(name = "position")
    private List<AttributionCredit> credits = new ArrayList<>();

    protected AgreementAttribution() {
    }

    /** Dados do acordo como recebidos do evento de atribuição. */
    public record Facts(UUID sourceEventId, UUID agreementId, UUID caseId, UUID debtorId, UUID creditorId,
                        UUID portfolioId, UUID teamId, AgreementOrigin origin, String channel, Integer concessionLevel,
                        Percentage envelopeUsage, Integer daysPastDue, Money agreementAmount, Money updatedBalance,
                        Money discountAmount, Money downPaymentAmount, UUID replacedAgreementId, Instant formalizedAt,
                        List<AttributionParticipant> participants) {
    }

    public AgreementAttribution(Facts facts, Money incrementalAmount, UUID planId, List<AttributionCredit> credits,
                                Money forecastCommission) {
        Guard.require(facts.agreementId() != null && facts.creditorId() != null, "attribution.agreement_required",
                "Acordo e credor são obrigatórios");
        Guard.require(facts.origin() != null, "attribution.origin_required", "Origem do acordo obrigatória");
        Guard.require(facts.formalizedAt() != null, "attribution.formalized_at_required", "Data de formalização obrigatória");
        Guard.require(facts.agreementAmount() != null && !facts.agreementAmount().isNegative(),
                "attribution.amount_invalid", "Valor do acordo inválido");
        Guard.require(facts.envelopeUsage() == null || !facts.envelopeUsage().exceeds(Percentage.HUNDRED),
                "attribution.envelope_invalid", "Uso do envelope deve estar entre 0 e 100%");
        Guard.require(incrementalAmount != null && !incrementalAmount.isNegative()
                        && !incrementalAmount.isGreaterThan(facts.agreementAmount()), "attribution.incremental_invalid",
                "Valor incremental inválido");
        this.sourceEventId = facts.sourceEventId();
        this.agreementId = facts.agreementId();
        this.caseId = facts.caseId();
        this.debtorId = facts.debtorId();
        this.creditorId = facts.creditorId();
        this.portfolioId = facts.portfolioId();
        this.teamId = facts.teamId();
        this.origin = facts.origin();
        this.channel = facts.channel() == null || facts.channel().isBlank() ? "UNKNOWN" : facts.channel();
        this.concessionLevel = facts.concessionLevel();
        this.envelopeUsage = facts.envelopeUsage();
        this.daysPastDue = facts.daysPastDue();
        this.delayBucket = DelayBucket.of(facts.daysPastDue());
        this.agreementAmount = facts.agreementAmount();
        this.updatedBalance = facts.updatedBalance();
        this.discountAmount = facts.discountAmount();
        this.downPaymentAmount = facts.downPaymentAmount();
        this.replacedAgreementId = facts.replacedAgreementId();
        this.incrementalAmount = incrementalAmount;
        this.formalizedAt = facts.formalizedAt();
        this.planId = planId;
        this.forecastCommission = forecastCommission;
        this.participants.addAll(facts.participants() == null ? List.of() : facts.participants());
        this.credits.addAll(Objects.requireNonNull(credits, "credits"));
    }

    /** Closer humano, se houver. */
    public Optional<UUID> humanCloser() {
        return participants.stream().filter(p -> p.getRole() == ParticipantRole.CLOSER && p.isHuman())
                .map(AttributionParticipant::getActorId).findFirst();
    }

    public Segment segment() {
        return new Segment(creditorId, portfolioId, delayBucket);
    }

    public boolean isRenegotiation() {
        return replacedAgreementId != null;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public AgreementOrigin getOrigin() {
        return origin;
    }

    public String getChannel() {
        return channel;
    }

    public Integer getConcessionLevel() {
        return concessionLevel;
    }

    public Percentage getEnvelopeUsage() {
        return envelopeUsage;
    }

    public Integer getDaysPastDue() {
        return daysPastDue;
    }

    public DelayBucket getDelayBucket() {
        return delayBucket;
    }

    public Money getAgreementAmount() {
        return agreementAmount;
    }

    public Money getUpdatedBalance() {
        return updatedBalance;
    }

    public Money getDiscountAmount() {
        return discountAmount;
    }

    public Money getDownPaymentAmount() {
        return downPaymentAmount;
    }

    public UUID getReplacedAgreementId() {
        return replacedAgreementId;
    }

    public Money getIncrementalAmount() {
        return incrementalAmount;
    }

    public Instant getFormalizedAt() {
        return formalizedAt;
    }

    public UUID getPlanId() {
        return planId;
    }

    public Money getForecastCommission() {
        return forecastCommission;
    }

    public List<AttributionParticipant> getParticipants() {
        return Collections.unmodifiableList(participants);
    }

    public List<AttributionCredit> getCredits() {
        return Collections.unmodifiableList(credits);
    }
}
