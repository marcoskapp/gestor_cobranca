package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.ComponentAmounts;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.payment.application.service.TargetResolver.AgreementTarget;
import br.com.gestorcobranca.payment.application.service.TargetResolver.TitleInfo;
import br.com.gestorcobranca.payment.domain.model.AllocationOrigin;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentAllocation;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.TargetKind;
import br.com.gestorcobranca.payment.domain.model.TitleStrategy;
import br.com.gestorcobranca.payment.domain.repository.PaymentAllocationRepository;
import br.com.gestorcobranca.payment.domain.service.AllocationPlanner;
import br.com.gestorcobranca.payment.domain.service.AllocationPlanner.Plan;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Alocação de pagamentos (RB06) e publicação aos consumidores:
 * <ul>
 *   <li><b>acordo</b>: uma alocação às parcelas e um {@code PaymentConfirmed} com {@code agreementId} e as
 *       parcelas ({@code titleId} nulo) — o negotiation distribui entre títulos e componentes e lança no
 *       razão; este módulo <b>não</b> lança essas alocações no razão;</li>
 *   <li><b>títulos avulsos</b>: lançamento no razão do título ({@code DebtLedgerPort}, distribuição
 *       proporcional entre componentes, referência = id da alocação) e um {@code PaymentConfirmed} sem
 *       acordo, com os títulos (cumprimento de promessa no collection).</li>
 * </ul>
 * O {@code paymentId} dos eventos é o id da alocação. Deve ser chamado com o pagamento bloqueado.
 */
@Component
class AllocationService {

    private static final Logger LOG = LoggerFactory.getLogger(AllocationService.class);
    static final String RESOURCE = "payment";

    private final PaymentAllocationRepository allocations;
    private final TargetResolver resolver;
    private final DebtTitleUseCases titles;
    private final DebtLedgerPort ledger;
    private final ApplicationEventPublisher events;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final PaymentMetrics metrics;
    private final Clock clock;

