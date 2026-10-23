package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.ObligationParty;
import br.com.gestorcobranca.debt.domain.model.ObligationRole;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ObligationPartyRepository {

    ObligationParty save(ObligationParty party);

    Optional<ObligationParty> findById(UUID id);

    List<ObligationParty> findByOriginContractIdOrderByCreatedAtAsc(UUID originContractId);

    Optional<ObligationParty> findByOriginContractIdAndDebtorIdAndRole(UUID originContractId, UUID debtorId,
                                                                       ObligationRole role);

    /** Participações vigentes da pessoa como coobrigada. */
    List<ObligationParty> findByDebtorIdAndEndedOnIsNullOrderByCreatedAtAsc(UUID debtorId);

    /** Credores dos contratos em que a pessoa é coobrigada vigente (define sua visibilidade por escopo). */
    List<UUID> findActiveCreditorIdsByDebtorId(UUID debtorId);

    /** A pessoa é coobrigada vigente de contrato com título em cobrança (aberto ou suspenso). */
    boolean existsActiveWithOpenTitles(UUID debtorId);
}
