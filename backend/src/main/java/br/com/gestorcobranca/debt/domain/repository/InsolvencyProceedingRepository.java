package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.InsolvencyProceeding;
import br.com.gestorcobranca.debt.domain.model.InsolvencyStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InsolvencyProceedingRepository {

    InsolvencyProceeding save(InsolvencyProceeding proceeding);

    Optional<InsolvencyProceeding> findById(UUID id);

    /** Processos do devedor, o mais recente primeiro. */
    List<InsolvencyProceeding> findByDebtorIdOrderByFiledOnDescCreatedAtDesc(UUID debtorId);

    Optional<InsolvencyProceeding> findFirstByDebtorIdAndStatus(UUID debtorId, InsolvencyStatus status);

    boolean existsByDebtorIdAndStatus(UUID debtorId, InsolvencyStatus status);

    /** Processo na situação em algum dos devedores (matriz e filiais da mesma empresa). */
    boolean existsByDebtorIdInAndStatus(Collection<UUID> debtorIds, InsolvencyStatus status);

    /** Processos dos devedores (unidades da mesma empresa), o mais recente primeiro. */
    List<InsolvencyProceeding> findByDebtorIdInOrderByFiledOnDescCreatedAtDesc(Collection<UUID> debtorIds);
}