    AllocationService(PaymentAllocationRepository allocations, TargetResolver resolver, DebtTitleUseCases titles,
                      DebtLedgerPort ledger, ApplicationEventPublisher events, CurrentActor currentActor,
                      AuditTrail auditTrail, PaymentMetrics metrics, Clock clock) {
        this.allocations = allocations;
        this.resolver = resolver;
        this.titles = titles;
        this.ledger = ledger;
        this.events = events;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Destino de uma alocação: parcelas de um acordo ou títulos, em ordem de preferência. */
    record Destination(TargetKind kind, UUID agreementId, List<UUID> items, TitleStrategy strategy) {

        Destination {
            Guard.require(kind == TargetKind.AGREEMENT || kind == TargetKind.TITLES, "allocation.target_invalid",
                    "Destino deve ser AGREEMENT ou TITLES");
            Guard.require(kind != TargetKind.AGREEMENT || agreementId != null, "allocation.agreement_required",
                    "Informe o acordo");
            items = items == null ? List.of() : List.copyOf(items);
            Guard.require(kind != TargetKind.TITLES || !items.isEmpty(), "allocation.titles_required", "Informe os títulos");
            Guard.require(items.size() <= PaymentAllocation.MAX_LINES, "allocation.too_many_lines", "Itens demais");
        }

        static Destination of(PaymentInstruction instruction) {
            return instruction.getAgreementId() != null
                    ? new Destination(TargetKind.AGREEMENT, instruction.getAgreementId(),
                    List.of(instruction.getInstallmentId()), null)
                    : new Destination(TargetKind.TITLES, null, List.of(instruction.getTitleId()), TitleStrategy.OLDEST_FIRST);
        }
    }

    /** @param leftoverReason motivo da sobra, quando houve */
    record Result(List<PaymentAllocation> allocations, Money leftover, String leftoverReason) {
    }

    /**
     * Aloca até {@code amount} do pagamento ao destino. O que não couber (acordo encerrado, parcelas
     * ou títulos já quitados, valor acima do saldo) fica como saldo não alocado do pagamento.
     */
    Result allocate(Payment payment, Destination destination, Money amount, AllocationOrigin origin,
                    UUID sourceAllocationId) {
        Guard.require(amount != null && amount.isPositive() && !amount.isGreaterThan(payment.unallocated()),
                "allocation.amount_exceeds_balance", "Valor acima do saldo não alocado do pagamento");
        return destination.kind() == TargetKind.AGREEMENT
                ? toAgreement(payment, destination, amount, origin, sourceAllocationId)
                : toTitles(payment, destination, amount, origin, sourceAllocationId);
    }

    private Result toAgreement(Payment payment, Destination destination, Money amount, AllocationOrigin origin,
                               UUID sourceAllocationId) {
        AgreementTarget target = resolver.agreement(destination.agreementId());
        requireSameParties(payment, target.creditorId(), target.debtorId());
        destination.items().forEach(target::installment);
        if (!target.active()) {
            return leftover(amount, "agreement_closed", payment, destination.kind());
        }
        Plan plan = AllocationPlanner.planAgreement(amount, target.balances(), destination.items());
        if (plan.shares().isEmpty()) {
            return leftover(amount, "excess", payment, destination.kind());
        }
        List<PaymentAllocation.Line> lines = plan.shares().stream()
                .map(s -> PaymentAllocation.Line.installment(s.targetId(), s.amount())).toList();
        PaymentAllocation allocation = allocations.save(PaymentAllocation.toAgreement(payment.getId(), origin,
                target.agreementId(), target.portfolioId(), target.caseId(), lines, sourceAllocationId));
        payment.allocate(allocation.getAmount());
        events.publishEvent(new PaymentConfirmed(Ids.newId(), currentActor.tenantId(), Instant.now(clock),
                allocation.getId(), payment.getDebtorId(), payment.getCreditorId(), target.portfolioId(),
                target.agreementId(), allocation.getAmount().amount(), payment.getPaidAt(), payment.getSource().name(),
                payment.getMethod().name(), lines.stream().map(l -> new PaymentConfirmed.Allocation(null,
                l.installmentId(), l.amount().amount())).toList()));
        recorded(payment, allocation);
        if (plan.leftover().isPositive()) {
            metrics.leftover("excess");
        }
        return new Result(List.of(allocation), plan.leftover(), plan.leftover().isPositive() ? "excess" : null);
    }

    private Result toTitles(Payment payment, Destination destination, Money amount, AllocationOrigin origin,
                            UUID sourceAllocationId) {
        List<TitleInfo> infos = resolver.titles(destination.items());
        for (TitleInfo t : infos) {
            requireSameParties(payment, t.creditorId(), t.debtorId());
        }
        Plan plan = AllocationPlanner.planTitles(amount, TargetResolver.balances(infos), destination.strategy());
        if (plan.shares().isEmpty()) {
            return leftover(amount, "title_unavailable", payment, destination.kind());
        }
        List<PaymentAllocation.Line> lines = plan.shares().stream()
                .map(s -> PaymentAllocation.Line.title(s.targetId(), s.amount())).toList();
        Set<UUID> portfolios = infos.stream().filter(t -> lines.stream().anyMatch(l -> l.titleId().equals(t.id())))
                .map(TitleInfo::portfolioId).filter(Objects::nonNull).collect(Collectors.toSet());
        UUID portfolioId = portfolios.size() == 1 ? portfolios.iterator().next() : null;
        PaymentAllocation allocation = allocations.save(PaymentAllocation.toTitles(payment.getId(), origin, portfolioId,
                lines, sourceAllocationId));
        Map<UUID, TitleInfo> byId = infos.stream().collect(Collectors.toMap(TitleInfo::id, t -> t, (a, b) -> a));
        LocalDate paidOn = payment.getPaidAt().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        List<PaymentConfirmed.Allocation> published = new java.util.ArrayList<>();
        for (PaymentAllocation.Line line : lines) {
            Map<Component, Money> parts = postToLedger(allocation, line, payment.getPaidAt());
            TitleInfo info = byId.get(line.titleId());
            Money honorarium = parts.getOrDefault(Component.HONORARIUM, Money.ZERO);
            Integer daysPastDue = info == null || info.dueDate() == null ? null
                    : (int) Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(info.dueDate(), paidOn));
            published.add(new PaymentConfirmed.Allocation(line.titleId(), null, line.amount().amount(),
                    info == null ? null : info.reference(), daysPastDue, honorarium.amount()));
        }
        payment.allocate(allocation.getAmount());
        events.publishEvent(new PaymentConfirmed(Ids.newId(), currentActor.tenantId(), Instant.now(clock),
                allocation.getId(), payment.getDebtorId(), payment.getCreditorId(), portfolioId, null,
                allocation.getAmount().amount(), payment.getPaidAt(), payment.getSource().name(),
                payment.getMethod().name(), published));
        recorded(payment, allocation);
        if (plan.leftover().isPositive()) {
            metrics.leftover("excess");
        }
        return new Result(List.of(allocation), plan.leftover(), plan.leftover().isPositive() ? "excess" : null);
    }

