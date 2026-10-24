package br.com.gestorcobranca.scoring.infrastructure.persistence;

import br.com.gestorcobranca.scoring.domain.model.CaseScore;
import br.com.gestorcobranca.scoring.domain.repository.CaseScoreRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CaseScoreJpaRepository extends JpaRepository<CaseScore, UUID>, CaseScoreRepository {

    @Override
    Optional<CaseScore> findByCaseId(UUID caseId);

    @Override
    List<CaseScore> findAllByCaseIdIn(Collection<UUID> caseIds);

    @Override
    @Modifying
    @Query("delete from CaseScore s where s.debtorId = :debtorId")
    long deleteByDebtorId(UUID debtorId);
}
