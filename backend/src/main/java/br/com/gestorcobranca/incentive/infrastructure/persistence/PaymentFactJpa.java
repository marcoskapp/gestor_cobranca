package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.ledger.PaymentFact;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PaymentFactJpa extends JpaRepository<PaymentFact, UUID> {

    Optional<PaymentFact> findByPaymentId(UUID paymentId);

    boolean existsByPaymentId(UUID paymentId);

    @Query("select p from PaymentFact p where p.agreementId = :agreementId and p.postedAt is null order by p.paidAt")
    List<PaymentFact> findUnpostedByAgreement(UUID agreementId);

    @Query("""
            select distinct p.agreementId from PaymentFact p
            where p.postedAt is null and p.confirmedAt < :before
            """)
    List<UUID> findAgreementsWithUnposted(Instant before, Limit limit);

    @Query("select coalesce(sum(p.commissionableAmount), 0) from PaymentFact p where p.agreementId = :agreementId")
    Object sumCommissionable(UUID agreementId);
}
