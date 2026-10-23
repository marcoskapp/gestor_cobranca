package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.PaymentPromise;
import br.com.gestorcobranca.collection.domain.repository.PaymentPromiseRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface PaymentPromiseJpaRepository extends JpaRepository<PaymentPromise, UUID>, PaymentPromiseRepository {

    @Override
    @Query("select distinct p from PaymentPromise p join p.payments pay where pay.paymentId = :paymentId")
    List<PaymentPromise> findByPaymentId(UUID paymentId);

    @Override
    default List<PaymentPromise> findPendingBrokenBy(LocalDate today, int limit) {
        return pendingBrokenBy(today, Limit.of(limit));
    }

    @Query("""
            select p from PaymentPromise p
             where p.status = br.com.gestorcobranca.collection.domain.model.PromiseStatus.PENDING
               and p.breaksAfter < :today
             order by p.breaksAfter, p.id
            """)
    List<PaymentPromise> pendingBrokenBy(LocalDate today, Limit limit);
}