    /** Devolução ao pagador de parte do saldo não alocado (registro, sem evento aos consumidores). */
    PaymentAllocation refund(Payment payment, Money amount, String reference, UUID sourceAllocationId) {
        Guard.require(amount != null && amount.isPositive() && !amount.isGreaterThan(payment.unallocated()),
                "allocation.amount_exceeds_balance", "Valor acima do saldo não alocado do pagamento");
        PaymentAllocation allocation = allocations.save(PaymentAllocation.refund(payment.getId(), amount, reference,
                sourceAllocationId));
        payment.allocate(amount);
        recorded(payment, allocation);
        return allocation;
    }

    /**
     * Libera para realocação a parte não aplicada de uma alocação a acordo. Se o negotiation não aplicou
     * nada (acordo encerrado), compensa aos consumidores com {@code PaymentReversed} do valor liberado
     * (o negotiation ignora; o incentive retira a base de comissão daquele acordo).
     */
    void release(Payment payment, PaymentAllocation source, Money amount) {
        source.release(amount);
        payment.release(amount);
        if (source.getAppliedAmount() != null && source.getAppliedAmount().isZero()) {
            UUID reversalId = Ids.newId();
            source.compensate(amount, reversalId);
            events.publishEvent(new PaymentReversed(Ids.newId(), currentActor.tenantId(), Instant.now(clock), reversalId,
                    source.getId(), payment.getDebtorId(), payment.getCreditorId(), source.getPortfolioId(),
                    source.getAgreementId(), amount.amount(), "REALLOCATED"));
        } else {
            LOG.atInfo().addKeyValue("allocationId", source.getId()).addKeyValue("paymentId", payment.getId())
                    .log("Excedente de alocação parcialmente aplicada liberado sem compensação aos consumidores");
        }
        allocations.save(source);
        metrics.allocation(source.getKind().name(), source.getOrigin().name(), "released");
    }

    /** Reverte as alocações do pagamento estornado e publica as compensações. */
    void reverseAll(Payment payment, String reason) {
        Instant now = Instant.now(clock);
        for (PaymentAllocation allocation : allocations.findByPaymentIdOrderByCreatedAtAsc(payment.getId())) {
            if (!allocation.isActive()) {
                continue;
            }
            Money pending = allocation.reverse(now);
            if (allocation.getKind() == TargetKind.TITLES) {
                for (PaymentAllocation.Line line : allocation.getLines()) {
                    DebtLedgerPort.LedgerResult result = ledger.reversePayment(new DebtLedgerPort.PaymentReversal(
                            line.titleId(), allocation.getId(), now, reason));
                    metrics.ledgerPosting("reversal", result.applied() ? "applied" : "duplicate");
                }
            }
            if (allocation.publishesEvents() && pending.isPositive()) {
                UUID reversalId = Ids.newId();
                allocation.compensate(pending, reversalId);
                events.publishEvent(new PaymentReversed(Ids.newId(), currentActor.tenantId(), now, reversalId,
                        allocation.getId(), payment.getDebtorId(), payment.getCreditorId(), allocation.getPortfolioId(),
                        allocation.getAgreementId(), pending.amount(), reason));
            }
            allocations.save(allocation);
            metrics.allocation(allocation.getKind().name(), allocation.getOrigin().name(), "reversed");
        }
    }

