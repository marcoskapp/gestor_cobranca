package br.com.gestorcobranca.negotiation.domain.model.agreement;

import br.com.gestorcobranca.negotiation.domain.service.ProportionalSplit;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Acordo formalizado (RB05) a partir de uma proposta aceita: cronograma de parcelas, títulos
 * cobertos, distribuição por título e componente do razão ({@link AgreementBucket}), regra de quebra
 * fixada na formalização, evidência de aceite e termo. Agregado: parcelas, títulos e componentes
 * só mudam por métodos daqui.
 * <p>
 * Pagamentos: aplicados à parcela indicada e, o excedente, às parcelas seguintes em ordem; o que
 * passar do saldo do acordo fica "não aplicado" (cabe ao módulo payment devolver ou realocar). Cada
 * pagamento aplicado é distribuído entre os componentes proporcionalmente ao saldo de cada um
 * ({@link ProportionalSplit}); os encargos são reconhecidos no razão somente na medida em que são
 * pagos, de modo que um acordo quebrado não deixa encargo reconhecido sem pagamento (sem dupla
 * contagem de mora numa nova negociação). Quando um título fica integralmente pago, o razão recebe
 * também os encargos brutos ainda não reconhecidos e o desconto concedido (quitação do título).
 */
@Entity
@Table(name = "agreement", schema = "negotiation")
public class Agreement extends TenantScopedEntity {

