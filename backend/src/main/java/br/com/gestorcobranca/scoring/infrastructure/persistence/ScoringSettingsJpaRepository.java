package br.com.gestorcobranca.scoring.infrastructure.persistence;

import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import br.com.gestorcobranca.scoring.domain.repository.ScoringSettingsRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ScoringSettingsJpaRepository extends JpaRepository<ScoringSettings, UUID>, ScoringSettingsRepository {

    /** Um registro por tenant (único); o RLS restringe à assessoria da transação. */
    Optional<ScoringSettings> findFirstByOrderByCreatedAtAsc();

    @Override
    default Optional<ScoringSettings> findCurrent() {
        return findFirstByOrderByCreatedAtAsc();
    }
}
