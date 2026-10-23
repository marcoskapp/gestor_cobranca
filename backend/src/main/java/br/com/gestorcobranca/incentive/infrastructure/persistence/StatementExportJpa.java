package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.statement.StatementExport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface StatementExportJpa extends JpaRepository<StatementExport, UUID> {

    List<StatementExport> findByPeriodStartOrderByCreatedAtDesc(LocalDate periodStart);
}
