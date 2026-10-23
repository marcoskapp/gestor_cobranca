package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.CaseMovement;
import br.com.gestorcobranca.legal.domain.repository.CaseMovementRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface CaseMovementJpaRepository extends JpaRepository<CaseMovement, UUID>, CaseMovementRepository {

    @Override
    Optional<CaseMovement> findById(UUID id);
}
