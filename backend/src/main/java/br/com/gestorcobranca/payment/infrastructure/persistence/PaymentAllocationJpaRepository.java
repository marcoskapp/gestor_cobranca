package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.domain.model.PaymentAllocation;
import br.com.gestorcobranca.payment.domain.repository.PaymentAllocationRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface PaymentAllocationJpaRepository extends JpaRepository<PaymentAllocation, UUID>, PaymentAllocationRepository {

    @Override
    @Query("""
            select a from PaymentAllocation a
             where a.agreementId = :agreementId and a.status = br.com.gestorcobranca.payment.domain.model.AllocationStatus.ACTIVE
               and a.outcome = br.com.gestorcobranca.payment.domain.model.AllocationOutcome.PENDING
            """)
    List<PaymentAllocation> findInFlightByAgreement(@Param("agreementId") UUID agreementId);

    @Override
    @Query("""
            select distinct a from PaymentAllocation a join a.lines l
             where l.installmentId = :installmentId
               and a.status = br.com.gestorcobranca.payment.domain.model.AllocationStatus.ACTIVE
            """)
    List<PaymentAllocation> findActiveByInstallment(@Param("installmentId") UUID installmentId);
}
