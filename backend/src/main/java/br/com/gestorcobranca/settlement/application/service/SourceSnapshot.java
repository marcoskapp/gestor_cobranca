package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.out.SettlementSources;
import br.com.gestorcobranca.shared.events.negotiation.AgreementPaymentRecorded;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fatos de outros módulos lidos <em>antes</em> da transação do consumidor de evento: o consumidor usa uma
 * conexão por vez (as leituras abrem e fecham as próprias transações), em vez de abrir uma transação de
 * leitura aninhada enquanto segura a sua. Consulta não pré-carregada cai na porta real.
 */
final class SourceSnapshot implements SettlementSources {

    private final SettlementSources delegate;
    private final Map<UUID, Optional<PaymentFacts>> payments = new HashMap<>();
    private final Map<String, Optional<AgreementPaymentFacts>> distributions = new HashMap<>();
    private final Map<UUID, Optional<Integer>> installmentCounts = new HashMap<>();
    private final Map<UUID, Optional<UUID>> contractsByPortfolio = new HashMap<>();
    private final Map<UUID, List<ContractFacts>> contractsByCreditor = new HashMap<>();

    private SourceSnapshot(SettlementSources delegate) {
        this.delegate = delegate;
    }

    static SourceSnapshot of(SettlementSources delegate) {
        return new SourceSnapshot(delegate);
    }

    /** Pagamento avulso: origem do pagamento e contrato da carteira/credor. */
    static SourceSnapshot forTitlePayment(SettlementSources sources, PaymentConfirmed e) {
        SourceSnapshot s = new SourceSnapshot(sources);
        s.payments.put(e.paymentId(), sources.paymentOfAllocation(e.paymentId()));
        if (e.portfolioId() != null) {
            s.contractsByPortfolio.put(e.portfolioId(), sources.contractOfPortfolio(e.portfolioId()));
        }
        s.contractsByCreditor.put(e.creditorId(), sources.contracts(e.creditorId()));
        return s;
    }

    /** Pagamento de acordo: distribuição no acordo, origem do pagamento e contrato. */
    static SourceSnapshot forAgreementPayment(SettlementSources sources, AgreementPaymentRecorded e) {
        SourceSnapshot s = new SourceSnapshot(sources);
        Optional<AgreementPaymentFacts> distribution = sources.agreementPayment(e.agreementId(), e.paymentId());
        s.distributions.put(key(e.agreementId(), e.paymentId()), distribution);
        s.payments.put(e.paymentId(), sources.paymentOfAllocation(e.paymentId()));
        distribution.map(AgreementPaymentFacts::portfolioId).ifPresent(p ->
                s.contractsByPortfolio.put(p, sources.contractOfPortfolio(p)));
        s.contractsByCreditor.put(e.creditorId(), sources.contracts(e.creditorId()));
        return s;
    }

    static SourceSnapshot forAgreement(SettlementSources sources, UUID agreementId) {
        SourceSnapshot s = new SourceSnapshot(sources);
        s.installmentCounts.put(agreementId, sources.agreementInstallmentCount(agreementId));
        return s;
    }

    @Override
    public Optional<PaymentFacts> paymentOfAllocation(UUID allocationId) {
        Optional<PaymentFacts> found = payments.get(allocationId);
        return found != null ? found : delegate.paymentOfAllocation(allocationId);
    }

    @Override
    public Optional<AgreementPaymentFacts> agreementPayment(UUID agreementId, UUID allocationId) {
        Optional<AgreementPaymentFacts> found = distributions.get(key(agreementId, allocationId));
        return found != null ? found : delegate.agreementPayment(agreementId, allocationId);
    }

    @Override
    public Optional<Integer> agreementInstallmentCount(UUID agreementId) {
        Optional<Integer> found = installmentCounts.get(agreementId);
        return found != null ? found : delegate.agreementInstallmentCount(agreementId);
    }

    @Override
    public List<OpenDivergence> openDivergences(UUID creditorId) {
        return delegate.openDivergences(creditorId);
    }

    @Override
    public Optional<UUID> contractOfPortfolio(UUID portfolioId) {
        Optional<UUID> found = contractsByPortfolio.get(portfolioId);
        return found != null ? found : delegate.contractOfPortfolio(portfolioId);
    }

    @Override
    public List<ContractFacts> contracts(UUID creditorId) {
        List<ContractFacts> found = contractsByCreditor.get(creditorId);
        return found != null ? found : delegate.contracts(creditorId);
    }

    @Override
    public Optional<CreditorFacts> creditor(UUID creditorId) {
        return delegate.creditor(creditorId);
    }

    private static String key(UUID agreementId, UUID allocationId) {
        return agreementId + "|" + allocationId;
    }
}
