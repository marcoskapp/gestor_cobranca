package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.CreditorTransfer;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement;
import br.com.gestorcobranca.settlement.domain.model.SettlementAdjustment;
import br.com.gestorcobranca.settlement.domain.model.SettlementExport;
import br.com.gestorcobranca.settlement.domain.model.StatementStatus;
import br.com.gestorcobranca.settlement.domain.repository.StatementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaSettlementStatementRepository implements StatementRepository {

    private final SettlementStatementJpa statements;
    private final SettlementAdjustmentJpa adjustments;
    private final SettlementExportJpa exports;
    private final CreditorTransferJpa transfers;

    JpaSettlementStatementRepository(SettlementStatementJpa statements, SettlementAdjustmentJpa adjustments,
                           SettlementExportJpa exports, CreditorTransferJpa transfers) {
        this.statements = statements;
        this.adjustments = adjustments;
        this.exports = exports;
        this.transfers = transfers;
    }

    @Override
    public SettlementStatement save(SettlementStatement statement) {
        return statements.save(statement);
    }

    @Override
    public Optional<SettlementStatement> findById(UUID id) {
        return statements.findById(id);
    }

    @Override
    public Optional<SettlementStatement> findByIdForUpdate(UUID id) {
        return statements.lockById(id);
    }

    @Override
    public List<SettlementStatement> findByCreditorIdOrderByPeriodStartDesc(UUID creditorId) {
        return statements.findByCreditorIdOrderByPeriodStartDesc(creditorId);
    }

    @Override
    public Page<SettlementStatement> search(Collection<UUID> creditorIds, UUID creditorId, StatementStatus status,
                                            Pageable pageable) {
        Specification<SettlementStatement> spec = Specification.unrestricted();
        if (creditorIds != null) {
            spec = spec.and((root, q, cb) -> creditorIds.isEmpty() ? cb.disjunction()
                    : root.get("creditorId").in(creditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return statements.findAll(spec, pageable);
    }

    @Override
    public List<SettlementStatement> findClosed(Collection<UUID> creditorIds) {
        if (creditorIds == null) {
            return statements.findByStatusInOrderByPeriodStartAsc(StatementStatus.CLOSED);
        }
        return creditorIds.isEmpty() ? List.of()
                : statements.findByCreditorIdInAndStatusInOrderByPeriodStartAsc(creditorIds, StatementStatus.CLOSED);
    }

    @Override
    public SettlementAdjustment save(SettlementAdjustment adjustment) {
        return adjustments.save(adjustment);
    }

    @Override
    public Optional<SettlementAdjustment> findAdjustment(UUID id) {
        return adjustments.findById(id);
    }

    @Override
    public List<SettlementAdjustment> findAdjustments(UUID statementId) {
        return adjustments.findByStatementIdOrderByRequestedAtAsc(statementId);
    }

    @Override
    public SettlementExport save(SettlementExport export) {
        return exports.save(export);
    }

    @Override
    public Optional<SettlementExport> findExport(UUID id) {
        return exports.findById(id);
    }

    @Override
    public List<SettlementExport> findExports(UUID statementId) {
        return exports.findByStatementIdOrderByCreatedAtDesc(statementId);
    }

    @Override
    public CreditorTransfer save(CreditorTransfer transfer) {
        return transfers.save(transfer);
    }

    @Override
    public Optional<CreditorTransfer> findTransfer(UUID id) {
        return transfers.findById(id);
    }

    @Override
    public List<CreditorTransfer> findTransfers(UUID statementId) {
        return transfers.findByStatementIdOrderByCreatedAtAsc(statementId);
    }

    @Override
    public Page<CreditorTransfer> searchTransfers(Collection<UUID> creditorIds, UUID creditorId, Pageable pageable) {
        Specification<CreditorTransfer> spec = Specification.unrestricted();
        if (creditorIds != null) {
            spec = spec.and((root, q, cb) -> creditorIds.isEmpty() ? cb.disjunction()
                    : root.get("creditorId").in(creditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        return transfers.findAll(spec, pageable);
    }
}
