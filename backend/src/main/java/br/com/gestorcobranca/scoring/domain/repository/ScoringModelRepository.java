package br.com.gestorcobranca.scoring.domain.repository;

import br.com.gestorcobranca.scoring.domain.model.ModelStatus;
import br.com.gestorcobranca.scoring.domain.model.ScoringModel;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScoringModelRepository {

    ScoringModel save(ScoringModel model);

    /** Grava imediatamente (troca de campeão: o antigo sai antes de o novo entrar — índice único). */
    ScoringModel saveAndFlush(ScoringModel model);

    Optional<ScoringModel> findById(UUID id);

    List<ScoringModel> findAllByIdIn(Collection<UUID> ids);

    /** Campeão ou desafiante (no máximo um de cada por assessoria). */
    Optional<ScoringModel> findFirstByStatus(ModelStatus status);

    /** Todas as versões, da mais recente à mais antiga. */
    List<ScoringModel> findAllByOrderByModelVersionDesc();

    /** Maior número de versão já usado (0 sem modelos). */
    int maxModelVersion();
}
