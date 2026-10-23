package br.com.gestorcobranca.incentive.domain.repository;

import br.com.gestorcobranca.incentive.domain.model.statement.CommissionStatement;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementAdjustment;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementDispute;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementExport;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Extratos e seus itens dependentes (contestações, ajustes, exportações). */
public interface StatementRepository {

    CommissionStatement save(CommissionStatement statement);

    Optional<CommissionStatement> findById(UUID id);

    Optional<CommissionStatement> findByPeriodAndBeneficiary(LocalDate periodStart, UUID beneficiaryId);

    List<CommissionStatement> findByPeriod(LocalDate periodStart);

    List<CommissionStatement> findByPeriodAndStatus(LocalDate periodStart, Collection<StatementStatus> statuses);

    /**
     * @param beneficiaryIds null = todos; vazio = nenhum
     */
    Page<CommissionStatement> search(LocalDate periodStart, Collection<UUID> beneficiaryIds, StatementStatus status,
                                     Pageable pageable);

    StatementDispute saveDispute(StatementDispute dispute);

    Optional<StatementDispute> findDispute(UUID disputeId);

    List<StatementDispute> findDisputes(UUID statementId);

    StatementAdjustment saveAdjustment(StatementAdjustment adjustment);

    Optional<StatementAdjustment> findAdjustment(UUID adjustmentId);

    List<StatementAdjustment> findAdjustments(UUID statementId);

    StatementExport saveExport(StatementExport export);

    Optional<StatementExport> findExport(UUID exportId);

    List<StatementExport> findExports(LocalDate periodStart);
}
