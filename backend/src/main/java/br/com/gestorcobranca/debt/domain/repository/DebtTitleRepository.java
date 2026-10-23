package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DebtTitleRepository {

    DebtTitle save(DebtTitle title);

    Optional<DebtTitle> findById(UUID id);

    /** Lock pessimista: serializa lançamentos e mudanças de status concorrentes no mesmo título. */
    Optional<DebtTitle> findByIdForUpdate(UUID id);

    /** Lock pessimista em lote, sempre na ordem do id (evita deadlock entre operações em lote). */
    List<DebtTitle> findAllByIdInForUpdate(Collection<UUID> ids);

    Optional<DebtTitle> findByCreditorIdAndExternalIdForUpdate(UUID creditorId, String externalId);

    /** @param allowedCreditorIds {@code null} = sem restrição */
    List<DebtTitle> findByDebtor(UUID debtorId, Collection<UUID> allowedCreditorIds);

    boolean existsByDebtorIdAndCreditorId(UUID debtorId, UUID creditorId);

    boolean existsByDebtorIdAndCreditorIdNot(UUID debtorId, UUID creditorId);

    /** Devedor tem títulos ainda em cobrança (abertos ou suspensos). */
    boolean existsOpenByDebtorId(UUID debtorId);

    /** Credores em que o devedor tem títulos (define a visibilidade do devedor por escopo). */
    List<UUID> findCreditorIdsByDebtorId(UUID debtorId);

    Page<DebtTitle> search(TitleSearchCriteria criteria, Pageable pageable);

    /** Devedores distintos com títulos do credor marcados pelo lote, em ordem de id, após {@code afterDebtorId}. */
    List<UUID> findDebtorIdsByImportBatch(UUID creditorId, UUID importBatchId, UUID afterDebtorId, int limit);
}
