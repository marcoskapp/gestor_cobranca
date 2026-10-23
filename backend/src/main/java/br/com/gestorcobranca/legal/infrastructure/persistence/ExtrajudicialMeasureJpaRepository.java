package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.ExtrajudicialMeasure;
import br.com.gestorcobranca.legal.domain.repository.ExtrajudicialMeasureRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ExtrajudicialMeasureJpaRepository extends JpaRepository<ExtrajudicialMeasure, UUID>,
        ExtrajudicialMeasureRepository {

    @Override
    Optional<ExtrajudicialMeasure> findById(UUID id);
}
