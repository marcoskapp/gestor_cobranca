package br.com.gestorcobranca.scoring.infrastructure.persistence;

import br.com.gestorcobranca.scoring.domain.model.MonitoringReport;
import br.com.gestorcobranca.scoring.domain.repository.MonitoringReportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface MonitoringReportJpaRepository extends JpaRepository<MonitoringReport, UUID>, MonitoringReportRepository {

    @Override
    Optional<MonitoringReport> findByRunId(UUID runId);

    @Override
    Page<MonitoringReport> findAllByOrderByReferenceDateDesc(Pageable pageable);
}
