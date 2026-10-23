package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.SettlementStatement;
import br.com.gestorcobranca.settlement.domain.model.StatementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SettlementStatementJpa extends JpaRepository<SettlementStatement, UUID>,
            JpaSpecificationExecutor<SettlementStatement> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SettlementStatement s where s.id = :id")
    Optional<SettlementStatement> lockById(@Param("id") UUID id);

    List<SettlementStatement> findByCreditorIdOrderByPeriodStartDesc(UUID creditorId);

    List<SettlementStatement> findByStatusInOrderByPeriodStartAsc(Collection<StatementStatus> statuses);

    List<SettlementStatement> findByCreditorIdInAndStatusInOrderByPeriodStartAsc(Collection<UUID> creditorIds,
                                                                                Collection<StatementStatus> statuses);
}
