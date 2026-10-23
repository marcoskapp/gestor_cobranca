package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.statement.CommissionStatement;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementAdjustment;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementDispute;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementExport;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementStatus;
import br.com.gestorcobranca.incentive.domain.repository.StatementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaStatementRepository implements StatementRepository {

    private final CommissionStatementJpa statements;
    private final StatementDisputeJpa disputes;
    private final StatementAdjustmentJpa adjustments;
    private final StatementExportJpa exports;

    JpaStatementRepository(CommissionStatementJpa statements, StatementDisputeJpa disputes,
                           StatementAdjustmentJpa adjustments, StatementExportJpa exports) {
        this.statements = statements;
        this.disputes = disputes;
        this.adjustments = adjustments;
        this.exports = exports;
    }

    @Override
    public CommissionStatement save(CommissionStatement statement) {
        return statements.save(statement);
    }

    @Override
    public Optional<CommissionStatement> findById(UUID id) {
        return statements.findById(id);
    }

    @Override
    public Optional<CommissionStatement> findByPeriodAndBeneficiary(LocalDate periodStart, UUID beneficiaryId) {
        return statements.findByPeriodStartAndBeneficiaryId(periodStart, beneficiaryId);
    }

    @Override
    public List<CommissionStatement> findByPeriod(LocalDate periodStart) {
        return statements.findByPeriodStart(periodStart);
    }

    @Override
    public List<CommissionStatement> findByPeriodAndStatus(LocalDate periodStart, Collection<StatementStatus> statuses) {
        return statements.findByPeriodStartAndStatusIn(periodStart, statuses);
    }

    @Override
    public Page<CommissionStatement> search(LocalDate periodStart, Collection<UUID> beneficiaryIds, StatementStatus status,
                                            Pageable pageable) {
        return statements.findAll(CommissionStatementJpa.filter(periodStart, beneficiaryIds, status), pageable);
    }

    @Override
    public StatementDispute saveDispute(StatementDispute dispute) {
        return disputes.save(dispute);
    }

    @Override
    public Optional<StatementDispute> findDispute(UUID disputeId) {
        return disputes.findById(disputeId);
    }

    @Override
    public List<StatementDispute> findDisputes(UUID statementId) {
        return disputes.findByStatementIdOrderByOpenedAtAsc(statementId);
    }

    @Override
    public StatementAdjustment saveAdjustment(StatementAdjustment adjustment) {
        return adjustments.save(adjustment);
    }

    @Override
    public Optional<StatementAdjustment> findAdjustment(UUID adjustmentId) {
        return adjustments.findById(adjustmentId);
    }

    @Override
    public List<StatementAdjustment> findAdjustments(UUID statementId) {
        return adjustments.findByStatementIdOrderByRequestedAtAsc(statementId);
    }

    @Override
    public StatementExport saveExport(StatementExport export) {
        return exports.save(export);
    }

    @Override
    public Optional<StatementExport> findExport(UUID exportId) {
        return exports.findById(exportId);
    }

    @Override
    public List<StatementExport> findExports(LocalDate periodStart) {
        return exports.findByPeriodStartOrderByCreatedAtDesc(periodStart);
    }
}
