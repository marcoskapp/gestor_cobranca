package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.ContactType;
import br.com.gestorcobranca.debt.domain.model.DebtorContact;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DebtorContactRepository {

    DebtorContact save(DebtorContact contact);

    Optional<DebtorContact> findById(UUID id);

    /** Contatos do devedor por tipo e prioridade. */
    List<DebtorContact> findByDebtorIdOrderByTypeAscPriorityAscCreatedAtAsc(UUID debtorId);

    List<DebtorContact> findByDebtorIdAndTypeOrderByPriorityAscCreatedAtAsc(UUID debtorId, ContactType type);

    boolean existsByDebtorIdAndTypeAndValueHash(UUID debtorId, ContactType type, String valueHash);

    void deleteAll(Iterable<? extends DebtorContact> contacts);
}
