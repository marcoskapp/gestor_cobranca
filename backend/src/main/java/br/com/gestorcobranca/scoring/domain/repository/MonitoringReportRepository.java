package br.com.gestorcobranca.scoring.domain.repository;

import br.com.gestorcobranca.scoring.domain.model.MonitoringReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface MonitoringReportRepository {

    MonitoringReport save(MonitoringReport report);

    Optional<MonitoringReport> findByRunId(UUID runId);

    Page<MonitoringReport> findAllByOrderByReferenceDateDesc(Pageable pageable);
}
