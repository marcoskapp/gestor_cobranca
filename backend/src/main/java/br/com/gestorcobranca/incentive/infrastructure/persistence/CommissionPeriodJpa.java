package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionPeriod;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CommissionPeriodJpa extends JpaRepository<CommissionPeriod, UUID> {

    Optional<CommissionPeriod> findByPeriodStart(LocalDate periodStart);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CommissionPeriod p where p.periodStart = :periodStart")
    Optional<CommissionPeriod> findForUpdate(LocalDate periodStart);

    List<CommissionPeriod> findAllByOrderByPeriodStartDesc();

    Optional<CommissionPeriod> findFirstByStatusOrderByPeriodStartDesc(
            br.com.gestorcobranca.incentive.domain.model.ledger.PeriodStatus status);
}
