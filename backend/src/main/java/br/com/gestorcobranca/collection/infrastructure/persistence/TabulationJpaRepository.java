package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.Tabulation;
import br.com.gestorcobranca.collection.domain.repository.TabulationRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface TabulationJpaRepository extends JpaRepository<Tabulation, UUID>, TabulationRepository {
}
