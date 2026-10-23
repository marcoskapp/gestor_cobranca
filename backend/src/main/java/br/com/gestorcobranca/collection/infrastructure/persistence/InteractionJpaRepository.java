package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.Interaction;
import br.com.gestorcobranca.collection.domain.repository.InteractionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface InteractionJpaRepository extends JpaRepository<Interaction, UUID>, InteractionRepository {
}
