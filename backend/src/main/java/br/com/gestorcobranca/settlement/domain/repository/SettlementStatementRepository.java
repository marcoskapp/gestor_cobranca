package br.com.gestorcobranca.settlement.domain.repository;

import br.com.gestorcobranca.settlement.domain.model.CreditorTransfer;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement;
import br.com.gestorcobranca.settlement.domain.model.SettlementAdjustment;
import br.com.gestorcobranca.settlement.domain.model.SettlementExport;
import br.com.gestorcobranca.settlement.domain.model.StatementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Demonstrativos e o que pertence a eles: ajustes, exportações e repasses. */
public interface SettlementStatementRepository {

    SettlementStatement save(SettlementStatement statement);

    Optional<SettlementStatement> findById(UUID id);

    /** Bloqueio pessimista (cálculo, aprovação, repasse). */
    Optional<SettlementStatement> findByIdForUpdate(UUID id);

    List<SettlementStatement> findByCreditorIdOrderByPeriodStartDesc(UUID creditorId);

    /** @param creditorIds credores visíveis; {@code null} = todos */
    Page<SettlementStatement> search(Collection<UUID> creditorIds, UUID creditorId, StatementStatus status,
                                     Pageable pageable);

    List<SettlementStatement> findClosed(Collection<UUID> creditorIds);

    SettlementAdjustment save(SettlementAdjustment adjustment);

    Optional<SettlementAdjustment> findAdjustment(UUID id);

    List<SettlementAdjustment> findAdjustments(UUID statementId);

    SettlementExport save(SettlementExport export);

    Optional<SettlementExport> findExport(UUID id);

    List<SettlementExport> findExports(UUID statementId);

    CreditorTransfer save(CreditorTransfer transfer);

    Optional<CreditorTransfer> findTransfer(UUID id);

    List<CreditorTransfer> findTransfers(UUID statementId);

    Page<CreditorTransfer> searchTransfers(Collection<UUID> creditorIds, UUID creditorId, Pageable pageable);
}