    /** Lança a linha no razão do título; devolve a distribuição por componente. */
    private Map<Component, Money> postToLedger(PaymentAllocation allocation, PaymentAllocation.Line line, Instant paidAt) {
        Map<String, Money> balances = new LinkedHashMap<>();
        titles.balance(line.titleId()).components()
                .forEach(c -> balances.put(c.component(), Money.ofNullable(c.balance())));
        Map<Component, Money> parts = AllocationPlanner.splitComponents(line.amount(), byComponent(balances));
        try {
            DebtLedgerPort.LedgerResult result = ledger.applyPayment(new DebtLedgerPort.PaymentApplication(line.titleId(),
                    allocation.getId(), new ComponentAmounts(amount(parts, Component.PRINCIPAL),
                    amount(parts, Component.INTEREST), amount(parts, Component.FINE), amount(parts, Component.FEES),
                    amount(parts, Component.HONORARIUM)), paidAt));
            metrics.ledgerPosting("payment", result.applied() ? "applied" : "duplicate");
            return parts;
        } catch (RuntimeException e) {
            metrics.ledgerPosting("payment", "failed");
            throw e;
        }
    }

    private Result leftover(Money amount, String reason, Payment payment, TargetKind kind) {
        metrics.leftover(reason);
        metrics.allocation(kind.name(), "INITIAL", "leftover");
        LOG.atInfo().addKeyValue("paymentId", payment.getId()).addKeyValue("reason", reason)
                .log("Pagamento sem destino disponível; valor fica não alocado para realocação");
        return new Result(List.of(), amount, reason);
    }

    private void recorded(Payment payment, PaymentAllocation allocation) {
        metrics.allocation(allocation.getKind().name(), allocation.getOrigin().name(), "allocated");
        auditTrail.record(AuditEntry.of("payment.allocated", RESOURCE, payment.getId())
                .creditor(payment.getCreditorId())
                .created("allocationId", allocation.getId())
                .created("kind", allocation.getKind())
                .created("origin", allocation.getOrigin())
                .created("amount", allocation.getAmount())
                .meta("agreementId", allocation.getAgreementId())
                .meta("sourceAllocationId", allocation.getSourceAllocationId())
                .meta("lines", allocation.getLines().size()));
    }

    private static void requireSameParties(Payment payment, UUID creditorId, UUID debtorId) {
        if (!payment.getCreditorId().equals(creditorId) || !payment.getDebtorId().equals(debtorId)) {
            throw new BusinessRuleException("allocation.target_mismatch",
                    "O destino não pertence ao credor e devedor do pagamento");
        }
    }

    private static java.math.BigDecimal amount(Map<Component, Money> parts, Component component) {
        Money value = parts.get(component);
        return value == null ? null : value.amount();
    }

    private static Map<Component, Money> byComponent(Map<String, Money> balances) {
        Map<Component, Money> result = new java.util.EnumMap<>(Component.class);
        balances.forEach((name, value) -> {
            try {
                result.put(Component.valueOf(name), value);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Componente do razão desconhecido: " + name, e);
            }
        });
        return result;
    }

    /** Componentes do razão do título ({@code DebtTitleUseCases.ComponentBalanceView#component}). */
    enum Component { PRINCIPAL, INTEREST, FINE, FEES, HONORARIUM }
}
