package br.com.gestorcobranca.scoring.domain.repository;

import br.com.gestorcobranca.scoring.domain.model.CaseScore;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CaseScoreRepository {

    CaseScore save(CaseScore score);

    Optional<CaseScore> findByCaseId(UUID caseId);

    List<CaseScore> findAllByCaseIdIn(Collection<UUID> caseIds);

    /** Remove os scores vigentes do devedor (anonimização LGPD). */
    long deleteByDebtorId(UUID debtorId);
}
