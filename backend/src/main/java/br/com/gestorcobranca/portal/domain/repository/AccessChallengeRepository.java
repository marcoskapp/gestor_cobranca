package br.com.gestorcobranca.portal.domain.repository;

import br.com.gestorcobranca.portal.domain.model.AccessChallenge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessChallengeRepository {

    AccessChallenge save(AccessChallenge challenge);

    /** Com lock: tentativas concorrentes do mesmo desafio contam todas. */
    Optional<AccessChallenge> findByIdForUpdate(UUID id);

    List<AccessChallenge> findByDebtorId(UUID debtorId);
}
