package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.ContactType;
import br.com.gestorcobranca.debt.domain.model.DebtorContact;

import java.util.Collection;
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

    /**
     * Devedores com contato do tipo e blind index informados (busca rápida por telefone completo), até {@code limit},
     * em ordem de id. O escopo é aplicado na própria consulta, com a regra de {@code DebtorVisibility} (título num dos
     * credores ou coobrigação vigente de contrato de um deles): o limite conta só os devedores do escopo.
     *
     * @param allowedCreditorIds credores visíveis ao ator; {@code null} = sem restrição
     */
    List<UUID> findDebtorIdsByTypeAndValueHash(ContactType type, String valueHash, Collection<UUID> allowedCreditorIds,
                                               int limit);

    void deleteAll(Iterable<? extends DebtorContact> contacts);
}
