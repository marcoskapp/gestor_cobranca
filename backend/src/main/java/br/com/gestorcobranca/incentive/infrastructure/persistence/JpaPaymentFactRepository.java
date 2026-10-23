package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.ledger.PaymentFact;
import br.com.gestorcobranca.incentive.domain.model.ledger.PaymentReversalFact;
import br.com.gestorcobranca.incentive.domain.repository.PaymentFactRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaPaymentFactRepository implements PaymentFactRepository {

    private final PaymentFactJpa facts;
    private final PaymentReversalJpa reversals;

    JpaPaymentFactRepository(PaymentFactJpa facts, PaymentReversalJpa reversals) {
        this.facts = facts;
        this.reversals = reversals;
    }

    @Override
    public PaymentFact save(PaymentFact fact) {
        return facts.save(fact);
    }

    @Override
    public Optional<PaymentFact> findByPaymentId(UUID paymentId) {
        return facts.findByPaymentId(paymentId);
    }

    @Override
    public boolean existsByPaymentId(UUID paymentId) {
        return facts.existsByPaymentId(paymentId);
    }

    @Override
    public List<PaymentFact> findUnpostedByAgreement(UUID agreementId) {
        return facts.findUnpostedByAgreement(agreementId);
    }

    @Override
    public List<UUID> findAgreementsWithUnpostedPaymentsBefore(Instant before, int limit) {
        return facts.findAgreementsWithUnposted(before, Limit.of(limit));
    }

    @Override
    public BigDecimal sumCommissionableByAgreement(UUID agreementId) {
        BigDecimal paid = toDecimal(facts.sumCommissionable(agreementId));
        BigDecimal reversed = toDecimal(reversals.sumByAgreement(agreementId));
        return paid.subtract(reversed).max(BigDecimal.ZERO);
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof br.com.gestorcobranca.shared.domain.Money money) {
            return money.amount();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    @Override
    public PaymentReversalFact saveReversal(PaymentReversalFact reversal) {
        return reversals.save(reversal);
    }

    @Override
    public boolean existsReversal(UUID reversalId) {
        return reversals.existsByReversalId(reversalId);
    }

    @Override
    public List<PaymentReversalFact> findReversalsByPayment(UUID paymentId) {
        return reversals.findByPaymentIdOrderByReversedAtAsc(paymentId);
    }
}
