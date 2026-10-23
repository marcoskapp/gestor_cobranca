package br.com.gestorcobranca.settlement.infrastructure.source;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.PaymentDistributionView;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.DivergenceFilter;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.DivergenceView;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Leitura dos fatos de payment, negotiation e organization pelas portas públicas, como processo do
 * sistema no tenant do ator corrente (transação de leitura própria). O escopo por credor já foi
 * aplicado pelo caso de uso do settlement antes de chegar aqui.
 */
@Component
class SystemSettlementSources implements SettlementSources {

    private static final Logger LOG = LoggerFactory.getLogger(SystemSettlementSources.class);
    private static final String PROCESS = "settlement.sources";
    private static final int MAX_DIVERGENCE_PAGES = 25;

    private final PaymentUseCases payments;
    private final ReconciliationUseCases reconciliation;
    private final AgreementUseCases agreements;
    private final PortfolioUseCases portfolios;
    private final ServiceContractUseCases contracts;
    private final CreditorUseCases creditors;
    private final TenantExecution tenantExecution;
    private final CurrentActor currentActor;

    SystemSettlementSources(PaymentUseCases payments, ReconciliationUseCases reconciliation, AgreementUseCases agreements,
                            PortfolioUseCases portfolios, ServiceContractUseCases contracts, CreditorUseCases creditors,
                            TenantExecution tenantExecution, CurrentActor currentActor) {
        this.payments = payments;
        this.reconciliation = reconciliation;
        this.agreements = agreements;
        this.portfolios = portfolios;
        this.contracts = contracts;
        this.creditors = creditors;
        this.tenantExecution = tenantExecution;
        this.currentActor = currentActor;
    }

    @Override
    public Optional<PaymentFacts> paymentOfAllocation(UUID allocationId) {
        return find(() -> {
            PaymentView p = payments.findByAllocation(allocationId);
            return new PaymentFacts(p.id(), p.status(), p.source(), p.method(), p.externalOrigin(), p.paidAt(),
                    p.reconciledAt());
        });
    }

    @Override
    public Optional<AgreementPaymentFacts> agreementPayment(UUID agreementId, UUID allocationId) {
        return find(() -> agreements.paymentDistribution(agreementId, allocationId).map(SystemSettlementSources::facts)
                .orElse(null));
    }

    @Override
    public Optional<Integer> agreementInstallmentCount(UUID agreementId) {
        return find(() -> agreements.get(agreementId).installments().size());
    }

    @Override
    public List<OpenDivergence> openDivergences(UUID creditorId) {
        return system(() -> {
            List<OpenDivergence> result = new ArrayList<>();
            for (int page = 0; page < MAX_DIVERGENCE_PAGES; page++) {
                PageResponse<DivergenceView> views = reconciliation.divergences(new DivergenceFilter("OPEN", null),
                        PageQuery.of(page, PageQuery.MAX_SIZE));
                views.content().stream().filter(d -> creditorId.equals(d.creditorId()))
                        .map(d -> new OpenDivergence(d.id(), d.type(), d.paymentId(), d.amount(), d.occurredOn()))
                        .forEach(result::add);
                if (page + 1 >= views.totalPages()) {
                    return result;
                }
            }
            LOG.atWarn().addKeyValue("creditorId", creditorId)
                    .log("Divergências em aberto acima do limite de leitura do demonstrativo; contagem parcial");
            return result;
        });
    }

    @Override
    public Optional<UUID> contractOfPortfolio(UUID portfolioId) {
        return find(() -> portfolios.get(portfolioId).contractId());
    }

    @Override
    public List<ContractFacts> contracts(UUID creditorId) {
        return system(() -> contracts.listByCreditor(creditorId).stream()
                .map(c -> new ContractFacts(c.id(), c.creditorId(), c.number(), c.startDate(), c.endDate(),
                        c.settlementDay(), c.status()))
                .toList());
    }

    @Override
    public Optional<CreditorFacts> creditor(UUID creditorId) {
        return find(() -> {
            CreditorUseCases.CreditorView c = creditors.get(creditorId);
            return new CreditorFacts(c.id(), c.legalName(), c.document());
        });
    }

    private static AgreementPaymentFacts facts(PaymentDistributionView d) {
        return new AgreementPaymentFacts(d.agreementId(), d.creditorId(), d.debtorId(), d.portfolioId(), d.status(),
                d.appliedAmount(), d.paidAt(), d.source(), d.method(), d.installmentCount(), d.maxDaysOverdue(),
                d.renegotiationNumber(),
                d.installments().stream().map(i -> new InstallmentShare(i.installmentId(), i.number(), i.amount())).toList(),
                d.components().stream().map(c -> new ComponentShare(c.titleId(), c.titleReference(), c.component(),
                        c.amount())).toList());
    }

    /**
     * Leitura que pode não encontrar o recurso: a exceção de "não encontrado" marca a transação de leitura
     * para desfazer, então é tratada fora dela.
     */
    private <T> Optional<T> find(Supplier<T> action) {
        try {
            return Optional.ofNullable(system(action));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(currentActor.tenantId(), PROCESS, action);
    }
}
