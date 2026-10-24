package br.com.gestorcobranca.scoring.infrastructure.persistence;

import br.com.gestorcobranca.scoring.domain.model.ModelStatus;
import br.com.gestorcobranca.scoring.domain.model.ScoringModel;
import br.com.gestorcobranca.scoring.domain.repository.ScoringModelRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ScoringModelJpaRepository extends JpaRepository<ScoringModel, UUID>, ScoringModelRepository {

    @Override
    List<ScoringModel> findAllByIdIn(Collection<UUID> ids);

    @Override
    Optional<ScoringModel> findFirstByStatus(ModelStatus status);

    @Override
    List<ScoringModel> findAllByOrderByModelVersionDesc();

    @Override
    @Query("select coalesce(max(m.modelVersion), 0) from ScoringModel m")
    int maxModelVersion();
}