    @Column(name = "proposal_id", nullable = false, updatable = false)
    private UUID proposalId;
    @Column(name = "simulation_id", nullable = false, updatable = false)
    private UUID simulationId;
    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;
    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;
    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;
    @Column(name = "case_id", updatable = false)
    private UUID caseId;
    @Column(name = "team_id", updatable = false)
    private UUID teamId;
    @Column(name = "policy_id", nullable = false, updatable = false)
    private UUID policyId;
    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;
    @Column(name = "override_code", updatable = false, length = 40)
    private String overrideCode;
    @Column(name = "reference_date", nullable = false, updatable = false)
    private LocalDate referenceDate;
    @Column(name = "concession_level", nullable = false, updatable = false)
    private int concessionLevel;
    @Column(name = "envelope_usage", nullable = false, updatable = false, precision = 9, scale = 4)
    private Percentage envelopeUsage;
    @Column(name = "max_days_overdue", nullable = false, updatable = false)
    private long maxDaysOverdue;
    @Column(name = "channel", updatable = false, length = 20)
    private String channel;
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false, length = 40)
    private AgreementOrigin origin;
    @Column(name = "originator_type", nullable = false, updatable = false, length = 20)
    private String originatorType;
    @Column(name = "originator_id", nullable = false, updatable = false)
    private UUID originatorId;
    @Column(name = "closer_type", nullable = false, updatable = false, length = 20)
    private String closerType;
    @Column(name = "closer_id", nullable = false, updatable = false)
    private UUID closerId;
    @Column(name = "approver_id", updatable = false)
    private UUID approverId;
    @Column(name = "pre_negotiator_id", updatable = false)
    private UUID preNegotiatorId;
    @Column(name = "updated_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money updatedAmount;
    @Column(name = "discount_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money discountAmount;
    @Column(name = "negotiated_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money negotiatedAmount;
    @Column(name = "financing_interest", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money financingInterest;
    @Column(name = "total_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money totalAmount;
    @Column(name = "down_payment", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money downPayment;
    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private Money paidAmount;
    @Column(name = "installment_count", nullable = false, updatable = false)
    private int installmentCount;
    @Column(name = "break_overdue_installments", nullable = false, updatable = false)
    private int breakOverdueInstallments;
    @Column(name = "break_tolerance_days", nullable = false, updatable = false)
    private int breakToleranceDays;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AgreementStatus status;
    @Column(name = "status_reason", length = 500)
    private String statusReason;
    @Column(name = "formalized_at", nullable = false, updatable = false)
    private Instant formalizedAt;
    @Column(name = "in_payment_at")
    private Instant inPaymentAt;
    @Column(name = "settled_at")
    private Instant settledAt;
    @Column(name = "ended_at")
    private Instant endedAt;
    @Column(name = "replaced_agreement_id", updatable = false)
    private UUID replacedAgreementId;
    @Column(name = "replaced_by_agreement_id")
    private UUID replacedByAgreementId;
    @Column(name = "renegotiation_number", nullable = false, updatable = false)
    private int renegotiationNumber;
    @Column(name = "incremental_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money incrementalAmount;
    @Embedded
    private AcceptanceEvidence acceptance;
    @Column(name = "term_deleted_at")
    private Instant termDeletedAt;

    @OneToMany(mappedBy = "agreement", cascade = CascadeType.ALL)
    @OrderBy("number")
    private List<AgreementInstallment> installments = new ArrayList<>();

    @OneToMany(mappedBy = "agreement", cascade = CascadeType.ALL)
    private List<AgreementTitle> titles = new ArrayList<>();

    @OneToMany(mappedBy = "agreement", cascade = CascadeType.ALL)
    private List<AgreementBucket> buckets = new ArrayList<>();

    protected Agreement() {
    }

    /** Dados da formalização (copiados da proposta aceita e do cálculo revalidado). */
    public record Terms(UUID proposalId, UUID simulationId, UUID creditorId, UUID debtorId, UUID portfolioId, UUID caseId,
                        UUID teamId, UUID policyId, int policyVersion, String overrideCode, LocalDate referenceDate,
                        int concessionLevel, BigDecimal envelopeUsage, long maxDaysOverdue, String channel,
                        AgreementOrigin origin, String originatorType, UUID originatorId, String closerType,
                        UUID closerId, UUID approverId, UUID preNegotiatorId, Money updatedAmount,
                        Money discountAmount, Money negotiatedAmount, Money financingInterest, Money totalAmount,
                        Money downPayment, int breakOverdueInstallments, int breakToleranceDays,
                        UUID replacedAgreementId, int renegotiationNumber, Money incrementalAmount,
                        AcceptanceEvidence acceptance, Instant formalizedAt) {
    }

    public record ScheduleEntry(int number, LocalDate dueDate, Money amount) {
    }

    /** Valores de um título por componente do razão. */
    public record TitleParts(UUID titleId, Map<LedgerPart, Money> gross, Map<LedgerPart, Money> discount,
                             Map<LedgerPart, Money> recognized) {
    }

    public Agreement(Terms t, List<ScheduleEntry> schedule, List<TitleParts> parts) {
        Guard.require(t.proposalId() != null && t.creditorId() != null && t.debtorId() != null && t.portfolioId() != null
                && t.policyId() != null, "agreement.invalid", "Proposta, credor, devedor, carteira e política obrigatórios");
        Guard.require(t.acceptance() != null, "agreement.acceptance_required", "A formalização exige evidência de aceite");
        Guard.require(!schedule.isEmpty() && !parts.isEmpty(), "agreement.invalid", "Cronograma e títulos obrigatórios");
        Guard.require(t.breakOverdueInstallments() >= 1 && t.breakToleranceDays() >= 0, "agreement.break_rule_invalid",
                "Regra de quebra inválida");
        Money scheduleTotal = schedule.stream().map(ScheduleEntry::amount).reduce(Money.ZERO, Money::plus);
        Guard.require(scheduleTotal.equals(t.totalAmount()), "agreement.schedule_mismatch",
                "A soma das parcelas difere do valor do acordo");
        this.proposalId = t.proposalId();
        this.simulationId = t.simulationId();
        this.creditorId = t.creditorId();
        this.debtorId = t.debtorId();
        this.portfolioId = t.portfolioId();
        this.caseId = t.caseId();
        this.teamId = t.teamId();
        this.policyId = t.policyId();
        this.policyVersion = t.policyVersion();
        this.overrideCode = t.overrideCode();
        this.referenceDate = t.referenceDate();
        this.concessionLevel = t.concessionLevel();
        this.envelopeUsage = Percentage.of(t.envelopeUsage());
        this.maxDaysOverdue = t.maxDaysOverdue();
        this.channel = t.channel();
        this.origin = t.origin();
        this.originatorType = t.originatorType();
        this.originatorId = t.originatorId();
        this.closerType = t.closerType();
        this.closerId = t.closerId();
        this.approverId = t.approverId();
        this.preNegotiatorId = t.preNegotiatorId();
        this.updatedAmount = t.updatedAmount();
        this.discountAmount = t.discountAmount();
        this.negotiatedAmount = t.negotiatedAmount();
        this.financingInterest = t.financingInterest();
        this.totalAmount = t.totalAmount();
        this.downPayment = t.downPayment();
        this.paidAmount = Money.ZERO;
        this.installmentCount = schedule.size();
        this.breakOverdueInstallments = t.breakOverdueInstallments();
        this.breakToleranceDays = t.breakToleranceDays();
        this.status = AgreementStatus.FORMALIZED;
        this.formalizedAt = t.formalizedAt();
        this.replacedAgreementId = t.replacedAgreementId();
        this.renegotiationNumber = t.renegotiationNumber();
        this.incrementalAmount = t.incrementalAmount();
        this.acceptance = t.acceptance();
        schedule.stream().sorted(Comparator.comparingInt(ScheduleEntry::number))
                .forEach(e -> installments.add(new AgreementInstallment(this, e.number(), e.dueDate(), e.amount())));
        Money payable = Money.ZERO;
        for (TitleParts p : parts) {
            titles.add(new AgreementTitle(this, p.titleId()));
            for (LedgerPart part : LedgerPart.values()) {
                Money gross = p.gross().getOrDefault(part, Money.ZERO);
                Money discount = p.discount().getOrDefault(part, Money.ZERO);
                if (gross.isZero() && discount.isZero()) {
                    continue;
                }
                AgreementBucket bucket = new AgreementBucket(this, p.titleId(), part, gross, discount,
                        p.recognized().getOrDefault(part, Money.ZERO));
                buckets.add(bucket);
                payable = payable.plus(bucket.getPayable());
            }
        }
        Guard.require(payable.equals(t.totalAmount()), "agreement.composition_mismatch",
                "A composição por título difere do valor do acordo");
    }

    // ------------------------------------------------------------------ resultados das operações

    public record InstallmentChange(AgreementInstallment installment, InstallmentStatus previous, InstallmentStatus current) {
    }

    public record StatusChange(AgreementStatus previous, AgreementStatus current) {
    }

    /**
     * @param applied   valor aplicado por parcela (id da parcela → valor)
     * @param unapplied excedente sobre o saldo do acordo (não aplicado)
     */
    public record Receipt(Map<UUID, Money> applied, Money appliedTotal, Money unapplied,
                          List<InstallmentChange> installmentChanges, List<StatusChange> statusChanges) {
    }

    /**
     * Lançamentos de um pagamento num componente de um título, na ordem do razão: encargo a
     * reconhecer ({@code charge}); quando o título fica integralmente pago, o encargo bruto ainda não
     * reconhecido ({@code settlementCharge}); o pagamento; e o desconto concedido ({@code discount}),
     * que zera o título.
     */
    public record BucketPosting(UUID titleId, LedgerPart part, Money payment, Money charge, Money settlementCharge,
                                Money discount) {

        public BucketPosting(UUID titleId, LedgerPart part, Money payment, Money charge) {
            this(titleId, part, payment, charge, Money.ZERO, Money.ZERO);
        }
    }

    // ------------------------------------------------------------------ pagamentos

    /**
     * Aplica um pagamento. {@code byInstallment}: valor indicado por parcela (parcela desconhecida
     * vira excedente); {@code unassigned}: valor sem parcela indicada. O excedente de cada parcela e
     * o valor sem parcela vão às parcelas em aberto em ordem de número.
     */
    public Receipt receive(Map<UUID, Money> byInstallment, Money unassigned, LocalDate today, Instant at) {
        Guard.requireState(status.isActive() || status == AgreementStatus.SETTLED, "agreement.not_active",
                "Acordo não está em vigor");
        Map<UUID, Money> applied = new LinkedHashMap<>();
        Money pool = unassigned == null ? Money.ZERO : unassigned;
        if (status.isActive()) {
            for (Map.Entry<UUID, Money> e : byInstallment.entrySet()) {
                Optional<AgreementInstallment> target = installment(e.getKey());
                if (target.isEmpty()) {
                    pool = pool.plus(e.getValue());
                    continue;
                }
                Money done = target.get().receive(e.getValue(), at);
                applied.merge(target.get().getId(), done, Money::plus);
                pool = pool.plus(e.getValue().minus(done));
            }
            for (AgreementInstallment i : installments) {
                if (!pool.isPositive()) {
                    break;
                }
                Money done = i.receive(pool, at);
                if (done.isPositive()) {
                    applied.merge(i.getId(), done, Money::plus);
                    pool = pool.minus(done);
                }
            }
        } else {
            pool = pool.plus(byInstallment.values().stream().reduce(Money.ZERO, Money::plus));
        }
        Money appliedTotal = applied.values().stream().reduce(Money.ZERO, Money::plus);
        paidAmount = paidAmount.plus(appliedTotal);
        List<InstallmentChange> changes = refreshInstallments(today);
        return new Receipt(applied, appliedTotal, pool, changes, refreshStatus(at));
    }

    /** Estorno de um pagamento aplicado: devolve às parcelas os valores aplicados. */
    public Receipt revert(Map<UUID, Money> appliedByInstallment, LocalDate today, Instant at) {
        Money total = Money.ZERO;
        for (Map.Entry<UUID, Money> e : appliedByInstallment.entrySet()) {
            AgreementInstallment i = installment(e.getKey()).orElseThrow(() -> new IllegalStateException(
                    "Parcela " + e.getKey() + " não pertence ao acordo " + getId()));
            i.revert(e.getValue());
            total = total.plus(e.getValue());
        }
        paidAmount = paidAmount.minus(total);
        List<InstallmentChange> changes = refreshInstallments(today);
        return new Receipt(appliedByInstallment, total, Money.ZERO, changes, refreshStatus(at));
    }

    /**
     * Distribui um valor aplicado entre títulos e componentes, proporcionalmente ao saldo de cada
     * componente; devolve os lançamentos por componente. O título cujos componentes ficam
     * integralmente pagos recebe também os lançamentos de quitação (encargo bruto restante e desconto).
     */
    public List<BucketPosting> allocate(Money applied) {
        List<AgreementBucket> ordered = orderedBuckets();
        List<Money> remaining = ordered.stream().map(AgreementBucket::remaining).toList();
        Money totalRemaining = remaining.stream().reduce(Money.ZERO, Money::plus);
        Guard.requireState(!applied.isGreaterThan(totalRemaining), "agreement.allocation_exceeds_balance",
                "Valor aplicado excede o saldo dos componentes do acordo");
        List<Money> shares = ProportionalSplit.split(applied, remaining);
        Map<AgreementBucket, BucketPosting> postings = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            Money share = shares.get(i);
            if (share.isPositive()) {
                AgreementBucket b = ordered.get(i);
                Money charge = b.pay(share);
                postings.put(b, new BucketPosting(b.getTitleId(), b.getPart(), share, charge));
            }
        }
        java.util.Set<UUID> touched = new java.util.LinkedHashSet<>();
        postings.values().forEach(p -> touched.add(p.titleId()));
        for (UUID titleId : touched) {
            List<AgreementBucket> titleBuckets = ordered.stream().filter(b -> b.getTitleId().equals(titleId)).toList();
            if (titleBuckets.stream().anyMatch(b -> b.remaining().isPositive())) {
                continue;
            }
            for (AgreementBucket b : titleBuckets) {
                Money settlementCharge = b.settleCharges();
                if (!settlementCharge.isPositive() && !b.getDiscount().isPositive()) {
                    continue;
                }
                BucketPosting current = postings.getOrDefault(b,
                        new BucketPosting(titleId, b.getPart(), Money.ZERO, Money.ZERO));
                postings.put(b, new BucketPosting(titleId, b.getPart(), current.payment(), current.charge(),
                        settlementCharge, b.getDiscount()));
            }
        }
        return List.copyOf(postings.values());
    }

    /**
     * Desfaz a distribuição de um pagamento estornado.
     *
     * @param posted o pagamento já estava lançado no razão (o encargo reconhecido permanece); senão o
     *               reconhecimento planejado também é desfeito
     */
    public void deallocate(List<BucketPosting> postings, boolean posted) {
        for (BucketPosting p : postings) {
            bucket(p.titleId(), p.part()).orElseThrow(() -> new IllegalStateException("Componente inexistente"))
                    .unpay(p.payment(), posted ? null : p.charge().plus(p.settlementCharge()));
        }
    }

    // ------------------------------------------------------------------ vencimentos, quebra e encerramento

    /** Varredura diária: parcelas vencidas passam a OVERDUE. */
    public List<InstallmentChange> refreshInstallments(LocalDate today) {
        List<InstallmentChange> changes = new ArrayList<>();
        boolean active = status.isActive() || status == AgreementStatus.SETTLED;
        for (AgreementInstallment i : installments) {
            i.refresh(today, active).ifPresent(previous -> changes.add(new InstallmentChange(i, previous, i.getStatus())));
        }
        return changes;
    }

    /** Regra de quebra (fixada na formalização): N parcelas em atraso além da tolerância. */
    public boolean breakRuleMet(LocalDate today) {
        if (!status.isActive()) {
            return false;
        }
        long overdue = installments.stream().filter(i -> i.overdueBeyond(today, breakToleranceDays)).count();
        return overdue >= breakOverdueInstallments;
    }

    public long overdueBeyondTolerance(LocalDate today) {
        return installments.stream().filter(i -> i.overdueBeyond(today, breakToleranceDays)).count();
    }

    /**
     * Parcela que dispara a quebra: a de menor número entre as em atraso além da tolerância (a suspensão
     * da quebra por pagamento em revisão é contada por ela — decisão 484).
     */
    public Optional<AgreementInstallment> breakTriggerInstallment(LocalDate today) {
        return installments.stream().filter(i -> i.overdueBeyond(today, breakToleranceDays))
                .min(Comparator.comparingInt(AgreementInstallment::getNumber));
    }

    public Ending breakAgreement(String reason, LocalDate today, Instant at) {
        Guard.requireState(status.isActive(), "agreement.not_active", "Só um acordo em vigor pode ser quebrado");
        return end(AgreementStatus.BROKEN, reason, today, at);
    }

    public Ending cancel(String reason, LocalDate today, Instant at) {
        Guard.requireState(status.isActive(), "agreement.not_active", "Só um acordo em vigor pode ser cancelado");
        Guard.require(reason != null && !reason.isBlank() && reason.length() <= 500, "agreement.cancel_reason_required",
                "Informe o motivo do cancelamento (até 500 caracteres)");
        return end(AgreementStatus.CANCELLED, reason.trim(), today, at);
    }

    /** Substituído por um novo acordo (renegociação): histórico preservado. */
    public Ending renegotiatedBy(UUID newAgreementId, LocalDate today, Instant at) {
        Guard.requireState(status.isRenegotiable(), "agreement.not_renegotiable",
                "Somente acordo em vigor ou quebrado pode ser renegociado");
        Guard.require(newAgreementId != null, "agreement.invalid", "Novo acordo obrigatório");
        this.replacedByAgreementId = newAgreementId;
        return end(AgreementStatus.RENEGOTIATED, "Substituído pelo acordo " + newAgreementId, today, at);
    }

    public record Ending(StatusChange change, List<InstallmentChange> installmentChanges) {
    }

    private Ending end(AgreementStatus next, String reason, LocalDate today, Instant at) {
        AgreementStatus previous = status;
        status = next;
        statusReason = reason;
        endedAt = at;
        titles.forEach(AgreementTitle::release);
        return new Ending(new StatusChange(previous, next), refreshInstallments(today));
    }

    private List<StatusChange> refreshStatus(Instant at) {
        List<StatusChange> changes = new ArrayList<>();
        boolean allPaid = installments.stream().allMatch(i -> i.getStatus() == InstallmentStatus.PAID);
        if (status == AgreementStatus.FORMALIZED && paidAmount.isPositive()) {
            changes.add(new StatusChange(status, AgreementStatus.IN_PAYMENT));
            status = AgreementStatus.IN_PAYMENT;
            inPaymentAt = at;
        }
        if (status == AgreementStatus.IN_PAYMENT && allPaid) {
            changes.add(new StatusChange(status, AgreementStatus.SETTLED));
            status = AgreementStatus.SETTLED;
            settledAt = at;
            titles.forEach(AgreementTitle::release);
        } else if (status == AgreementStatus.SETTLED && !allPaid) {
            changes.add(new StatusChange(status, AgreementStatus.IN_PAYMENT));
            status = AgreementStatus.IN_PAYMENT;
            settledAt = null;
            titles.forEach(AgreementTitle::reactivate);
        }
        return changes;
    }

    /** LGPD: apaga IP/agente do aceite e o texto livre de motivo; o termo é removido pelo serviço. */
    public void anonymize(Instant at) {
        if (acceptance != null) {
            acceptance = acceptance.withoutNetworkData();
        }
        if (status == AgreementStatus.CANCELLED) {
            statusReason = null;
        }
        if (termDeletedAt == null) {
            termDeletedAt = at;
        }
    }

    // ------------------------------------------------------------------ consultas

    private List<AgreementBucket> orderedBuckets() {
        return buckets.stream().sorted(Comparator.comparing((AgreementBucket b) -> b.getTitleId())
                .thenComparing(AgreementBucket::getPart)).toList();
    }

    public Optional<AgreementInstallment> installment(UUID id) {
        return installments.stream().filter(i -> i.getId().equals(id)).findFirst();
    }

    private Optional<AgreementBucket> bucket(UUID titleId, LedgerPart part) {
        return buckets.stream().filter(b -> b.getTitleId().equals(titleId) && b.getPart() == part).findFirst();
    }

    public List<UUID> titleIds() {
        return titles.stream().map(AgreementTitle::getTitleId).sorted().toList();
    }

    public Money remaining() {
        return totalAmount.minus(paidAmount);
    }

    public List<AgreementInstallment> getInstallments() {
        return List.copyOf(installments);
    }

    public List<AgreementBucket> getBuckets() {
        return orderedBuckets();
    }

    public UUID getProposalId() { return proposalId; }
    public UUID getSimulationId() { return simulationId; }
    public UUID getCreditorId() { return creditorId; }
    public UUID getDebtorId() { return debtorId; }
    public UUID getPortfolioId() { return portfolioId; }
    public UUID getCaseId() { return caseId; }
    public UUID getTeamId() { return teamId; }
    public UUID getPolicyId() { return policyId; }
    public int getPolicyVersion() { return policyVersion; }
    public String getOverrideCode() { return overrideCode; }
    public LocalDate getReferenceDate() { return referenceDate; }
    public int getConcessionLevel() { return concessionLevel; }
    public Percentage getEnvelopeUsage() { return envelopeUsage; }
    public long getMaxDaysOverdue() { return maxDaysOverdue; }
    public String getChannel() { return channel; }
    public AgreementOrigin getOrigin() { return origin; }
    public String getOriginatorType() { return originatorType; }
    public UUID getOriginatorId() { return originatorId; }
    public String getCloserType() { return closerType; }
    public UUID getCloserId() { return closerId; }
    public UUID getApproverId() { return approverId; }
    public UUID getPreNegotiatorId() { return preNegotiatorId; }
    public Money getUpdatedAmount() { return updatedAmount; }
    public Money getDiscountAmount() { return discountAmount; }
    public Money getNegotiatedAmount() { return negotiatedAmount; }
    public Money getFinancingInterest() { return financingInterest; }
    public Money getTotalAmount() { return totalAmount; }
    public Money getDownPayment() { return downPayment; }
    public Money getPaidAmount() { return paidAmount; }
    public int getInstallmentCount() { return installmentCount; }
    public int getBreakOverdueInstallments() { return breakOverdueInstallments; }
    public int getBreakToleranceDays() { return breakToleranceDays; }
    public AgreementStatus getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public Instant getFormalizedAt() { return formalizedAt; }
    public Instant getInPaymentAt() { return inPaymentAt; }
    public Instant getSettledAt() { return settledAt; }
    public Instant getEndedAt() { return endedAt; }
    public UUID getReplacedAgreementId() { return replacedAgreementId; }
    public UUID getReplacedByAgreementId() { return replacedByAgreementId; }
    public int getRenegotiationNumber() { return renegotiationNumber; }
    public Money getIncrementalAmount() { return incrementalAmount; }
    public AcceptanceEvidence getAcceptance() { return acceptance; }
    public Instant getTermDeletedAt() { return termDeletedAt; }
}
