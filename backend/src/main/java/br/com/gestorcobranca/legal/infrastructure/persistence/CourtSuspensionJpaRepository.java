package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.CourtSuspension;
import br.com.gestorcobranca.legal.domain.repository.CourtSuspensionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface CourtSuspensionJpaRepository extends JpaRepository<CourtSuspension, UUID>, CourtSuspensionRepository {

    @Override
    Optional<CourtSuspension> findById(UUID id);

    @Override
    void delete(CourtSuspension suspension);
}
