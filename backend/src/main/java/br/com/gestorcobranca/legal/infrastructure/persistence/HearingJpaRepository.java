package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.Hearing;
import br.com.gestorcobranca.legal.domain.repository.HearingRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface HearingJpaRepository extends JpaRepository<Hearing, UUID>, HearingRepository {

    @Override
    Optional<Hearing> findById(UUID id);
}
