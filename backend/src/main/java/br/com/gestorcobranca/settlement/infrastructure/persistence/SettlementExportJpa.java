package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.SettlementExport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SettlementExportJpa extends JpaRepository<SettlementExport, UUID> {
    List<SettlementExport> findByStatementIdOrderByCreatedAtDesc(UUID statementId);
}
