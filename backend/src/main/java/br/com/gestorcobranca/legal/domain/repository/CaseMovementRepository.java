package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.CaseMovement;
import br.com.gestorcobranca.legal.domain.model.MovementType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CaseMovementRepository {

    CaseMovement save(CaseMovement movement);

    java.util.Optional<CaseMovement> findById(UUID id);

    Page<CaseMovement> findByLawsuitIdOrderByOccurredOnDescCreatedAtDesc(UUID lawsuitId, Pageable page);

    boolean existsByLawsuitIdAndProviderAndExternalId(UUID lawsuitId, String provider, String externalId);

    boolean existsByLawsuitIdAndType(UUID lawsuitId, MovementType type);
}
