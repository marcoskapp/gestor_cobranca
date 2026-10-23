package br.com.gestorcobranca.incentive.domain.repository;

import br.com.gestorcobranca.incentive.domain.model.attribution.BeneficiaryType;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionLedgerEntry;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionPeriod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Razão de comissão e períodos de apuração. */
public interface LedgerRepository {

    CommissionLedgerEntry save(CommissionLedgerEntry entry);

    Optional<CommissionLedgerEntry> findById(UUID id);

    List<CommissionLedgerEntry> findByPayment(UUID paymentId);

    /** Lançamentos em carência com liberação até a data, créditos antes das compensações. */
    List<CommissionLedgerEntry> findPendingDue(LocalDate until, int limit);

    /** Lançamentos liberados de um período (todos os beneficiários). */
    List<CommissionLedgerEntry> findCleared(LocalDate periodStart);

    /** Lançamentos liberados de um beneficiário num período. */
    List<CommissionLedgerEntry> findCleared(LocalDate periodStart, BeneficiaryType type, UUID beneficiaryId);

    /** Lançamentos ainda em carência de um beneficiário (prévia do período corrente). */
    List<CommissionLedgerEntry> findPending(BeneficiaryType type, UUID beneficiaryId);

    /** Lançamentos de um beneficiário, filtrados por credores (vazio = sem filtro), mais recentes primeiro. */
    Page<CommissionLedgerEntry> findByBeneficiary(BeneficiaryType type, UUID beneficiaryId, LocalDate periodStart,
                                                  Collection<UUID> creditorIds, Pageable pageable);

    List<CommissionLedgerEntry> findByStatement(UUID statementId);

    CommissionPeriod savePeriod(CommissionPeriod period);

    Optional<CommissionPeriod> findPeriod(LocalDate periodStart);

    Optional<CommissionPeriod> findPeriodById(UUID periodId);

    /** Leitura com lock pessimista: serializa fechamentos concorrentes do mesmo período. */
    Optional<CommissionPeriod> findPeriodForUpdate(LocalDate periodStart);

    List<CommissionPeriod> findPeriods();

    Optional<CommissionPeriod> findLatestClosedPeriod();
}
