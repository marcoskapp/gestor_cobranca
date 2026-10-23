package br.com.gestorcobranca.incentive.domain.repository;

import br.com.gestorcobranca.incentive.domain.model.ledger.PaymentFact;
import br.com.gestorcobranca.incentive.domain.model.ledger.PaymentReversalFact;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Fatos de pagamento e estorno (mesmo agregado lógico: o pagamento e suas reversões). */
public interface PaymentFactRepository {

    PaymentFact save(PaymentFact fact);

    Optional<PaymentFact> findByPaymentId(UUID paymentId);

    boolean existsByPaymentId(UUID paymentId);

    List<PaymentFact> findUnpostedByAgreement(UUID agreementId);

    /** Pagamentos ainda sem lançamento recebidos antes do instante (varredura de contingência). */
    List<UUID> findAgreementsWithUnpostedPaymentsBefore(Instant before, int limit);

    /** Soma comissionável de pagamentos do acordo (para o incremental da renegociação). */
    BigDecimal sumCommissionableByAgreement(UUID agreementId);

    PaymentReversalFact saveReversal(PaymentReversalFact reversal);

    boolean existsReversal(UUID reversalId);

    List<PaymentReversalFact> findReversalsByPayment(UUID paymentId);
}
