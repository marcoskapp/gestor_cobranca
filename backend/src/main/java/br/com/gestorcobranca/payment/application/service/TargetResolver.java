package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementFilter;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.payment.domain.model.PaymentAllocation;
import br.com.gestorcobranca.payment.domain.repository.PaymentAllocationRepository;
import br.com.gestorcobranca.payment.domain.service.AllocationPlanner.InstallmentBalance;
import br.com.gestorcobranca.payment.domain.service.AllocationPlanner.TitleBalance;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Posição atual dos destinos de um pagamento, lida pelos casos de uso públicos do negotiation (acordo,
 * parcelas) e do debt (títulos): saldo em aberto de cada parcela — descontadas as alocações deste
 * módulo ainda não baixadas pelo negotiation (em trânsito) —, saldo de cada título e títulos cobertos
 * por acordo em vigor (que não recebem pagamento avulso). Respeita o escopo do ator.
 */
@Component
class TargetResolver {

    private static final Set<String> ACTIVE_AGREEMENT = Set.of("FORMALIZED", "IN_PAYMENT");
    private static final Set<String> OPEN_INSTALLMENT = Set.of("OPEN", "PARTIALLY_PAID", "OVERDUE");
    private static final Set<String> PAYABLE_TITLE = Set.of("OPEN", "SUSPENDED");

    private final AgreementUseCases agreements;
    private final DebtTitleUseCases titles;
    private final PaymentAllocationRepository allocations;

    TargetResolver(AgreementUseCases agreements, DebtTitleUseCases titles, PaymentAllocationRepository allocations) {
        this.agreements = agreements;
        this.titles = titles;
        this.allocations = allocations;
    }

    record InstallmentInfo(UUID id, int number, LocalDate dueDate, Money amount, Money remaining, String status) {
    }

    record AgreementTarget(UUID agreementId, UUID creditorId, UUID debtorId, UUID portfolioId, UUID caseId, String status,
                           List<InstallmentInfo> installments) {

        boolean active() {
            return ACTIVE_AGREEMENT.contains(status);
        }

        List<InstallmentBalance> balances() {
            return installments.stream().filter(i -> i.remaining().isPositive())
                    .map(i -> new InstallmentBalance(i.id(), i.number(), i.remaining())).toList();
        }

        InstallmentInfo installment(UUID id) {
            return installments.stream().filter(i -> i.id().equals(id)).findFirst()
                    .orElseThrow(() -> new BusinessRuleException("allocation.installment_not_in_agreement",
                            "Parcela não pertence ao acordo"));
        }

        Money remaining() {
            return installments.stream().map(InstallmentInfo::remaining).reduce(Money.ZERO, Money::plus);
        }
    }

    /** @param externalId identificação do título no credor; {@code installmentNumber} parcela do título no credor */
    record TitleInfo(UUID id, UUID creditorId, UUID debtorId, UUID portfolioId, LocalDate dueDate, Money openBalance,
                     String status, boolean underAgreement, String externalId, Integer installmentNumber) {

        /** Identificação do título para o credor: {@code externalId} ou {@code externalId/parcela}. */
        String reference() {
            if (externalId == null) {
                return null;
            }
            return installmentNumber == null ? externalId : externalId + "/" + installmentNumber;
        }

        boolean payable() {
            return PAYABLE_TITLE.contains(status) && !underAgreement && openBalance.isPositive();
        }
    }

    AgreementTarget agreement(UUID agreementId) {
        AgreementView a = agreements.get(agreementId);
        Map<UUID, Money> inFlight = new HashMap<>();
        for (PaymentAllocation allocation : allocations.findInFlightByAgreement(agreementId)) {
            allocation.getLines().forEach(l -> inFlight.merge(l.installmentId(), l.amount(), Money::plus));
        }
        List<InstallmentInfo> installments = new ArrayList<>();
        for (InstallmentView i : a.installments()) {
            Money amount = Money.of(i.amount());
            Money remaining = OPEN_INSTALLMENT.contains(i.status())
                    ? amount.minus(Money.ofNullable(i.paidAmount())).minus(inFlight.getOrDefault(i.id(), Money.ZERO))
                    .max(Money.ZERO)
                    : Money.ZERO;
            installments.add(new InstallmentInfo(i.id(), i.number(), i.dueDate(), amount, remaining, i.status()));
        }
        return new AgreementTarget(a.id(), a.creditorId(), a.debtorId(), a.portfolioId(), a.caseId(), a.status(),
                installments);
    }

    /** Títulos na ordem informada (sem repetição), com a posição atual. */
    List<TitleInfo> titles(List<UUID> titleIds) {
        Set<UUID> ids = new LinkedHashSet<>(titleIds);
        List<TitleView> views = ids.stream().map(titles::get).toList();
        Set<UUID> covered = coveredByAgreements(views);
        return views.stream().map(t -> new TitleInfo(t.id(), t.creditorId(), t.debtorId(), t.portfolioId(), t.dueDate(),
                Money.ofNullable(t.openBalance()), t.status(), covered.contains(t.id()), t.externalId(),
                t.installmentNumber())).toList();
    }

    static List<TitleBalance> balances(List<TitleInfo> titles) {
        return titles.stream().filter(TitleInfo::payable)
                .map(t -> new TitleBalance(t.id(), t.dueDate(), t.openBalance())).toList();
    }

    private Set<UUID> coveredByAgreements(List<TitleView> views) {
        Set<UUID> covered = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        for (TitleView t : views) {
            if (!pairs.add(t.creditorId() + "|" + t.debtorId())) {
                continue;
            }
            for (String status : ACTIVE_AGREEMENT) {
                agreements.search(new AgreementFilter(t.creditorId(), t.debtorId(), null, status), PageQuery.of(0, 200))
                        .content().forEach(a -> covered.addAll(a.titleIds()));
            }
        }
        return covered;
    }
}
