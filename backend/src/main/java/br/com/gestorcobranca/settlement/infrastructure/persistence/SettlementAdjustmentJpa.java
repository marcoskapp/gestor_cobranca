package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.SettlementAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SettlementAdjustmentJpa extends JpaRepository<SettlementAdjustment, UUID> {
    List<SettlementAdjustment> findByStatementIdOrderByRequestedAtAsc(UUID statementId);
}
