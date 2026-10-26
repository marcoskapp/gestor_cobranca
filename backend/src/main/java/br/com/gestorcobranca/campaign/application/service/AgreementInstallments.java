package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.domain.model.SkipReason;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementFilter;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Próxima parcela em aberto do acordo em vigor de um caso, pela interface pública do negotiation
 * ({@code AgreementUseCases#search}) — o módulo não lê tabelas de outro módulo.
 * <ul>
 *   <li>Acordo do caso: do devedor no credor, em vigor ({@code FORMALIZED}, {@code IN_PAYMENT}), do mesmo
 *       caso (ou, sem caso no acordo, da mesma carteira).</li>
 *   <li>Parcela em aberto: {@code OPEN}, {@code PARTIALLY_PAID} ou {@code OVERDUE}, a de vencimento mais
 *       próximo (empate: menor número); valor = valor da parcela menos o já pago.</li>
 * </ul>
 * Nenhum valor é registrado em log; só os ids voltam para a régua.
 */
@Component
class AgreementInstallments {

    static final Set<String> ACTIVE = Set.of("FORMALIZED", "IN_PAYMENT");
    static final Set<String> OPEN = Set.of("OPEN", "PARTIALLY_PAID", "OVERDUE");

    private final AgreementUseCases agreements;

    AgreementInstallments(AgreementUseCases agreements) {
        this.agreements = agreements;
    }

    /** @param amount valor ainda devido da parcela */
    record NextInstallment(UUID agreementId, UUID installmentId, int number, LocalDate dueDate, BigDecimal amount) {
    }

    /** Resultado da busca: a parcela, ou o motivo de pular ({@link SkipReason}). */
    record Lookup(NextInstallment installment, String unavailableReason) {

        static Lookup of(NextInstallment installment) {
            return new Lookup(installment, null);
        }

        static Lookup unavailable(String reason) {
            return new Lookup(null, reason);
        }

        boolean available() {
            return installment != null;
        }
    }

    Lookup next(UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId) {
        List<AgreementView> active = agreements.search(new AgreementFilter(creditorId, debtorId, null, null),
                        PageQuery.of(0, PageQuery.MAX_SIZE)).content().stream()
                .filter(a -> ACTIVE.contains(a.status()))
                .filter(a -> a.caseId() != null ? a.caseId().equals(caseId) : Objects.equals(a.portfolioId(), portfolioId))
                .toList();
        if (active.isEmpty()) {
            return Lookup.unavailable(SkipReason.NO_ACTIVE_AGREEMENT);
        }
        return active.stream()
                .flatMap(a -> a.installments().stream().filter(i -> OPEN.contains(i.status()))
                        .map(i -> new NextInstallment(a.id(), i.id(), i.number(), i.dueDate(), due(i))))
                .filter(i -> i.amount().signum() > 0)
                .min(Comparator.comparing(NextInstallment::dueDate).thenComparingInt(NextInstallment::number))
                .map(Lookup::of)
                .orElseGet(() -> Lookup.unavailable(SkipReason.NO_OPEN_INSTALLMENT));
    }

    /** Vencimento da próxima parcela em aberto (projeção de audiência); vazio sem acordo em vigor com parcela. */
    Optional<LocalDate> nextDueDate(UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId) {
        return Optional.ofNullable(next(caseId, debtorId, creditorId, portfolioId).installment())
                .map(NextInstallment::dueDate);
    }

    /** Caso do acordo (nulo se o acordo não tem caso). */
    Optional<UUID> caseOf(UUID agreementId) {
        return Optional.ofNullable(agreements.get(agreementId).caseId());
    }

    private static BigDecimal due(InstallmentView i) {
        BigDecimal amount = i.amount() == null ? BigDecimal.ZERO : i.amount();
        BigDecimal paid = i.paidAmount() == null ? BigDecimal.ZERO : i.paidAmount();
        return amount.subtract(paid).max(BigDecimal.ZERO);
    }
}
