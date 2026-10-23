package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.ledger.PaymentReversalFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

interface PaymentReversalJpa extends JpaRepository<PaymentReversalFact, UUID> {

    boolean existsByReversalId(UUID reversalId);

    List<PaymentReversalFact> findByPaymentIdOrderByReversedAtAsc(UUID paymentId);

    @Query("select coalesce(sum(r.amount), 0) from PaymentReversalFact r where r.agreementId = :agreementId")
    Object sumByAgreement(UUID agreementId);
}
